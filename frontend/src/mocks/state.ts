/**
 * Mock 专用：响应构造与内存状态。
 *
 * 严格照契约的形状返回，使前端代码在切换到真实后端时无需改动：
 *   - 成功：HTTP 200/201/202 + {code:"OK", message:"Success", data}
 *   - 失败：真实 HTTP 状态码 + {code, message, data:null}，绝不包成 200
 *
 * 所有数据均为虚构，符合仓库「不提交真实学生名单、学号、联系方式」的要求。
 */
import { HttpResponse } from 'msw'

import { API_PREFIX } from '@/api/constants'
import type { Direction, Identity, Me, Session } from '@/api/types'

/** 业务时区固定为 Asia/Shanghai，与后端一致。 */
export const BUSINESS_TIMEZONE = 'Asia/Shanghai'

// ---------------------------------------------------------------------------
// 响应构造
// ---------------------------------------------------------------------------

/** 成功响应，默认 HTTP 200。 */
export function ok<T>(data: T, status = 200) {
  return HttpResponse.json({ code: 'OK', message: 'Success', data }, { status })
}

/** 成功但无数据（data: null）。用于改密、退出、移出等动作。 */
export function okEmpty() {
  return HttpResponse.json({ code: 'OK', message: 'Success', data: null }, { status: 200 })
}

/**
 * 失败响应。
 *
 * 契约 §4.1：失败使用正确的 HTTP 状态，data 为 null，
 * 不把失败包装成 HTTP 200。因此这里按错误码映射到真实状态码。
 */
export function fail(code: string, status: number, message = 'Request could not be completed', headers?: Record<string, string>) {
  return HttpResponse.json(
    { code, message, data: null },
    { status, ...(headers ? { headers } : {}) },
  )
}

/** 常见失败的便捷封装，状态码与契约 §4.3 的表一致。 */
export const errors = {
  invalidRequest: () => fail('INVALID_REQUEST', 400),
  invalidCredentials: () => fail('INVALID_CREDENTIALS', 401, '账号或密码错误'),
  unauthorized: () => fail('UNAUTHORIZED', 401),
  tokenExpired: () => fail('TOKEN_EXPIRED', 401),
  refreshInvalid: () => fail('REFRESH_TOKEN_INVALID', 401),
  forbidden: () => fail('FORBIDDEN', 403),
  passwordChangeRequired: () => fail('PASSWORD_CHANGE_REQUIRED', 403),
  accountDisabled: () => fail('ACCOUNT_DISABLED', 403),
  memberRemoved: () => fail('MEMBER_REMOVED', 403),
  notFound: () => fail('NOT_FOUND', 404),
  versionConflict: () => fail('VERSION_CONFLICT', 409),
  idempotencyConflict: () => fail('IDEMPOTENCY_CONFLICT', 409),
  alreadyExists: () => fail('ACCOUNT_ALREADY_EXISTS', 409),
  reportAlreadyExists: () => fail('REPORT_ALREADY_EXISTS', 409),
  reportNotEditable: () => fail('REPORT_NOT_EDITABLE', 409),
  invalidState: () => fail('INVALID_STATE', 409),
  scoringPending: () => fail('SCORING_PENDING', 409),
  tooManyRequests: (seconds = 30) =>
    fail('RATE_LIMITED', 429, `请求过于频繁，请 ${seconds} 秒后重试`, { 'Retry-After': String(seconds) }),
  internalError: () => fail('INTERNAL_ERROR', 500),
  rulesNotConfigured: () => fail('RULES_NOT_CONFIGURED', 503),
  unavailable: () => fail('SERVICE_UNAVAILABLE', 503),
}

/** 分页载荷。total 是过滤后的条数，不是页数。 */
export function page<T>(items: T[], p: number, size: number, total?: number) {
  return { items, page: p, size, total: total ?? items.length }
}

// ---------------------------------------------------------------------------
// 虚构的账号与身份
// ---------------------------------------------------------------------------

/** 七个预置方向，与后端 V2__seed_directions.sql 完全一致。 */
export const DIRECTIONS: Direction[] = [
  { id: '1', code: 'AI_FULLSTACK', name: 'AI 全栈', enabled: true },
  { id: '2', code: 'HARMONY_OS', name: '鸿蒙', enabled: true },
  { id: '3', code: 'CYBER_SECURITY', name: '网络安全', enabled: true },
  { id: '4', code: 'CPP', name: 'C++', enabled: true },
  { id: '5', code: 'BIG_DATA', name: '大数据', enabled: true },
  { id: '6', code: 'ARTIFICIAL_INTELLIGENCE', name: '人工智能', enabled: true },
  { id: '7', code: 'PROJECT_DESIGN', name: '项目创意设计', enabled: true },
]

