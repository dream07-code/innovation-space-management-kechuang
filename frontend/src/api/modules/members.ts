/**
 * 成员管理接口。
 *
 * 契约：docs/api/openapi.yaml 的 成员 tag。
 *
 * 权限矩阵（契约 §5）：
 *   - 学院管理员：全方向成员；
 *   - 方向负责人：仅所辖方向的普通学生，可看列表/详情/改资料，不能改方向；
 *   - 恢复、加分、更正学号、重置密码：仅学院管理员。
 *
 * 手动移出（removeMember）在契约中仍是 pending —— 本期不提供前端入口，
 * 成员的 REMOVED 状态只可能来自后端的自动移出定时任务。
 */
import { API_PREFIX, get, patch, post, postWithoutIdempotency } from '../client'
import type {
  AccountCorrectionRequest,
  AddMemberPointsRequest,
  CreateMemberRequest,
  Member,
  MemberPage,
  ReasonVersionRequest,
  UpdateMemberRequest,
} from '../types'

const BASE = `${API_PREFIX}/members`

/** 成员列表的查询参数。分页上限 200，排序由服务端固定（createdAt DESC, id DESC）。 */
export interface ListMembersParams {
  page?: number
  size?: number
  directionId?: string
  /** 姓名/学号模糊搜索。 */
  keyword?: string
  /** 不传返回权限范围内全部状态。 */
  status?: 'ACTIVE' | 'REMOVED'
}

export async function listMembers(params: ListMembersParams = {}): Promise<MemberPage> {
  return get<MemberPage>(BASE, { params })
}

/**
 * 添加学生并创建账号。
 *
 * 契约：固定创建 STUDENT，初始积分 0、mustChangePassword=true；
 * 禁止提交 role/password/points（Jackson 严格模式，多一个字段 400）。
 */
export async function createMember(
  payload: CreateMemberRequest,
  idempotencyKey: string,
): Promise<Member> {
  return post<Member>(BASE, payload, { idempotencyKey })
}

export async function getMember(memberId: string): Promise<Member> {
  return get<Member>(`${BASE}/${memberId}`)
}

/**
 * 更新学生资料。version 做乐观锁，409 时调用方必须重取后重试，不可盲目覆盖。
 * 不接受学号、角色、密码、积分；负责人不能改 directionId。
 */
export async function updateMember(memberId: string, payload: UpdateMemberRequest): Promise<Member> {
  return patch<Member>(`${BASE}/${memberId}`, payload)
}

/** 管理员更正学号（留痕、撤销全部会话）。 */
export async function correctStudentNumber(
  memberId: string,
  payload: AccountCorrectionRequest,
  idempotencyKey: string,
): Promise<Member> {
  return post<Member>(`${BASE}/${memberId}/account-corrections`, payload, { idempotencyKey })
}

/** 管理员重置密码为统一初始口令（不返回密码；重置后 mustChangePassword=true）。 */
export async function resetMemberPassword(
  memberId: string,
  payload: ReasonVersionRequest,
  idempotencyKey: string,
): Promise<Member> {
  return post<Member>(`${BASE}/${memberId}/password-reset`, payload, { idempotencyKey })
}

/**
 * 管理员恢复被自动移出的学生。
 *
 * 契约：仅 COLLEGE_ADMIN、仅 REMOVED 学生；历史报告与积分流水保留，
 * 净积分清零，membershipStartedAt 重置为恢复时间，开始新的 14 天观察期。
 */
export async function restoreMember(
  memberId: string,
  payload: ReasonVersionRequest,
  idempotencyKey: string,
): Promise<Member> {
  return post<Member>(`${BASE}/${memberId}/restore`, payload, { idempotencyKey })
}

/**
 * 管理员直接给学生加积分。
 *
 * 契约明确：只要求正整数 points，不需要 reason、version 或 Idempotency-Key；
 * 每次调用都新增一条 MANUAL_ADJUSTMENT 流水并累计，不支持减分。
 */
export async function addMemberPoints(
  memberId: string,
  payload: AddMemberPointsRequest,
): Promise<Member> {
  return postWithoutIdempotency<Member>(`${BASE}/${memberId}/points`, payload)
}
