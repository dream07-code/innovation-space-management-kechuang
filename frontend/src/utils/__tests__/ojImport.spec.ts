import { describe, expect, it } from 'vitest'

import { parseOjImportJson } from '@/utils/ojImport'

const validProblem = (title: string) => ({
  title,
  summary: '摘要',
  description: '题面',
  inputFormat: '输入',
  outputFormat: '输出',
  examples: [],
  difficulty: 'EASY',
  tags: ['导入'],
  timeLimitMs: 1000,
  memoryLimitKb: 65536,
  allowedLanguages: ['java'],
  assessmentTestCases: [{ input: '1', expectedOutput: '1' }],
})

describe('OJ 题库 JSON 导入预览', () => {
  it('读取合法文件并保留两套测试点', () => {
    const result = parseOjImportJson(
      JSON.stringify({ problems: [validProblem('第一题'), validProblem('第二题')] }),
    )

    expect(result.errors).toEqual([])
    expect(result.payload?.problems).toHaveLength(2)
    expect(result.payload?.problems[0]?.assessmentTestCases).toEqual([
      { input: '1', expectedOutput: '1' },
    ])
  })

  it('逐题校验失败时不生成可提交 payload 且不泄露题面内容', () => {
    const invalid = validProblem('敏感标题')
    delete (invalid as { assessmentTestCases?: unknown }).assessmentTestCases
    const result = parseOjImportJson(JSON.stringify({ problems: [validProblem('正常题'), invalid] }))

    expect(result.payload).toBeNull()
    expect(result.errors).toEqual([{ index: 1, code: 'INVALID_PROBLEM' }])
    expect(JSON.stringify(result.errors)).not.toContain('敏感标题')
  })

  it('识别重复标题、超量和超过 5 MB 的请求', () => {
    expect(parseOjImportJson(JSON.stringify({ problems: [validProblem('同题'), validProblem('  同题  ')] })).errors)
      .toEqual([{ index: 1, code: 'DUPLICATE_TITLE' }])

    const tooMany = Array.from({ length: 101 }, (_, index) => validProblem(`第${index}题`))
    expect(parseOjImportJson(JSON.stringify({ problems: tooMany })).errors)
      .toEqual([{ index: 0, code: 'TOO_MANY_PROBLEMS' }])

    const oversized = JSON.stringify({
      problems: [{ ...validProblem('大文件'), description: 'x'.repeat(5 * 1024 * 1024) }],
    })
    expect(parseOjImportJson(oversized).errors)
      .toEqual([{ index: 0, code: 'REQUEST_TOO_LARGE' }])
  })

  it('JSON 语法错误只返回安全错误码', () => {
    const result = parseOjImportJson('{"problems": [')
    expect(result.payload).toBeNull()
    expect(result.errors).toEqual([{ index: 0, code: 'INVALID_JSON' }])
  })
})