/** Mock 账号。全部为虚构数据，不含任何真实学生信息。 */
export interface MockAccount {
  account: string
  password: string
  user: Identity
  /** 首次登录或密码被重置后为 true，登录后进入受限改密流程。 */
  mustChangePassword: boolean
  accountStatus: Me['accountStatus']
  membershipStatus: Me['membershipStatus']
  profile: Me['profile']
}

const studentIdentity: Identity = {
  userId: '101',
  memberId: '201',
  account: 'MOCK20260001',
  displayName: '示例同学',
  role: 'STUDENT',
  directionId: '1',
  managedDirectionIds: [],
}

const leaderIdentity: Identity = {
  userId: '102',
  memberId: '202',
  account: 'MOCK20260002',
  displayName: '示例负责人',
  role: 'DIRECTION_LEADER',
  directionId: '1',
  managedDirectionIds: ['1', '2'],
}

const adminIdentity: Identity = {
  userId: '1',
  // 契约 §5：学院管理员账号没有成员身份。
  memberId: null,
  account: 'MOCK-ADMIN',
  displayName: '示例管理员',
  role: 'COLLEGE_ADMIN',
  directionId: null,
  managedDirectionIds: [],
}

const emptyProfile: Me['profile'] = {
  studentNo: 'MOCK20260001',
  major: null,
  className: null,
  grade: null,
  contact: null,
}

export const ACCOUNTS: MockAccount[] = [
  {
    account: 'MOCK20260001',
    password: 'Example-Only9!',
    user: studentIdentity,
    mustChangePassword: false,
    accountStatus: 'ENABLED',
    membershipStatus: 'ACTIVE',
    profile: emptyProfile,
  },
  {
    // 模拟首次登录：拿到受限会话，必须先去改密。
    account: 'MOCK20260003',
    password: 'Example-Only9!',
    user: {
      ...studentIdentity,
      userId: '103',
      memberId: '203',
      account: 'MOCK20260003',
      displayName: '首次登录同学',
    },
    mustChangePassword: true,
    accountStatus: 'ENABLED',
    membershipStatus: 'ACTIVE',
    profile: { ...emptyProfile, studentNo: 'MOCK20260003' },
  },
  {
    account: 'MOCK20260002',
    password: 'Example-Only9!',
    user: leaderIdentity,
    mustChangePassword: false,
    accountStatus: 'ENABLED',
    membershipStatus: 'ACTIVE',
    profile: { ...emptyProfile, studentNo: 'MOCK20260002' },
  },
  {
    // 模拟被移出：登录成功后访问业务接口会被后端拒绝。
    account: 'MOCK20260004',
    password: 'Example-Only9!',
    user: {
      ...studentIdentity,
      userId: '104',
      memberId: '204',
      account: 'MOCK20260004',
      displayName: '已移出同学',
    },
    mustChangePassword: false,
    accountStatus: 'ENABLED',
    membershipStatus: 'REMOVED',
    profile: { ...emptyProfile, studentNo: 'MOCK20260004' },
  },
  {
    // 模拟账号被停用：登录即被拒（契约要求与凭据错误统一返回 401）。
    account: 'MOCK20260005',
    password: 'Example-Only9!',
    user: {
      ...studentIdentity,
      userId: '105',
      memberId: '205',
      account: 'MOCK20260005',
      displayName: '停用同学',
    },
    mustChangePassword: false,
    accountStatus: 'DISABLED',
    membershipStatus: 'ACTIVE',
    profile: { ...emptyProfile, studentNo: 'MOCK20260005' },
  },
  {
    account: 'MOCK-ADMIN',
    password: 'Admin-Example9!',
    user: adminIdentity,
    mustChangePassword: false,
    accountStatus: 'ENABLED',
    membershipStatus: null,
    profile: { studentNo: null, major: null, className: null, grade: null, contact: null },
  },
]

// ---------------------------------------------------------------------------
// 内存会话状态
// ---------------------------------------------------------------------------

/**
 * 每个 access token 对应一条会话。
 *
 * access token 用受限/正常两种前缀，便于观察契约差异：
 *   - mock-limited-* 只能访问 me / password / logout；
 *   - mock-access-* 为正常会话，可访问全部业务接口。
 */
export interface MockSessionRecord {
  userId: string
  account: string
  refreshToken: string | null
  restricted: boolean
  /** 是否已被撤销（改密、退出、被移出时置位）。 */
  revoked: boolean
}

const sessions = new Map<string, MockSessionRecord>()

let tokenCounter = 0

function nextToken(kind: 'access' | 'limited' | 'refresh'): string {
  tokenCounter += 1
  return `mock-${kind}-token-${tokenCounter}-not-a-credential`
}

/** 建立一次新会话，返回给客户端的 Session 载荷。 */
export function createSession(
  account: MockAccount,
  restricted: boolean,
): Session {
  const accessToken = nextToken(restricted ? 'limited' : 'access')
  const refreshToken = restricted ? null : nextToken('refresh')

  sessions.set(accessToken, {
    userId: account.user.userId,
    account: account.account,
    refreshToken,
    restricted,
    revoked: false,
  })

  return {
    accessToken,
    refreshToken,
    tokenType: 'Bearer',
    // 契约建议值：受限 token 300 秒、普通 access token 900 秒、refresh 604800 秒。
    expiresIn: restricted ? 300 : 900,
    refreshExpiresIn: restricted ? null : 604800,
    mustChangePassword: restricted,
    user: account.user,
  }
}

