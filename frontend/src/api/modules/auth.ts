/**
 * 认证接口。
 *
 * 契约：docs/api/README.md §2、§3；openapi.yaml 的认证 tag。
 *
 * 登录、刷新、退出、改密在契约中都没有声明 Idempotency-Key 参数，
 * 因此一律使用 postWithoutIdempotency，不擅自补未声明的请求头。
 */
import { API_PREFIX, authClient, get, postWithoutIdempotency, put } from '../client'
import { toApiError } from '../errors'
import { setTokens } from '../tokenStore'
import type { ChangePasswordRequest, EmptyBody, Envelope, LoginRequest, Me, Session } from '../types'

/**
 * 账号密码登录。
 *
 * 契约要点：
 *   - 不使用 Cookie；响应带 Cache-Control: no-store，客户端不应缓存；
 *   - 首次/重置后登录同样返回 HTTP 200、code=OK，但 refreshToken=null、
 *     expiresIn=300、mustChangePassword=true —— 受限会话只能访问
 *     /auth/me、PUT /auth/password、/auth/logout，其余接口返回 403
 *     PASSWORD_CHANGE_REQUIRED；
 *   - 错误账号、错误密码与被禁用账号统一返回 401 INVALID_CREDENTIALS，
 *     以避免通过登录响应枚举学号。前端提示必须保持通用。
 */
export async function login(payload: LoginRequest): Promise<Session> {
  try {
    const response = await authClient.post<Envelope<Session>>(`${API_PREFIX}/auth/login`, payload)
    const session = response.data.data

    if (!session?.accessToken) {
      throw new Error('登录响应缺少 accessToken')
    }

    // 写入内存；refreshToken 在受限会话下为 null，这是预期行为。
    setTokens({
      accessToken: session.accessToken,
      refreshToken: session.refreshToken,
      expiresIn: session.expiresIn,
    })

    return session
  } catch (error) {
    throw toApiError(error)
  }
}

/**
 * 退出当前会话，撤销该 session 及其刷新能力。
 *
 * 契约：重复用已失效 token 退出可返回 401，前端仍应清空本地状态。
 * 因此调用方不应因本接口失败而跳过本地清理。
 */
export async function logout(): Promise<void> {
  await postWithoutIdempotency<EmptyBody>(`${API_PREFIX}/auth/logout`)
}

/**
 * 读取当前登录身份与本人资料。
 *
 * 受限改密会话也可以调用本接口 —— 这是它被允许的三个接口之一。
 */
export async function fetchMe(): Promise<Me> {
  return get<Me>(`${API_PREFIX}/auth/me`)
}

/**
 * 修改本人密码。
 *
 * 契约要点（§3.2）：
 *   - 成功返回 HTTP 200、data=null，全部会话失效，且**不再返回 token**，
 *     因此调用方必须重新登录，不能继续使用当前会话；
 *   - 新密码 12–64 字符，不接受全空白、当前密码或共享初始口令；
 *   - 用户输入不静默 trim —— 因此这里原样透传，不做任何规整。
 */
export async function changePassword(payload: ChangePasswordRequest): Promise<void> {
  // 契约中是 PUT（不是 POST），路径 /auth/password。
  await put<EmptyBody>(`${API_PREFIX}/auth/password`, payload)
}
