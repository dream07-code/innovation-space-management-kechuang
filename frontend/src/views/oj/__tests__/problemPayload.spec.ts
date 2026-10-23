import { describe, expect, it } from 'vitest'

import {
  buildCreateProblemPayload,
  validateFunctionProblemForm,
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
  judgeMode: 'STDIO' as const,
  functionName: '',
  parameterTypes: [] as string[],
  returnType: 'int',
  examples: [{ input: '[2,7]', output: '0 1', explanation: '' }],
  assessmentTestCases: [{ input: '默认输入', expectedOutput: '默认输出' }],
  practiceTestCases: [] as { input: string; expectedOutput: string }[],
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


describe('函数式判题载荷', () => {
  const functionForm = {
    ...baseForm,
    judgeMode: 'FUNCTION' as const,
    functionName: 'fib',
    parameterTypes: ['long'],
    returnType: 'long',
    assessmentTestCases: [{ input: '[10]', expectedOutput: '55' }],
  }

  it('发送函数签名而不是命令行模板', () => {
    const payload = buildCreateProblemPayload(functionForm)
    expect(payload.judgeMode).toBe('FUNCTION')
    expect(payload.functionName).toBe('fib')
    expect(payload.parameterTypes).toEqual(['long'])
    expect(payload.returnType).toBe('long')
    expect(payload.assessmentTestCases).toEqual([{ input: '[10]', expectedOutput: '55' }])
  })

  it('校验函数名与参数个数', () => {
    expect(validateFunctionProblemForm(functionForm)).toBeNull()
    expect(validateFunctionProblemForm({ ...functionForm, functionName: '' })).toContain('函数名')
    expect(
      validateFunctionProblemForm({
        ...functionForm,
        assessmentTestCases: [{ input: '[10, 20]', expectedOutput: '55' }],
      }),
    ).toContain('参数个数')
    expect(
      validateFunctionProblemForm({
        ...functionForm,
        assessmentTestCases: [{ input: 'n = 10', expectedOutput: '55' }],
      }),
    ).toContain('JSON 数组')
  })

  it('命令行模式不发送函数签名', () => {
    const payload = buildCreateProblemPayload({ ...baseForm, judgeMode: 'STDIO' })
    expect(payload.judgeMode).toBe('STDIO')
    expect(payload.functionName).toBeNull()
    expect(payload.parameterTypes).toBeNull()
    expect(payload.returnType).toBeNull()
  })
})
