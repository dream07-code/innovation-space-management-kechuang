/**
 * access / refresh token 的内存存储。
 *
 * 契约约束（docs/api/README.md §3.2，2026-10 修订）：
 *   「本版不是 Cookie 认证：接口不设置认证 Cookie。access token 只放内存；
 *     refreshToken 允许写 sessionStorage（tab 会话内刷新页面静默恢复会话，
 *     关闭标签页即清除）；仍然禁止持久 localStorage。」
 *
 * access token 与过期时刻保持模块级内存变量；refreshToken 额外镜像到
 * sessionStorage（key 见 STORAGE_KEY），restore() 在应用启动时读回，
 * 配合 /auth/refresh + /auth/me 完成静默恢复。
 *
 * token 一律按不透明字符串处理：不解 JWT、不据其内容推断权限或过期时间；
 * 过期以响应里的 expiresIn 为准。
 */

let accessToken: string | null = null
let refreshToken: string | null = null

const STORAGE_KEY = 'is.refresh'

function persistRefreshToken(value: string | null): void {
  try {
    if (value === null) sessionStorage.removeItem(STORAGE_KEY)
    else sessionStorage.setItem(STORAGE_KEY, value)
  } catch {
    // 隐私模式等 sessionStorage 不可用时降级为纯内存：仅失去刷新恢复能力。
  }
}

/** access token 相对过期时刻（毫秒时间戳），由登录/刷新响应的 expiresIn 推算。 */
let accessTokenExpiresAt: number | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return refreshToken
}

/** 受限改密会话不返回 refresh token，因此这里可能为 null。 */
export function hasRefreshToken(): boolean {
  return refreshToken !== null
}

/**
 * 写入会话凭据。
 *
 * @param expiresIn access token 有效期（秒），来自响应，不可写死。
 * @param refreshExpiresIn refresh token 有效期（秒）；受限会话为 null。
 */
export function setTokens(tokens: {
  accessToken: string
  refreshToken: string | null
  expiresIn: number
}): void {
  accessToken = tokens.accessToken
  refreshToken = tokens.refreshToken
  accessTokenExpiresAt = Date.now() + tokens.expiresIn * 1000
  persistRefreshToken(refreshToken)
}

/** 仅轮换 token，保留既有过期策略（用于 /auth/refresh 的轮换结果）。 */
export function rotateTokens(tokens: {
  accessToken: string
  refreshToken: string | null
  expiresIn: number
}): void {
  setTokens(tokens)
}

/** 清空全部凭据。退出登录、改密成功、刷新失败时调用。 */
export function clearTokens(): void {
  accessToken = null
  refreshToken = null
  accessTokenExpiresAt = null
  persistRefreshToken(null)
}

/** 应用启动（刷新页面）时从 sessionStorage 读回 refreshToken。 */
export function restore(): void {
  if (refreshToken !== null) return
  try {
    const saved = sessionStorage.getItem(STORAGE_KEY)
    if (saved !== null) refreshToken = saved
  } catch {
    // sessionStorage 不可用：维持匿名态。
  }
}

/**
 * access token 是否已到期（或即将到期）。
 *
 * 注意：这只是为避免明显无谓请求的本地预判，不是权威判断 ——
 * 服务端才是唯一裁判，收到 TOKEN_EXPIRED 时仍走刷新流程。
 *
 * @param skewSeconds 提前量，避免请求在途中过期。
 */
export function isAccessTokenExpiring(skewSeconds = 30): boolean {
  if (accessToken === null || accessTokenExpiresAt === null) return true
  return Date.now() >= accessTokenExpiresAt - skewSeconds * 1000
}
