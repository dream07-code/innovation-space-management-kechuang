import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'
import { resetMockState } from '@/mocks/state'

const BASE = 'http://localhost'
const PRIVACY_KEYS = [
  'studentNo',
  'contact',
  'content',
  'code',
  'codeText',
  'codeLocator',
  'privateTestData',
  'rawProviderResponse',
  'apiToken',
  'password',
]

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  server.resetHandlers()
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
  return body.data.accessToken
}

async function get(path: string, token: string) {
  const response = await fetch(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  return { status: response.status, body: await response.json() }
}

function expectPrivacySafe(body: unknown): void {
  const text = JSON.stringify(body)
  for (const key of PRIVACY_KEYS) {
    expect(text).not.toContain(`"${key}"`)
  }
}

describe('管理员数据看板契约', () => {
  it('默认返回30d并限制风险列表为10条', async () => {
    const token = await login('MOCK-ADMIN', 'Admin-Example9!')
    const result = await get('/api/v1/dashboard/admin', token)

    expect(result.status).toBe(200)
    expect(result.body.data.range).toBe('30d')
    expect(result.body.data.attention.length).toBeLessThanOrEqual(10)
    expect(result.body.data.trends.length).toBeGreaterThan(0)
    expect(result.body.data.directions.length).toBeGreaterThan(0)
    expect(result.body.data.recentActivity.length).toBeGreaterThan(0)
    expectPrivacySafe(result.body.data)
  })

  it('仅接受7d或30d范围', async () => {
    const token = await login('MOCK-ADMIN', 'Admin-Example9!')
    const valid = await get('/api/v1/dashboard/admin?range=7d', token)
    const invalid = await get('/api/v1/dashboard/admin?range=14d', token)

    expect(valid.status).toBe(200)
    expect(valid.body.data.range).toBe('7d')
    expect(invalid.status).toBe(400)
    expect(invalid.body.code).toBe('INVALID_REQUEST')
  })
})

describe('方向负责人数据看板契约', () => {
  it('默认7d且排行榜预览只返回服务端学生结果', async () => {
    const token = await login('MOCK20260002', 'Example-Only9!')
    const result = await get('/api/v1/dashboard/leader', token)

    expect(result.status).toBe(200)
    expect(result.body.data.range).toBe('7d')
    expect(result.body.data.rankingPreview.length).toBeGreaterThan(0)
    expect(result.body.data.rankingPreview.every((item: { memberId: string }) => item.memberId !== '202')).toBe(true)
    expect(result.body.data.students.length).toBeGreaterThan(0)
    expectPrivacySafe(result.body.data)
  })

  it('学生不能读取负责人看板', async () => {
    const token = await login('MOCK20260001', 'Example-Only9!')
    const result = await get('/api/v1/dashboard/leader', token)
    expect(result.status).toBe(403)
  })
})

describe('学生数据看板契约', () => {
  it('默认7d且只返回本人摘要数据', async () => {
    const token = await login('MOCK20260001', 'Example-Only9!')
    const result = await get('/api/v1/dashboard/student', token)

    expect(result.status).toBe(200)
    expect(result.body.data.range).toBe('7d')
    expect(result.body.data.progress).toBeDefined()
    expect(result.body.data.reports).toBeDefined()
    expect(result.body.data.assessments).toBeDefined()
    expect(result.body.data.points).toBeDefined()
    expect(result.body.data.rankingPreview.eligible).toBe(true)
    expectPrivacySafe(result.body.data)
  })

  it('负责人不能读取学生看板', async () => {
    const token = await login('MOCK20260002', 'Example-Only9!')
    const result = await get('/api/v1/dashboard/student', token)
    expect(result.status).toBe(403)
  })
})
