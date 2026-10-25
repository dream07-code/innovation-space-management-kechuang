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

type ParameterType = NonNullable<CreateAssessmentProblemRequest['parameterTypes']>[number]

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
  judgeMode: 'STDIO' | 'FUNCTION'
  functionName: string
  parameterTypes: readonly string[]
  returnType: string
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
  const functionMode = form.judgeMode === 'FUNCTION'
  return {
    judgeMode: form.judgeMode,
    functionName: functionMode ? form.functionName.trim() : null,
    parameterTypes: functionMode
      ? ([...form.parameterTypes] as ParameterType[])
      : null,
    returnType: functionMode ? (form.returnType as ParameterType) : null,
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
    judgeMode: 'FUNCTION' as const,
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


/** 函数模式的额外校验：签名完整，且每个测试点输入是参数个数匹配的 JSON 数组。 */
export function validateFunctionProblemForm(
  form: Pick<
    ProblemEditFormShape,
    'judgeMode' | 'functionName' | 'parameterTypes' | 'returnType' | 'assessmentTestCases' | 'practiceTestCases'
  >,
): string | null {
  if (form.judgeMode !== 'FUNCTION') return null
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(form.functionName.trim())) {
    return '函数模式下请填写合法的函数名（字母或下划线开头）'
  }
  if (!form.returnType) return '函数模式下请选择返回类型'
  for (const [label, rows] of [
    ['正式考核测试点', form.assessmentTestCases],
    ['练习测试点', form.practiceTestCases],
  ] as const) {
    for (const [index, row] of rows.entries()) {
      let parsed: unknown
      try {
        parsed = JSON.parse(row.input)
      } catch {
        return `${label} ${index + 1} 的参数必须是 JSON 数组，例如 [10]`
      }
      if (!Array.isArray(parsed) || parsed.length !== form.parameterTypes.length) {
        return `${label} ${index + 1} 的参数个数应为 ${form.parameterTypes.length} 个`
      }
    }
  }
  return null
}
