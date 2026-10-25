import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

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
  judgeMode: 'FUNCTION',
  functionName: 'add',
  parameterTypes: ['int', 'int'],
  returnType: 'int',
  assessmentTestCases: [{ input: '[1, 2]', expectedOutput: '3' }],
  practiceTestCases: [{ input: '[10, -3]', expectedOutput: '7' }],
})

describe('OJ 题库 JSON 导入预览', () => {
  it('读取合法文件并保留两套测试点', () => {
    const result = parseOjImportJson(
      JSON.stringify({ problems: [validProblem('第一题'), validProblem('第二题')] }),
    )

    expect(result.errors).toEqual([])
    expect(result.payload?.problems).toHaveLength(2)
    expect(result.payload?.problems[0]?.assessmentTestCases).toEqual([
      { input: '[1, 2]', expectedOutput: '3' },
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

describe('函数传参（FUNCTION）签名校验', () => {
  const cases: Array<[string, unknown]> = [
    ['judgeMode 不接受 STDIO', { judgeMode: 'STDIO' }],
    ['缺少 functionName', { functionName: undefined }],
    ['functionName 不合法', { functionName: '9fn' }],
    ['parameterTypes 超过 4 个', { parameterTypes: ['int', 'int', 'int', 'int', 'int'] }],
    ['parameterTypes 含未知类型', { parameterTypes: ['double'] }],
    ['returnType 非法', { returnType: 'void' }],
    ['测试点 input 不是 JSON 数组', { assessmentTestCases: [{ input: '1 2', expectedOutput: '3' }] }],
    ['参数个数不匹配', { assessmentTestCases: [{ input: '[1]', expectedOutput: '3' }] }],
    ['参数类型不匹配', { assessmentTestCases: [{ input: '["1", 2]', expectedOutput: '3' }] }],
    ['int 参数越界', { assessmentTestCases: [{ input: '[2147483648, 1]', expectedOutput: '3' }] }],
    ['练习测试点同样按签名校验', { practiceTestCases: [{ input: '1 2', expectedOutput: '3' }] }],
  ]

  it.each(cases)('%s 时返回 INVALID_PROBLEM', (_name, override) => {
    const problem = { ...validProblem('签名题'), ...(override as Record<string, unknown>) }
    const result = parseOjImportJson(JSON.stringify({ problems: [problem] }))
    expect(result.errors).toEqual([{ index: 0, code: 'INVALID_PROBLEM' }])
  })

  it('接受数组与布尔等复合签名', () => {
    const problem = {
      ...validProblem('复合签名题'),
      functionName: 'sortedUnique',
      parameterTypes: ['int[]'],
      returnType: 'int[]',
      assessmentTestCases: [
        { input: '[[4, 1, 4, 2]]', expectedOutput: '[1,2,4]' },
        { input: '[[]]', expectedOutput: '[]' },
      ],
      practiceTestCases: undefined,
    }
    const result = parseOjImportJson(JSON.stringify({ problems: [problem] }))
    expect(result.errors).toEqual([])
  })
})

describe('随仓库分发的导入模板', () => {
  it('tools/oj-bank/oj-import-template.json 通过本地校验', () => {
    const specPath = fileURLToPath(import.meta.url)
    const templatePath = resolve(specPath, '../../../../../tools/oj-bank/oj-import-template.json')
    const result = parseOjImportJson(readFileSync(templatePath, 'utf-8'))
    expect(result.errors).toEqual([])
    expect(result.payload?.problems.length).toBeGreaterThan(0)
  })
})
