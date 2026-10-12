/**
 * 通用展示组件：与契约字段一一对应的状态映射。
 *
 * 契约文本（docs/api/openapi.yaml）是 UI 文案的唯一依据：
 *   - finalScore 为 null 表示评分中，绝不能显示成 0 分；
 *   - REMOVED 成员要展示 removedAt 与 removalReason；
 *   - scoreStatus 为枚举，UI 只做映射不改语义。
 */
import type { ReportStatus, ScoreStatus } from '@/api/types'

export const SCORE_STATUS_META: Record<ScoreStatus, { label: string; type: 'info' | 'success' | 'warning' | 'danger' | 'primary' }> = {
  NOT_REQUESTED: { label: '未提交评分', type: 'info' },
  QUEUED: { label: '评分排队中', type: 'warning' },
  PROCESSING: { label: '评分中', type: 'warning' },
  SCORED: { label: '已评分', type: 'success' },
  RETRYING: { label: '评分重试中', type: 'warning' },
  REVIEW_REQUIRED: { label: '待人工复核', type: 'danger' },
  MANUAL: { label: '人工评分', type: 'primary' },
}

export const REPORT_STATUS_META: Record<ReportStatus, { label: string; type: 'info' | 'success' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  SUBMITTED: { label: '已提交', type: 'success' },
}

export function reportStatusMeta(status: ReportStatus): (typeof REPORT_STATUS_META)[ReportStatus] {
  return REPORT_STATUS_META[status]
}

export function scoreStatusMeta(status: ScoreStatus): (typeof SCORE_STATUS_META)[ScoreStatus] {
  return SCORE_STATUS_META[status]
}

/**
 * 评分与积分展示。
 *
 * 契约明确 finalScore 为 null 表示「评分尚未完成」，不是 0 分；
 * 若渲染成 0 会误导用户以为得到了最低分。
 */
export function finalScoreLabel(score: number | null, scoreStatus: ScoreStatus): string {
  if (score !== null) return String(score)
  return SCORE_STATUS_META[scoreStatus].label
}
