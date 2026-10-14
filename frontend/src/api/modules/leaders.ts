/**
 * 方向负责人管理接口。
 *
 * 契约：docs/api/openapi.yaml 的 负责人 tag。全部仅学院管理员可用。
 */
import { API_PREFIX, get, patch, post } from '../client'
import type {
  AppointLeaderRequest,
  Member,
  MemberPage,
  ReasonVersionRequest,
  UpdateLeaderRequest,
} from '../types'

const BASE = `${API_PREFIX}/direction-leaders`

export interface ListLeadersParams {
  page?: number
  size?: number
  /** 按负责方向过滤（不是成员归属方向）。 */
  directionId?: string
}

export async function listLeaders(params: ListLeadersParams = {}): Promise<MemberPage> {
  return get<MemberPage>(BASE, { params })
}

/**
 * 任命现有学生为方向负责人。
 *
 * 契约：memberId 必须是 ACTIVE 且 ENABLED 的 STUDENT；同一身份改角色并赋予
 * managedDirectionIds，保留密码与历史，撤销旧会话。
 */
export async function appointLeader(
  payload: AppointLeaderRequest,
  idempotencyKey: string,
): Promise<Member> {
  return post<Member>(BASE, payload, { idempotencyKey })
}

/** 修改负责人资料、授权方向和账号状态；变更授权或账号状态会撤销全部会话。 */
export async function updateLeader(memberId: string, payload: UpdateLeaderRequest): Promise<Member> {
  return patch<Member>(`${BASE}/${memberId}`, payload)
}

/** 撤销负责人任命（恢复为普通学生）。 */
export async function revokeLeader(
  memberId: string,
  payload: ReasonVersionRequest,
  idempotencyKey: string,
): Promise<Member> {
  return post<Member>(`${BASE}/${memberId}/revoke`, payload, { idempotencyKey })
}
