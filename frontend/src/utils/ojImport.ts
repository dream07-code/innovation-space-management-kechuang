/**
 * OJ 题库 JSON 批量导入的本地预览与安全校验。
 *
 * 新建题目统一为函数传参（FUNCTION）模式：每题必须给出函数签名，
 * 测试点 input 填写与参数列表匹配的 JSON 参数数组，规则与后端
 * FunctionHarness 对齐；后端仍会重新校验并在一个事务中原子写入。
 *
 * 预览只保留题目数量、题名索引和稳定错误码，不把原始文件内容写入状态。
 */
import type {
  CreateAssessmentProblemRequest,
  ImportAssessmentProblemsRequest,
} from '@/api/types'

export const OJ_IMPORT_MAX_BYTES = 5 * 1024 * 1024

export type OjImportErrorCode =
  | 'INVALID_JSON'
  | 'INVALID_IMPORT'
  | 'INVALID_PROBLEM'
  | 'DUPLICATE_TITLE'
  | 'TOO_MANY_PROBLEMS'
  | 'REQUEST_TOO_LARGE'

export interface OjImportError {
  index: number
  code: OjImportErrorCode
}

export interface OjImportPreview {
  payload: ImportAssessmentProblemsRequest | null
  errors: OjImportError[]
}

type RecordValue = Record<string, unknown>

const LANGUAGE_VALUES = new Set(['java', 'python', 'cpp', 'javascript', 'go', 'rust'])
const FUNCTION_TYPE_VALUES = new Set([
  'int',
  'long',
  'bool',
  'string',
  'int[]',
  'long[]',
  'string[]',
  'int[][]',
])
const ARRAY_ITEM_TYPES = {
  'int[]': 'int',
  'long[]': 'long',
  'string[]': 'string',
  'int[][]': 'int[]',
} as const
const INT_MIN = -2147483648
const INT_MAX = 2147483647
const MAX_ARRAY_LENGTH = 100_000

const PROBLEM_FIELDS = new Set([
  'title',
  'summary',
  'description',
  'inputFormat',
  'outputFormat',
  'examples',
  'difficulty',
  'tags',
  'timeLimitMs',
  'memoryLimitKb',
  'allowedLanguages',
  'judgeMode',
  'functionName',
  'parameterTypes',
  'returnType',
  'assessmentTestCases',
  'practiceTestCases',
])

function isRecord(value: unknown): value is RecordValue {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown, max: number, allowBlank = false): value is string {
  return (
    typeof value === 'string' &&
    value.length <= max &&
    (allowBlank || value.trim().length > 0)
  )
}

function isIntegerInRange(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max
}

function validExamples(value: unknown): boolean {
  if (!Array.isArray(value) || value.length > 20) return false
  return value.every((item) => {
    if (!isRecord(item)) return false
    const keys = Object.keys(item)
    if (keys.some((key) => !['input', 'output', 'explanation'].includes(key))) return false
    if (!isText(item.input, 10000) || !isText(item.output, 10000)) return false
    return item.explanation === undefined || item.explanation === null || isText(item.explanation, 2000, true)
  })
}

function argumentMatches(type: string, value: unknown): boolean {
  switch (type) {
    case 'int':
      return (
        typeof value === 'number' &&
        Number.isInteger(value) &&
        value >= INT_MIN &&
        value <= INT_MAX
      )
    case 'long':
      return typeof value === 'number' && Number.isInteger(value)
    case 'bool':
      return typeof value === 'boolean'
    case 'string':
      return typeof value === 'string'
    default: {
      const itemType = ARRAY_ITEM_TYPES[type as keyof typeof ARRAY_ITEM_TYPES]
      if (!itemType) return false
      return Array.isArray(value) && value.length <= MAX_ARRAY_LENGTH
        ? value.every((element) => argumentMatches(itemType, element))
        : false
    }
  }
}

/** 测试点 input 必须是与参数列表个数、类型都匹配的 JSON 数组文本。 */
function validArguments(input: string, parameterTypes: string[]): boolean {
  let parsed: unknown
  try {
    parsed = JSON.parse(input)
  } catch {
    return false
  }
  if (!Array.isArray(parsed) || parsed.length !== parameterTypes.length) return false
  return parameterTypes.every((type, index) => argumentMatches(type, parsed[index]))
}

