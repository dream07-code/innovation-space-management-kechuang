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
import { API_PREFIX, get, patch, post } from '../client'
import type {
  CreateDailyReportRequest,
  CreateWeeklyReportRequest,
  DailyReport,
  PageData,
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
