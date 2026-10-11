/**
 * access / refresh token 的内存存储。
 *
 * 契约约束（docs/api/README.md §3.2）：
 *   「本版不是 Cookie 认证：接口不设置认证 Cookie。前端 token 默认只放内存；
 *     刷新页面可要求重新登录，不为『记住登录』擅自使用持久 localStorage。」
 *
 * 因此本模块刻意使用模块级变量，而不是 localStorage / sessionStorage / IndexedDB，
 * 也不接入 pinia 持久化插件。刷新页面后 token 丢失、要求重新登录，
 * 这是契约规定的预期行为，不是缺陷。
 *
 * token 一律按不透明字符串处理：不解 JWT、不据其内容推断权限或过期时间；
 * 过期以响应里的 expiresIn 为准。
 */

let accessToken: string | null = null
let refreshToken: string | null = null

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
