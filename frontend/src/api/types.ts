/**
 * 契约类型的语义化别名。
 *
 * 原始类型由 openapi-typescript 从 docs/api/openapi.yaml 生成到 ./types.gen.ts，
 * 不要手改那个文件。本文件只做两件事：
 *   1. 把深层的 components['schemas'][...] 索引收成有名字的类型，便于业务代码引用；
 *   2. 集中所有「契约约定」的类型，使约定在编译期可被检查。
 *
 * 对应契约版本：1.0.0-draft（docs/api/openapi.yaml）
 */
import type { components } from './types.gen'

type Schemas = components['schemas']

// ---------------------------------------------------------------------------
// 通用信封
// ---------------------------------------------------------------------------

/**
 * 业务信封。契约里没有泛型 ApiResponse，每个响应都是具体的包装 schema，
 * 但字段形状一致，因此这里给出一个结构化类型供客户端解包使用。
 */
export interface Envelope<T> {
  code: 'OK'
  message: string
  data: T
}

/** 失败响应。使用真实 HTTP 状态码，data 恒为 null，绝不包装成 HTTP 200。 */
export type ApiErrorBody = Schemas['ErrorResponse']

/** 成功但无数据的响应（data: null）。 */
export type EmptyBody = Schemas['EmptyResponse']

/** 分页载荷。total 是权限过滤后的记录数，不是页数。 */
export interface PageData<T> {
  items: T[]
  page: number
  size: number
  total: number
}

// ---------------------------------------------------------------------------
// 认证与身份
// ---------------------------------------------------------------------------

export type Role = Schemas['Role']
export type Identity = Schemas['Identity']
export type Session = Schemas['Session']
export type Me = Schemas['Me']
export type LoginRequest = Schemas['LoginRequest']
export type RefreshRequest = Schemas['RefreshRequest']
export type ChangePasswordRequest = Schemas['ChangePasswordRequest']

// ---------------------------------------------------------------------------
// 方向与规则
// ---------------------------------------------------------------------------

export type Direction = Schemas['Direction']
export type DirectionCode = Direction['code']
export type SystemRules = Schemas['SystemRules']

/** 规则确认状态。PENDING_CONFIRMATION 时相关功能只能做骨架，不得启用真实执行。 */
export type ConfigStatus = SystemRules['reportPolicy']['configStatus']

// ---------------------------------------------------------------------------
// 枚举（供 UI 映射与筛选使用）
// ---------------------------------------------------------------------------

export type MembershipStatus = NonNullable<Me['membershipStatus']>
export type AccountStatus = Me['accountStatus']
export type RankingPeriod = NonNullable<Schemas['MyRanking']['period']>
export type TaskType = Schemas['Task']['type']
export type TaskStatus = Schemas['Task']['status']

/**
 * 全部业务错误码。契约中 ErrorResponse.code 是一个扁平枚举，
 * 不按 HTTP 状态码区分，且后端只实现了其中一部分 —— 因此客户端必须
 * 通用地解析 code 字符串，不要 switch 硬编码枚举。
 */
export type ErrorCode = ApiErrorBody['code']
