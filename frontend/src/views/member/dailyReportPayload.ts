/**
 * 日报表单载荷与校验。
 *
 * 表单只负责学习内容、收获、遇到的问题；日期由后端自动生成。
 * nextPlan/links 仅保留在旧 API 兼容类型中，不进入日报表单载荷。
 */

import type { ReportImage } from '@/api/types'

export interface DailyReportFormValues {
  learningContent: string
  gains: string
  problems: string
  /** 笔记图片；与正文一起提交，随报告版本固化。旧调用方可不传。 */
  images?: ReportImage[]
}

export interface DailyReportCreatePayload {
  learningContent: string
  gains?: string
  problems?: string
  images?: ReportImage[]
}

export interface DailyReportUpdatePayload {
  version: number
  learningContent: string
  gains: string
  problems: string | null
  images: ReportImage[]
}

export type DailyReportField = keyof DailyReportFormValues
export type DailyReportValidationErrors = Partial<Record<DailyReportField, string>>

function optionalTrimmed(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

export function buildCreateDailyReportPayload(
  form: DailyReportFormValues,
): DailyReportCreatePayload {
  const payload: DailyReportCreatePayload = {
    learningContent: form.learningContent.trim(),
  }
  const gains = optionalTrimmed(form.gains)
  const problems = optionalTrimmed(form.problems)
  if (gains !== undefined) payload.gains = gains
  if (problems !== undefined) payload.problems = problems
  if ((form.images ?? []).length) payload.images = form.images
  return payload
}

export function buildUpdateDailyReportPayload(
  version: number,
  form: DailyReportFormValues,
): DailyReportUpdatePayload {
  return {
    version,
    learningContent: form.learningContent.trim(),
    gains: form.gains.trim(),
    problems: form.problems.trim() || null,
    images: form.images ?? [],
  }
}

export function validateDailyReportForm(
  form: DailyReportFormValues,
): DailyReportValidationErrors {
  const errors: DailyReportValidationErrors = {}
  const learningContent = form.learningContent.trim()
  const gains = form.gains.trim()
  const problems = form.problems.trim()

  if (learningContent.length === 0) {
    errors.learningContent = '学习内容不能为空'
  } else if (learningContent.length > 10000) {
    errors.learningContent = '学习内容不能超过 10000 字'
  }

  if (gains.length > 10000) {
    errors.gains = '收获不能超过 10000 字'
  }

  if (problems.length > 5000) {
    errors.problems = '遇到的问题不能超过 5000 字'
  }

  return errors
}