function validTestCases(value: unknown, required: boolean, parameterTypes: string[]): boolean {
  if (value === undefined && !required) return true
  if (!Array.isArray(value) || value.length > 100 || (required && value.length === 0)) return false
  return value.every((item) => {
    if (!isRecord(item)) return false
    const keys = Object.keys(item)
    return (
      keys.length === 2 &&
      keys.every((key) => key === 'input' || key === 'expectedOutput') &&
      typeof item.input === 'string' &&
      item.input.length <= 1_000_000 &&
      typeof item.expectedOutput === 'string' &&
      item.expectedOutput.length <= 1_000_000 &&
      validArguments(item.input, parameterTypes)
    )
  })
}

function validProblem(value: unknown): value is CreateAssessmentProblemRequest {
  if (!isRecord(value)) return false
  if (Object.keys(value).some((key) => !PROBLEM_FIELDS.has(key))) return false
  if (
    !isText(value.title, 120) ||
    !isText(value.summary, 300) ||
    !isText(value.description, 20000) ||
    !isText(value.inputFormat, 5000) ||
    !isText(value.outputFormat, 5000) ||
    !validExamples(value.examples) ||
    !['EASY', 'MEDIUM', 'HARD'].includes(String(value.difficulty))
  ) {
    return false
  }
  if (!Array.isArray(value.tags) || value.tags.length > 20) return false
  if (!value.tags.every((tag) => isText(tag, 40))) return false
  if (
    !isIntegerInRange(value.timeLimitMs, 100, 30000) ||
    !isIntegerInRange(value.memoryLimitKb, 1024, 1048576) ||
    !Array.isArray(value.allowedLanguages) ||
    value.allowedLanguages.length < 1 ||
    value.allowedLanguages.length > 6 ||
    !value.allowedLanguages.every((language) => typeof language === 'string' && LANGUAGE_VALUES.has(language))
  ) {
    return false
  }
  // 函数传参模式是新建题目唯一模式；签名与测试点参数规则与后端 FunctionHarness 一致。
  if (value.judgeMode !== undefined && value.judgeMode !== 'FUNCTION') return false
  if (typeof value.functionName !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(value.functionName)) {
    return false
  }
  if (
    !Array.isArray(value.parameterTypes) ||
    value.parameterTypes.length > 4 ||
    !value.parameterTypes.every(
      (type) => typeof type === 'string' && FUNCTION_TYPE_VALUES.has(type),
    )
  ) {
    return false
  }
  if (typeof value.returnType !== 'string' || !FUNCTION_TYPE_VALUES.has(value.returnType)) {
    return false
  }
  return (
    validTestCases(value.assessmentTestCases, true, value.parameterTypes) &&
    validTestCases(value.practiceTestCases, false, value.parameterTypes)
  )
}

function error(index: number, code: OjImportErrorCode): OjImportError {
  return { index, code }
}

export function parseOjImportJson(text: string): OjImportPreview {
  if (new TextEncoder().encode(text).length > OJ_IMPORT_MAX_BYTES) {
    return { payload: null, errors: [error(0, 'REQUEST_TOO_LARGE')] }
  }

  let root: unknown
  try {
    root = JSON.parse(text)
  } catch {
    return { payload: null, errors: [error(0, 'INVALID_JSON')] }
  }

  if (!isRecord(root) || Object.keys(root).length !== 1 || !Array.isArray(root.problems)) {
    return { payload: null, errors: [error(0, 'INVALID_IMPORT')] }
  }
  const rawProblems = root.problems
  if (rawProblems.length === 0) {
    return { payload: null, errors: [error(0, 'INVALID_IMPORT')] }
  }
  if (rawProblems.length > 100) {
    return { payload: null, errors: [error(0, 'TOO_MANY_PROBLEMS')] }
  }

  const errors: OjImportError[] = []
  const problems: CreateAssessmentProblemRequest[] = []
  const titles = new Set<string>()
  rawProblems.forEach((item, index) => {
    if (!validProblem(item)) {
      errors.push(error(index, 'INVALID_PROBLEM'))
      return
    }
    const title = item.title.trim().toLocaleLowerCase()
    if (titles.has(title)) {
      errors.push(error(index, 'DUPLICATE_TITLE'))
      return
    }
    titles.add(title)
    problems.push(item)
  })

  if (errors.length > 0) return { payload: null, errors }
  return { payload: { problems }, errors }
}

export async function readOjImportFile(file: File): Promise<OjImportPreview> {
  if (file.size > OJ_IMPORT_MAX_BYTES) {
    return { payload: null, errors: [error(0, 'REQUEST_TOO_LARGE')] }
  }
  return parseOjImportJson(await file.text())
}
