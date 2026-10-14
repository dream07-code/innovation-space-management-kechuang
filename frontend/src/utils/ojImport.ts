/**
 * OJ 题库 JSON 批量导入的本地预览与安全校验。
 *
 * 预览只保留题目数量、题名索引和稳定错误码，不把原始文件内容写入状态；
 * 后端仍会重新校验并在一个事务中原子写入。
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

function validTestCases(value: unknown, required: boolean): boolean {
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
      item.expectedOutput.length <= 1_000_000
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
  return validTestCases(value.assessmentTestCases, true) && validTestCases(value.practiceTestCases, false)
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
