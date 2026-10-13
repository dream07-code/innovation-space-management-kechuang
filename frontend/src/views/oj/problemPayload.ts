/**
 * OJ 题目编辑表单的载荷与测试点校验。
 *
 * 正式考核测试点与题库练习测试点是两套独立数据，保存时必须使用不同字段；
 * 正式考核测试点的顺序即后端存储的 position，练习测试点中的全空白行不发送。
 */
import type {
  AssessmentLanguage,
  CreateAssessmentProblemRequest,
  ProblemExample,
  UpdateAssessmentProblemRequest,
} from '@/api/types'

export interface ProblemTestCaseRow {
  input: string
  expectedOutput: string
}

export interface ProblemEditFormShape {
  title: string
  summary: string
  description: string
  inputFormat: string
  outputFormat: string
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  tagsText: string
  timeLimitMs: number
  memoryLimitKb: number
  allowedLanguages: readonly AssessmentLanguage[]
  examples: ReadonlyArray<{ input: string; output: string; explanation: string }>
  assessmentTestCases: readonly ProblemTestCaseRow[]
  practiceTestCases: readonly ProblemTestCaseRow[]
}

function toExamples(
  examples: ProblemEditFormShape['examples'],
): ProblemExample[] {
  return examples
    .filter((item) => item.input.trim() || item.output.trim())
    .map((item) => ({
      input: item.input,
      output: item.output,
      explanation: item.explanation.trim() || null,
    }))
}

function basePayload(form: ProblemEditFormShape) {
  return {
    title: form.title.trim(),
    summary: form.summary.trim(),
    description: form.description.trim(),
    inputFormat: form.inputFormat.trim(),
    outputFormat: form.outputFormat.trim(),
    examples: toExamples(form.examples),
    difficulty: form.difficulty,
    tags: form.tagsText
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean),
    timeLimitMs: form.timeLimitMs,
    memoryLimitKb: form.memoryLimitKb,
    allowedLanguages: [...form.allowedLanguages],
  }
}

function normalizedPracticeTestCases(rows: readonly ProblemTestCaseRow[]) {
  return rows
    .filter((row) => row.input !== '' || row.expectedOutput !== '')
    .map((row) => ({ input: row.input, expectedOutput: row.expectedOutput }))
}

export function buildCreateProblemPayload(
  form: ProblemEditFormShape,
): CreateAssessmentProblemRequest {
  return {
    ...basePayload(form),
    assessmentTestCases: form.assessmentTestCases.map((row) => ({
      input: row.input,
      expectedOutput: row.expectedOutput,
    })),
    practiceTestCases: normalizedPracticeTestCases(form.practiceTestCases),
  }
}

export function buildUpdateProblemPayload(
  version: number,
  form: ProblemEditFormShape,
): UpdateAssessmentProblemRequest {
  return {
    version,
    ...basePayload(form),
    assessmentTestCases: form.assessmentTestCases.map((row) => ({
      input: row.input,
      expectedOutput: row.expectedOutput,
    })),
    practiceTestCases: normalizedPracticeTestCases(form.practiceTestCases),
  }
}

export function validateProblemTestCaseForm(
  assessmentTestCases: readonly ProblemTestCaseRow[],
  practiceTestCases: readonly ProblemTestCaseRow[],
): string | null {
  if (assessmentTestCases.length < 1) return '至少需要 1 个正式考核测试点'
  if (assessmentTestCases.length > 100) return '正式考核测试点最多 100 个'
  if (practiceTestCases.length > 100) return '练习测试点最多 100 个'
  if (
    [...assessmentTestCases, ...practiceTestCases].some(
      (row) => typeof row.input !== 'string' || typeof row.expectedOutput !== 'string',
    )
  ) {
    return '测试点输入和期望输出必须是文本'
  }
  return null
}