export function findSessionByAccess(accessToken: string | null): MockSessionRecord | null {
  if (!accessToken) return null
  const record = sessions.get(accessToken)
  if (!record || record.revoked) return null
  return record
}

export function findSessionByRefresh(refreshToken: string | null): MockSessionRecord | null {
  if (!refreshToken) return null
  for (const record of sessions.values()) {
    if (record.refreshToken === refreshToken && !record.revoked) return record
  }
  return null
}

export function revokeSessionByRefresh(refreshToken: string): void {
  const record = findSessionByRefresh(refreshToken)
  if (record) record.revoked = true
}

/** 撤销某账号的全部会话（改密、重置密码、学号更正、移出时调用）。 */
export function revokeSessionsOfUser(userId: string): void {
  for (const record of sessions.values()) {
    if (record.userId === userId) record.revoked = true
  }
}

/** 轮换 refresh token，旧 token 立即失效。 */
export function rotateSession(oldRefreshToken: string, account: MockAccount): Session {
  revokeSessionByRefresh(oldRefreshToken)
  return createSession(account, false)
}

export function findAccountByUserId(userId: string): MockAccount | null {
  return ACCOUNTS.find((a) => a.user.userId === userId) ?? null
}

export function findAccountByAccount(account: string): MockAccount | null {
  return ACCOUNTS.find((a) => a.account === account) ?? null
}

/**
 * 账号的初始值快照。
 *
 * 测试之间必须完全隔离：改密用例会改写 ACCOUNTS 里的 password 与
 * mustChangePassword，如果不还原，后续用例会拿到被污染的账号。
 * 因此这里在模块加载时冻结一份初始值，供 resetMockState 恢复。
 */
const INITIAL_ACCOUNT_STATE = ACCOUNTS.map((a) => ({
  account: a.account,
  password: a.password,
  mustChangePassword: a.mustChangePassword,
  accountStatus: a.accountStatus,
  membershipStatus: a.membershipStatus,
}))

/** 重置 Mock 状态，供测试使用。 */
export function resetMockState(): void {
  sessions.clear()
  tokenCounter = 0

  for (const snapshot of INITIAL_ACCOUNT_STATE) {
    const account = ACCOUNTS.find((a) => a.account === snapshot.account)
    if (!account) continue
    account.password = snapshot.password
    account.mustChangePassword = snapshot.mustChangePassword
    account.accountStatus = snapshot.accountStatus
    account.membershipStatus = snapshot.membershipStatus
  }

  // 业务数据（成员/报告/积分/审计）由 data.ts 注册的钩子还原，
  // 避免 state.ts 反向依赖造成循环导入。
  for (const hook of resetHooks) hook()
}

/** 供 data.ts 注册业务数据的重置逻辑。 */
export function registerResetHook(hook: () => void): void {
  resetHooks.push(hook)
}

const resetHooks: Array<() => void> = []

/** 从 Authorization 头解析 Bearer token。 */
export function bearerFrom(request: Request): string | null {
  const header = request.headers.get('Authorization')
  if (!header?.startsWith('Bearer ')) return null
  return header.slice('Bearer '.length)
}

/**
 * 受限改密会话允许访问的接口（契约 §3.1）。
 * 白名单之外的受保护接口一律 403 PASSWORD_CHANGE_REQUIRED。
 */
const RESTRICTED_ALLOWED_PATHS: readonly string[] = [
  `${API_PREFIX}/auth/me`,
  `${API_PREFIX}/auth/password`,
  `${API_PREFIX}/auth/logout`,
]

/**
 * 所有受保护接口的通用会话守卫。
 *
 * 返回 null 表示通过；否则返回应直接响应的失败。
 * 集中在这里而不是各 handler 各写一遍，是因为受限限制必须
 * 在**每一个**受保护接口上一致生效 —— 漏掉一个就等于没限制。
 */
export function guardSession(request: Request): Response | null {
  const session = findSessionByAccess(bearerFrom(request))
  if (!session) return errors.unauthorized()

  // 受限改密会话：只放行白名单。
  const pathname = new URL(request.url).pathname
  if (session.restricted && !RESTRICTED_ALLOWED_PATHS.includes(pathname)) {
    return errors.passwordChangeRequired()
  }

  const account = findAccountByUserId(session.userId)
  if (!account) return errors.unauthorized()

  // 账号停用或成员已移出：拒绝业务访问。
  if (account.accountStatus === 'DISABLED') return errors.accountDisabled()
  if (account.membershipStatus === 'REMOVED') return errors.memberRemoved()

  return null
}
