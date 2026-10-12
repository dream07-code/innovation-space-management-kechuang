/**
 * 排行榜接口。
 *
 * 契约：docs/api/openapi.yaml 的 排行榜 tag。
 *
 * 隐私约束：RankingEntry 只有 memberId/displayName/directionId/directionName/
 * points/rank 六个字段，绝不暴露学号 —— 前端不得关联成员缓存补字段。
 *
 * 月榜（period=MONTH）只用于展示积分，不触发任何淘汰或移出（月度评定已取消）。
 * 同分并列竞赛排名（1,1,3），不能用分页序号当全榜名次。
 */
import { API_PREFIX, get } from '../client'
import type { MyRanking, RankingPage, RankingPeriod } from '../types'

const BASE = `${API_PREFIX}/rankings`

export interface RankingParams {
  page?: number
  size?: number
  period?: RankingPeriod
  /** period=MONTH 必须提供（YYYY-MM）；ALL_TIME 不得提供。 */
  month?: string
}

export async function getOverallRanking(params: RankingParams = {}): Promise<RankingPage> {
  return get<RankingPage>(`${BASE}/overall`, { params })
}

export async function getDirectionRanking(
  directionId: string,
  params: RankingParams = {},
): Promise<RankingPage> {
  return get<RankingPage>(`${BASE}/directions/${directionId}`, { params })
}

/** 本人总榜与方向榜名次；eligible=false 时不在榜，rank 均为 null。 */
export async function getMyRanking(params: Pick<RankingParams, 'period' | 'month'> = {}): Promise<MyRanking> {
  return get<MyRanking>(`${BASE}/me`, { params })
}
