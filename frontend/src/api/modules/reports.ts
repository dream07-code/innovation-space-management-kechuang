/**
 * 报告接口（日报/周报）。
 *
 * 契约：docs/api/openapi.yaml 的 日报 / 周报 tag。两者结构高度对称，
 * 仅字段名不同（日报 reportDate/learningContent/gains/problems/nextPlan，
 * 周报 weekStart+weekEnd/summary/achievements/reflections/nextWeekPlan），
 * 因此用泛型收敛，对外暴露语义化函数。
 *
 * 关键契约点：
 *   - 学生只能看/写本人报告；负责人看本人全部 + 所辖方向正式报告；
 *     管理员只看正式报告，不读他人草稿；
 *   - 每成员每日期/自然周仅一份（含草稿），重复创建 409 REPORT_ALREADY_EXISTS；
 *   - 修改必须带 version 乐观锁，且仅 editable=true 可改；
 *   - 提交是异步评分的开始：HTTP 200 不代表评分完成，finalScore 可空表示评分中；
 *   - 周报 weekStart 必须为周一，weekEnd 由服务端计算。
 */
import { API_PREFIX, get, patch, post, postWithoutIdempotency } from '../client'
import type {
  CreateDailyReportRequest,
  CreateWeeklyReportRequest,
  DailyReport,
  ManualScoreReportRequest,
  PageData,
  ReportImageUpload,
  ScoreStatus,
  UpdateDailyReportRequest,
  UpdateWeeklyReportRequest,
  WeeklyReport,
} from '../types'

/** 报告列表查询参数（日报按 reportDate、周报按 weekStart 过滤）。 */
export interface ListReportsParams {
  page?: number
  size?: number
  memberId?: string
  directionId?: string
  fromDate?: string
  toDate?: string
  status?: 'DRAFT' | 'SUBMITTED'
  scoreStatus?: ScoreStatus
}

// ---------------------------------------------------------------------------
// 日报
// ---------------------------------------------------------------------------

const DAILY = `${API_PREFIX}/daily-reports`

export async function listDailyReports(params: ListReportsParams = {}): Promise<PageData<DailyReport>> {
  return get<PageData<DailyReport>>(DAILY, { params })
}

/** 创建本人草稿。身份从 token 取，不能传 memberId。 */
export async function createDailyReport(
  payload: CreateDailyReportRequest,
  idempotencyKey: string,
): Promise<DailyReport> {
  return post<DailyReport>(DAILY, payload, { idempotencyKey })
}

export async function getDailyReport(reportId: string): Promise<DailyReport> {
  return get<DailyReport>(`${DAILY}/${reportId}`)
}

/** 修改本人报告；仅作者且 editable=true，version 必须一致。 */
export async function updateDailyReport(
  reportId: string,
  payload: UpdateDailyReportRequest,
): Promise<DailyReport> {
  return patch<DailyReport>(`${DAILY}/${reportId}`, payload)
}

/**
 * 正式提交并开始异步评分。相同 version 已 SUBMITTED 时返回现状不加分。
 * 返回的 finalScore 为 null 表示评分中，绝不可渲染成 0 分。
 */
export async function submitDailyReport(
  reportId: string,
  version: number,
  idempotencyKey: string,
): Promise<DailyReport> {
  return post<DailyReport>(`${DAILY}/${reportId}/submit`, { version }, { idempotencyKey })
}

/**
 * 人工评分（学院管理员 / 方向负责人）。
 *
 * 仅对已提交报告生效；评分后 scoreStatus 变为 MANUAL，迟到的 AI 结果不会覆盖。
 * 重复评分按最新分数调整积分差额，因此不需要幂等键。
 */
export async function scoreDailyReport(
  reportId: string,
  payload: ManualScoreReportRequest,
): Promise<DailyReport> {
  // 人工评分是「把最终分数设为 N」的幂等覆盖，重复提交同一分数差额为 0，不需要幂等键。
  return postWithoutIdempotency<DailyReport>(`${DAILY}/${reportId}/score`, payload)
}

// ---------------------------------------------------------------------------
// 周报
// ---------------------------------------------------------------------------

const WEEKLY = `${API_PREFIX}/weekly-reports`

export async function listWeeklyReports(params: ListReportsParams = {}): Promise<PageData<WeeklyReport>> {
  return get<PageData<WeeklyReport>>(WEEKLY, { params })
}

/** 创建本人周报草稿；weekStart 必须为周一。 */
export async function createWeeklyReport(
  payload: CreateWeeklyReportRequest,
  idempotencyKey: string,
): Promise<WeeklyReport> {
  return post<WeeklyReport>(WEEKLY, payload, { idempotencyKey })
}

export async function getWeeklyReport(reportId: string): Promise<WeeklyReport> {
  return get<WeeklyReport>(`${WEEKLY}/${reportId}`)
}

export async function updateWeeklyReport(
  reportId: string,
  payload: UpdateWeeklyReportRequest,
): Promise<WeeklyReport> {
  return patch<WeeklyReport>(`${WEEKLY}/${reportId}`, payload)
}

export async function submitWeeklyReport(
  reportId: string,
  version: number,
  idempotencyKey: string,
): Promise<WeeklyReport> {
  return post<WeeklyReport>(`${WEEKLY}/${reportId}/submit`, { version }, { idempotencyKey })
}

/** 人工评分，语义同日报。 */
export async function scoreWeeklyReport(
  reportId: string,
  payload: ManualScoreReportRequest,
): Promise<WeeklyReport> {
  return postWithoutIdempotency<WeeklyReport>(`${WEEKLY}/${reportId}/score`, payload)
}


// ---------------------------------------------------------------------------
// 笔记图片
// ---------------------------------------------------------------------------

/**
 * 上传一张笔记图片，返回可直接渲染的 URL。
 *
 * 服务端按文件头判定类型（只收 JPEG/PNG/WebP），单张上限 10 MiB，
 * 每人每分钟最多 20 次；超过会返回 429。
 */
export async function uploadReportImage(file: File): Promise<ReportImageUpload> {
  const form = new FormData()
  form.append('file', file)
  return postWithoutIdempotency<ReportImageUpload>(`${API_PREFIX}/report-images`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    // 大图在慢网下可能超过默认 15s，单独放宽。
    timeout: 120000,
  })
}
