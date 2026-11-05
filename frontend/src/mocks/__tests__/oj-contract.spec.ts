/**
 * OJ Mock 的隐私、幂等和评分优先级契约测试。
 *
 * 这些用例保护第二阶段最容易做错的边界：
 *   - 普通提交响应不返回代码正文；
 *   - 同一 Idempotency-Key 重复提交不会创建重复记录；
 *   - 人工评分优先于 AI 评分；
 *   - OJ 结果是独立 DTO，不混入现有积分字段。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'
import { resetMockState } from '@/mocks/state'
import { resetIdempotencyStore } from '@/mocks/handlers/idempotency'
import { findAssessment } from '@/mocks/ojData'

const BASE = 'http://localhost'

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetIdempotencyStore()
  resetMockState()
})
afterAll(() => server.close())

async function login(account: string, password: string): Promise<string> {
  const response = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account, password }),
  })
  const body = await response.json()
  expect(response.status).toBe(200)
  return body.data.accessToken as string
}

async function request(path: string, token: string, init: RequestInit = {}) {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...init.headers,
    },
  })
  return { status: response.status, body: await response.json() }
}

describe('OJ 提交隐私', () => {
  it('学生只能看到本人提交，普通响应不返回代码正文', async () => {
    const token = await login('MOCK20260001', 'Example-Only9!')
    const result = await request('/api/v1/assessment-submissions', token)

    expect(result.status).toBe(200)
    expect(result.body.data.items.length).toBeGreaterThan(0)
    expect(result.body.data.items.every((item: { memberId: string }) => item.memberId === '201')).toBe(true)
    for (const item of result.body.data.items as Array<Record<string, unknown>>) {
      expect(item).not.toHaveProperty('code')
      expect(item.codeHash).toMatch(/^[a-z0-9-]+$/i)
    }
  })
})

describe('OJ 评分优先级', () => {
  it('自动分按通过测试点计算，人工调整优先且保留独立 OJ 技术判定', async () => {
    const token = await login('MOCK-ADMIN', 'Admin-Example9!')
    const result = await request('/api/v1/assessments/9001/results', token)
    const row = result.body.data.items.find((item: { submissionId: string }) => item.submissionId === '9201')

    expect(result.status).toBe(200)
    expect(row.technicalVerdict).toBe('ACCEPTED')
    // 提交 9201 的自动分来自通过的测试点比例；人工调整后最终分取人工分
    expect(row.autoScore).toBe(100)
    expect(row.humanScore).toBe(90)
    expect(row.finalScore).toBe(90)
    expect(row.scoreSource).toBe('HUMAN')
    expect(row).not.toHaveProperty('points')
    expect(row).not.toHaveProperty('awardedPoints')
  })
})

describe('OJ Mock 数据新鲜度', () => {
  it('进行中的考核使用滚动时间窗，不会随时间推移过期', async () => {
    const assessment = findAssessment('9001')
    const now = Date.now()

    expect(assessment?.status).toBe('PUBLISHED')
    // 一旦写死日期，跨过 endAt 后提交接口会返回 409，这类回归很难第一时间发现。
    expect(Date.parse(assessment!.startAt)).toBeLessThan(now)
    expect(Date.parse(assessment!.endAt)).toBeGreaterThan(now)
  })
})

describe('OJ 提交幂等', () => {
  it('相同 Idempotency-Key 与请求体不会重复创建提交', async () => {
    const token = await login('MOCK20260001', 'Example-Only9!')
    const init: RequestInit = {
      method: 'POST',
      headers: { 'Idempotency-Key': 'oj-submit-test-key' },
      body: JSON.stringify({
        problemId: '9101',
        language: 'python',
        code: 'print("mock-only")',
      }),
    }

    const first = await request('/api/v1/assessments/9001/submissions', token, init)
    const second = await request('/api/v1/assessments/9001/submissions', token, init)

    expect(first.status).toBe(201)
    expect(second.status).toBe(201)
    expect(second.body.data.id).toBe(first.body.data.id)
    expect(first.body.data).not.toHaveProperty('code')
    expect(first.body.data.codeHash).toBe(second.body.data.codeHash)
  })
})

describe('OJ 人工批阅', () => {
  it('人工 review 使用双版本校验并按幂等键只生效一次', async () => {
    const token = await login('MOCK20260002', 'Example-Only9!')
    const init: RequestInit = {
      method: 'POST',
      headers: { 'Idempotency-Key': 'oj-review-test-key' },
      body: JSON.stringify({
        submissionVersion: 2,
        reviewVersion: 1,
        score: 88,
        comment: '结构清楚，边界处理需要补充。',
      }),
    }

    const first = await request('/api/v1/assessment-submissions/9202/reviews', token, init)
    const second = await request('/api/v1/assessment-submissions/9202/reviews', token, init)
    const list = await request('/api/v1/assessment-submissions/9202/reviews', token)

    expect(first.status).toBe(201)
    expect(second.status).toBe(201)
    expect(second.body.data.id).toBe(first.body.data.id)
    expect(list.body.data.total).toBe(1)
    expect(list.body.data.items[0].status).toBe('ACTIVE')
    expect(list.body.data.items[0].score).toBe(88)
  })
})

describe('OJ 题库练习 Mock', () => {
  it('学生读取并运行独立练习测试点', async () => {
    const studentToken = await login('MOCK20260001', 'Example-Only9!')
    const cases = await request('/api/v1/assessment-problems/9101/practice-test-cases', studentToken)
    expect(cases.status).toBe(200)
    expect(cases.body.data.practiceTestCases.length).toBeGreaterThan(0)
    expect(JSON.stringify(cases.body)).not.toContain('hidden-9101')

    const run = await request('/api/v1/assessment-problems/9101/practice-run', studentToken, {
      method: 'POST',
      headers: { 'Idempotency-Key': 'oj-practice-mock-key' },
      body: JSON.stringify({ language: 'python', code: 'print(1)' }),
    })
    expect(run.status).toBe(200)
    expect(run.body.data.totalTestCases).toBe(cases.body.data.practiceTestCases.length)
    expect(run.body.data.testCaseResults[0]).toHaveProperty('input')
    expect(run.body.data.testCaseResults[0]).toHaveProperty('expectedOutput')
    expect(JSON.stringify(run.body)).not.toContain('hidden-9101')
  })
})

describe('OJ 题库批量导入 Mock', () => {
  it('批量导入使用同一事务语义并返回安全结果', async () => {
    const adminToken = await login('MOCK-ADMIN', 'Admin-Example9!')
    const created = await request('/api/v1/assessment-problems/import', adminToken, {
      method: 'POST',
      headers: { 'Idempotency-Key': 'oj-import-contract-key' },
      body: JSON.stringify({
        problems: [
          {
            title: 'Mock 导入 A',
            summary: '导入摘要',
            description: '导入题面',
            inputFormat: '输入',
            outputFormat: '输出',
            examples: [],
            difficulty: 'EASY',
            tags: [],
            timeLimitMs: 1000,
            memoryLimitKb: 65536,
            allowedLanguages: ['python'],
            judgeMode: 'FUNCTION',
            functionName: 'add',
            parameterTypes: ['int', 'int'],
            returnType: 'int',
            assessmentTestCases: [{ input: '[1, 2]', expectedOutput: '3' }],
          },
          {
            title: 'Mock 导入 B',
            summary: '导入摘要',
            description: '导入题面',
            inputFormat: '输入',
            outputFormat: '输出',
            examples: [],
            difficulty: 'EASY',
            tags: [],
            timeLimitMs: 1000,
            memoryLimitKb: 65536,
            allowedLanguages: ['python'],
            judgeMode: 'FUNCTION',
            functionName: 'twice',
            parameterTypes: ['int'],
            returnType: 'int',
            assessmentTestCases: [{ input: '[2]', expectedOutput: '4' }],
          },
        ],
      }),
    })

    expect(created.status).toBe(201)
    expect(created.body.data.importedCount).toBe(2)
    expect(created.body.data.problemIds).toHaveLength(2)
    expect(created.body.data.errors).toEqual([])
  })
})

describe('OJ 题目测试点隔离', () => {
  it('工作人员可读取两套测试点，普通题目响应和学生读取不泄露', async () => {
    const adminToken = await login('MOCK-ADMIN', 'Admin-Example9!')
    const studentToken = await login('MOCK20260001', 'Example-Only9!')
    const created = await request('/api/v1/assessment-problems', adminToken, {
      method: 'POST',
      headers: { 'Idempotency-Key': 'oj-test-cases-contract-key' },
      body: JSON.stringify({
        title: '测试点隔离题',
        summary: '验证两套测试点',
        description: '输入一个整数',
        inputFormat: '一行整数',
        outputFormat: '相同整数',
        examples: [],
        difficulty: 'EASY',
        tags: ['测试'],
        timeLimitMs: 1000,
        memoryLimitKb: 65536,
        allowedLanguages: ['python'],
        assessmentTestCases: [{ input: 'hidden', expectedOutput: 'hidden-result' }],
        practiceTestCases: [{ input: 'practice', expectedOutput: 'practice-result' }],
      }),
    })

    expect(created.status).toBe(201)
    expect(created.body.data).not.toHaveProperty('assessmentTestCases')
    expect(created.body.data).not.toHaveProperty('practiceTestCases')
    expect(JSON.stringify(created.body)).not.toContain('hidden-result')

    const problemId = created.body.data.id as string
    const staffCases = await request(`/api/v1/assessment-problems/${problemId}/test-cases`, adminToken)
    expect(staffCases.status).toBe(200)
    expect(staffCases.body.data.assessmentTestCases).toEqual([
      { position: 1, input: 'hidden', expectedOutput: 'hidden-result' },
    ])
    expect(staffCases.body.data.practiceTestCases).toEqual([
      { position: 1, input: 'practice', expectedOutput: 'practice-result' },
    ])

    const studentCases = await request(
      `/api/v1/assessment-problems/${problemId}/test-cases`,
      studentToken,
    )
    expect(studentCases.status).toBe(403)

    const studentProblem = await request(`/api/v1/assessment-problems/${problemId}`, studentToken)
    expect(studentProblem.status).toBe(404)
  })
})

describe('OJ 考核草稿编辑载荷', () => {
  it('更新接受 items 与 pointsEnabled，并拒绝契约外的 directionId', async () => {
    const token = await login('MOCK-ADMIN', 'Admin-Example9!')

    const updated = await request('/api/v1/assessments/9002', token, {
      method: 'PATCH',
      body: JSON.stringify({
        version: 1,
        title: '鸿蒙方向摸底考核',
        pointsEnabled: true,
        items: [
          { position: 1, type: 'PROGRAM', refId: '9103', score: 60 },
          { position: 2, type: 'PROGRAM', refId: '9101', score: 40 },
        ],
      }),
    })
    expect(updated.status).toBe(200)
    expect(updated.body.data.version).toBe(2)
    expect(updated.body.data.pointsEnabled).toBe(true)
    expect(updated.body.data.items).toEqual([
      { position: 1, type: 'PROGRAM', refId: '9103', score: 60 },
      { position: 2, type: 'PROGRAM', refId: '9101', score: 40 },
    ])
    // problemIds 是派生兼容字段，随 items 重算。
    expect(updated.body.data.problemIds).toEqual(['9103', '9101'])

    // 这正是「保存草稿后再编辑保存报错」的根因：更新契约不接受 directionId。
    const rejected = await request('/api/v1/assessments/9002', token, {
      method: 'PATCH',
      body: JSON.stringify({ version: 2, directionId: '1', title: '改方向' }),
    })
    expect(rejected.status).toBe(400)
    expect(rejected.body.code).toBe('INVALID_REQUEST')
    expect(findAssessment('9002')?.version).toBe(2)
  })
})
