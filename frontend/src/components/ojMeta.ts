/**
 * OJ 考核 UI 状态映射。
 *
 * 只负责展示契约枚举，不改变 OJ 判定、AI 评分或人工评分语义。
 */
import type {
  AiEvaluationStatus,
  AssessmentLanguage,
  AssessmentProblemStatus,
  AssessmentResultStatus,
  AssessmentReviewStatus,
  AssessmentScoreSource,
  AssessmentStatus,
  AssessmentSubmissionStatus,
  OjVerdict,
} from '@/api/types'

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export const ASSESSMENT_STATUS_META: Record<AssessmentStatus, { label: string; type: TagType }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PUBLISHED: { label: '已发布', type: 'success' },
  CLOSED: { label: '已关闭', type: 'warning' },
  ARCHIVED: { label: '已归档', type: 'info' },
}

export const PROBLEM_STATUS_META: Record<AssessmentProblemStatus, { label: string; type: TagType }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PUBLISHED: { label: '已发布', type: 'success' },
  RETIRED: { label: '已退役', type: 'danger' },
}

export const SUBMISSION_STATUS_META: Record<AssessmentSubmissionStatus, { label: string; type: TagType }> = {
  RECEIVED: { label: '已接收', type: 'info' },
  JUDGING: { label: '判定中', type: 'warning' },
  JUDGED: { label: '已判定', type: 'success' },
  FAILED: { label: '判定失败', type: 'danger' },
  NEEDS_REVIEW: { label: '待人工复核', type: 'warning' },
}

export const VERDICT_META: Record<OjVerdict, { label: string; type: TagType }> = {
  ACCEPTED: { label: '通过', type: 'success' },
  WRONG_ANSWER: { label: '答案错误', type: 'danger' },
  TIME_LIMIT_EXCEEDED: { label: '超时', type: 'warning' },
  MEMORY_LIMIT_EXCEEDED: { label: '超内存', type: 'warning' },
  RUNTIME_ERROR: { label: '运行错误', type: 'danger' },
  COMPILATION_ERROR: { label: '编译错误', type: 'danger' },
  INTERNAL_ERROR: { label: '内部错误', type: 'info' },
}

export const AI_STATUS_META: Record<AiEvaluationStatus, { label: string; type: TagType }> = {
  PENDING: { label: '评分中', type: 'warning' },
  COMPLETE: { label: '已完成', type: 'success' },
  FAILED: { label: '评分失败', type: 'danger' },
  NEEDS_REVIEW: { label: '待人工复核', type: 'warning' },
}

export const REVIEW_STATUS_META: Record<AssessmentReviewStatus, { label: string; type: TagType }> = {
  ACTIVE: { label: '生效中', type: 'success' },
  SUPERSEDED: { label: '已被替代', type: 'info' },
}

export const SCORE_SOURCE_META: Record<AssessmentScoreSource, { label: string; type: TagType }> = {
  HUMAN: { label: '人工评分', type: 'primary' },
  AI: { label: 'AI 评分', type: 'success' },
  PENDING: { label: '待评分', type: 'warning' },
  UNAVAILABLE: { label: '暂不可用', type: 'danger' },
}

export const RESULT_STATUS_META: Record<AssessmentResultStatus, { label: string; type: TagType }> = {
  PENDING: { label: '处理中', type: 'warning' },
  COMPLETE: { label: '已完成', type: 'success' },
  NEEDS_REVIEW: { label: '待复核', type: 'danger' },
}

export const LANGUAGE_LABELS: Record<AssessmentLanguage, string> = {
  java: 'Java',
  python: 'Python',
  cpp: 'C++',
  javascript: 'JavaScript',
  go: 'Go',
  rust: 'Rust',
}

export const DIFFICULTY_META = {
  EASY: { label: '简单', type: 'success' },
  MEDIUM: { label: '中等', type: 'warning' },
  HARD: { label: '困难', type: 'danger' },
} as const

export function assessmentStatusMeta(status: AssessmentStatus): (typeof ASSESSMENT_STATUS_META)[AssessmentStatus] {
  return ASSESSMENT_STATUS_META[status]
}

export function problemStatusMeta(status: AssessmentProblemStatus): (typeof PROBLEM_STATUS_META)[AssessmentProblemStatus] {
  return PROBLEM_STATUS_META[status]
}

export function submissionStatusMeta(status: AssessmentSubmissionStatus): (typeof SUBMISSION_STATUS_META)[AssessmentSubmissionStatus] {
  return SUBMISSION_STATUS_META[status]
}

export function verdictMeta(verdict: OjVerdict): (typeof VERDICT_META)[OjVerdict] {
  return VERDICT_META[verdict]
}

export function aiStatusMeta(status: AiEvaluationStatus): (typeof AI_STATUS_META)[AiEvaluationStatus] {
  return AI_STATUS_META[status]
}

export function reviewStatusMeta(status: AssessmentReviewStatus): (typeof REVIEW_STATUS_META)[AssessmentReviewStatus] {
  return REVIEW_STATUS_META[status]
}

export function scoreSourceMeta(source: AssessmentScoreSource): (typeof SCORE_SOURCE_META)[AssessmentScoreSource] {
  return SCORE_SOURCE_META[source]
}

export function resultStatusMeta(status: AssessmentResultStatus): (typeof RESULT_STATUS_META)[AssessmentResultStatus] {
  return RESULT_STATUS_META[status]
}

export function languageLabel(language: AssessmentLanguage): string {
  return LANGUAGE_LABELS[language]
}

export function difficultyMetaLabel(
  difficulty: keyof typeof DIFFICULTY_META,
): (typeof DIFFICULTY_META)[keyof typeof DIFFICULTY_META] {
  return DIFFICULTY_META[difficulty]
}

export function scoreLabel(score: number | null): string {
  return score === null ? '待评分' : `${score} 分`
}

/** 考核结果/提交记录中的成员展示：优先姓名，缺失姓名时回退内部 ID。 */
export function memberNameLabel(member: {
  memberId: string
  memberName?: string | null
  memberStudentNo?: string | null
}): string {
  const name = member.memberName?.trim()
  if (!name) return member.memberId
  const studentNo = member.memberStudentNo?.trim()
  return studentNo ? `${name}（${studentNo}）` : name
}
