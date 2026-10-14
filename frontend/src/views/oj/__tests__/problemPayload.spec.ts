import { describe, expect, it } from 'vitest'

import {
  buildCreateProblemPayload,
  buildUpdateProblemPayload,
  validateProblemTestCaseForm,
} from '@/views/oj/problemPayload'

const baseForm = {
  title: '两数之和',
  summary: '基础题',
  description: '给定数组和目标值',
  inputFormat: '一行数组',
  outputFormat: '两个下标',
  difficulty: 'EASY' as const,
  tagsText: '数组, 哈希',
  timeLimitMs: 1000,
  memoryLimitKb: 65536,
  allowedLanguages: ['java'] as const,
  examples: [{ input: '[2,7]', output: '0 1', explanation: '' }],
}

describe('OJ 题目测试点表单载荷', () => {
  it('创建时分别发送正式考核与练习测试点', () => {
    const payload = buildCreateProblemPayload({
      ...baseForm,
      assessmentTestCases: [
        { input: '第一组隐藏输入', expectedOutput: '1' },
        { input: '第二组隐藏输入', expectedOutput: '2' },
      ],
      practiceTestCases: [
        { input: '', expectedOutput: '' },
        { input: '练习输入', expectedOutput: '3' },
      ],
    })

    expect(payload.assessmentTestCases).toEqual([
      { input: '第一组隐藏输入', expectedOutput: '1' },
      { input: '第二组隐藏输入', expectedOutput: '2' },
    ])
    expect(payload.practiceTestCases).toEqual([
      { input: '练习输入', expectedOutput: '3' },
    ])
    expect(payload.assessmentTestCases).not.toHaveProperty('position')
    expect(payload.tags).toEqual(['数组', '哈希'])
    expect(payload.examples[0]?.explanation).toBeNull()
  })

  it('更新时携带版本并保留两套测试点的顺序', () => {
    const payload = buildUpdateProblemPayload(4, {
      ...baseForm,
      assessmentTestCases: [{ input: 'a', expectedOutput: 'A' }],
      practiceTestCases: [{ input: 'p', expectedOutput: 'P' }],
    })

    expect(payload).toMatchObject({
      version: 4,
      assessmentTestCases: [{ input: 'a', expectedOutput: 'A' }],
      practiceTestCases: [{ input: 'p', expectedOutput: 'P' }],
    })
    expect(Object.keys(payload).filter((key) => key.endsWith('TestCases'))).toHaveLength(2)
  })

  it('正式考核测试点必须为 1 到 100，练习测试点最多 100', () => {
    expect(validateProblemTestCaseForm([], [])).toBe('至少需要 1 个正式考核测试点')
    expect(
      validateProblemTestCaseForm(
        Array.from({ length: 101 }, (_, index) => ({ input: String(index), expectedOutput: 'x' })),
        [],
      ),
    ).toBe('正式考核测试点最多 100 个')
    expect(
      validateProblemTestCaseForm(
        [{ input: 'ok', expectedOutput: 'ok' }],
        Array.from({ length: 101 }, (_, index) => ({ input: String(index), expectedOutput: 'x' })),
      ),
    ).toBe('练习测试点最多 100 个')
    expect(validateProblemTestCaseForm([{ input: '', expectedOutput: '' }], [])).toBeNull()
  })
})
