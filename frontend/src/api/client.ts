/**
 * HTTP 客户端。
 *
 * 负责契约里所有跨接口的横切约定：
 *   - 信封解包：成功响应 {code:"OK", message, data} → 直接返回 data；
 *   - 认证头注入：Authorization: Bearer <accessToken>；
 *   - 幂等头注入：写操作带 Idempotency-Key；
 *   - 401 的 single-flight 刷新与请求重放；
 *   - 失败响应归一化为 ApiError。
 *
 * 契约定（docs/api/README.md §1.3、§3.2、§4）：
 *   - 「业务路径包含 /api/v1；不要重复添加前缀」，契约 servers 为 "/"，
 *     因此 baseURL 只填 origin（开发环境留空走同源代理），路径写全 /api/v1/...；
 *   - 刷新必须 single-flight，「禁止多个并发刷新」；
 *   - 「refresh 失败不循环」，刷新失败即结束会话。
 */
import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'

import { ApiError, toApiError } from './errors'
import { API_PREFIX } from './constants'
import { clearTokens, getAccessToken, getRefreshToken, rotateTokens } from './tokenStore'
import type { Envelope, Session } from './types'

/** 需要在请求头带 Idempotency-Key 的扩展配置。 */
export interface RequestOptions extends AxiosRequestConfig {
  /** 写操作幂等键。由调用方生成并在重试时复用。 */
  idempotencyKey?: string
  /** 内部标记：已重放过的请求不再二次刷新，避免无限循环。 */
  _retried?: boolean
  /** 内部标记：该请求本身是刷新/登录等认证流程，不参与刷新重放。 */
  _skipAuthRefresh?: boolean
}

/** 会话失效回调，由 auth store 注册，用于在刷新失败时清理前端状态并跳转登录。 */
type SessionExpiredHandler = (error: ApiError) => void
let onSessionExpired: SessionExpiredHandler | null = null

export function registerSessionExpiredHandler(handler: SessionExpiredHandler): void {
  onSessionExpired = handler
}

/** 受限改密会话回调：收到 PASSWORD_CHANGE_REQUIRED 时切到改密页。 */
type PasswordChangeRequiredHandler = () => void
let onPasswordChangeRequired: PasswordChangeRequiredHandler | null = null

export function registerPasswordChangeRequiredHandler(handler: PasswordChangeRequiredHandler): void {
  onPasswordChangeRequired = handler
}

// ---------------------------------------------------------------------------
// 实例
// ---------------------------------------------------------------------------

/**
 * 用于登录/刷新等认证流程的裸实例。
 *
 * 刻意不经过下面的拦截器：否则刷新请求自身收到 401 时又去触发刷新，形成循环。
 */
const authClient: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

/** 主实例。 */
export const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

// ---------------------------------------------------------------------------
// 请求拦截：注入认证头与幂等头
// ---------------------------------------------------------------------------

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const options = config as InternalAxiosRequestConfig & RequestOptions

  // 始终注入当前 access token（含受限改密 token）。
  // 受限 token 只被后端允许访问 me/password/logout，越权由后端拒绝，
  // 前端不需要也不应该在这里做权限判断。
  const token = getAccessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }

  if (options.idempotencyKey) {
    config.headers.set('Idempotency-Key', options.idempotencyKey)
  }

  return config
})

// ---------------------------------------------------------------------------
// single-flight 刷新
// ---------------------------------------------------------------------------

/**
 * 进行中的刷新。并发请求共享同一个 promise，因此只会发出一次 /auth/refresh。
 * 契约要求「前端对刷新使用 single-flight，禁止多个并发刷新」。
 */
let refreshInFlight: Promise<string> | null = null

/**
 * 执行刷新并返回新的 access token。
 *
 * 旧 refresh token 在服务端立即失效并被轮换；服务端还会检测重复使用
 * 并撤销该 session，所以失败后不得重试同一个 refresh token。
 */
function performRefresh(): Promise<string> {
  const currentRefreshToken = getRefreshToken()
  if (!currentRefreshToken) {
    return Promise.reject(
      new ApiError({
        code: 'REFRESH_TOKEN_INVALID',
        httpStatus: 401,
        message: '没有可用的刷新令牌',
      }),
    )
  }

  return authClient
    .post<Envelope<Session>>(`${API_PREFIX}/auth/refresh`, { refreshToken: currentRefreshToken })
    .then((response) => {
      const session = response.data.data
      if (!session?.accessToken) {
        throw new ApiError({
          code: 'UNEXPECTED_RESPONSE',
          httpStatus: 200,
          message: '刷新响应缺少 accessToken',
          body: response.data,
        })
      }
      // 刷新会重新读取账号状态与权限，不复制旧 token 中的过期权限。
      rotateTokens({
        accessToken: session.accessToken,
        refreshToken: session.refreshToken,
        expiresIn: session.expiresIn,
      })
      return session.accessToken
    })
    .catch((error: unknown) => {
      // 刷新失败一律结束会话，绝不循环重试。
      const apiError = toApiError(error)
      clearTokens()
      onSessionExpired?.(apiError)
      throw apiError
    })
}

/** 取得刷新结果；并发调用共享同一个进行中的刷新。 */
/** 供启动静默恢复会话使用；与 401 拦截器共用 single-flight 队列。 */
export function refreshAccessToken(): Promise<string> {
  if (!refreshInFlight) {
    refreshInFlight = performRefresh().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

// ---------------------------------------------------------------------------
// 响应拦截：解信封 + 错误归一化 + 401 重放
// ---------------------------------------------------------------------------

/** 判断响应体是否为成功的业务信封。 */
function isSuccessEnvelope(body: unknown): body is Envelope<unknown> {
  return (
    typeof body === 'object' &&
    body !== null &&
    (body as { code?: unknown }).code === 'OK' &&
    'data' in body
  )
}

http.interceptors.response.use(
  (response: AxiosResponse) => {
    const body = response.data

    // /actuator/health 是唯一不套业务信封的接口，原样返回。
    if (response.config.url?.startsWith('/actuator')) {
      return response
    }

    if (isSuccessEnvelope(body)) {
      // 直接把 data 交给调用方，业务代码不再接触信封。
      response.data = body.data
    }

    return response
  },
  async (error: unknown) => {
    const apiError = toApiError(error)

    // 受限改密会话访问业务接口：后端强制返回 403 PASSWORD_CHANGE_REQUIRED。
    // 前端据此切换页面，但不把它当作可重试的错误。
    if (apiError.isPasswordChangeRequired) {
      onPasswordChangeRequired?.()
      return Promise.reject(apiError)
    }

    // 账号被停用 / 已移出：结束会话，不重试。
    if (apiError.isAccountDisabled || apiError.isMemberRemoved) {
      clearTokens()
      onSessionExpired?.(apiError)
      return Promise.reject(apiError)
    }

    const config = (error as AxiosError)?.config as
      | (InternalAxiosRequestConfig & RequestOptions)
      | undefined

    // 只在「确属过期」且「尚未重放过」且「非认证流程自身」时才刷新重放。
    // 契约要求刷新失败不循环，因此最多重放一次。
    const shouldRefresh =
      apiError.isTokenExpired &&
      config !== undefined &&
      !config._retried &&
      !config._skipAuthRefresh

    if (!shouldRefresh) {
      return Promise.reject(apiError)
    }

    try {
      const newToken = await refreshAccessToken()
      config._retried = true
      config.headers.set('Authorization', `Bearer ${newToken}`)
      // 重放原请求。幂等键保持不变，因此服务端会回放首次成功结果而不是重复执行。
      return http.request(config)
    } catch (refreshError) {
      return Promise.reject(toApiError(refreshError))
    }
  },
)

// ---------------------------------------------------------------------------
// 便捷方法
// ---------------------------------------------------------------------------

/**
 * 发起请求并返回解包后的 data。
 *
 * 用法上区分读写：写操作必须传 idempotencyKey，读操作不传。
 */
export async function request<T>(config: RequestOptions): Promise<T> {
  const response = await http.request<T>(config)
  return response.data
}

export function get<T>(url: string, config: RequestOptions = {}): Promise<T> {
  return request<T>({ ...config, method: 'GET', url })
}

/**
 * 写操作。必须提供 idempotencyKey —— 契约要求所有非认证业务 POST 都带该头。
 */
export function post<T>(url: string, data: unknown, config: RequestOptions): Promise<T> {
  if (!config.idempotencyKey) {
    throw new Error(`POST ${url} 缺少 idempotencyKey，违反契约的幂等要求`)
  }
  return request<T>({ ...config, method: 'POST', url, data })
}

/**
 * 认证类 POST（登录/刷新/退出/改密）。
 *
 * 这些接口在契约中没有声明 Idempotency-Key 参数，因此不能套用上面的强制校验，
 * 也不应擅自补一个未声明的请求头。
 */
export function postWithoutIdempotency<T>(
  url: string,
  data?: unknown,
  config: RequestOptions = {},
): Promise<T> {
  return request<T>({ ...config, method: 'POST', url, data })
}

/**
 * PATCH 更新。契约用 version 做乐观锁，调用方必须把版本号放进 body。
 */
export function patch<T>(url: string, data: unknown, config: RequestOptions = {}): Promise<T> {
  return request<T>({ ...config, method: 'PATCH', url, data })
}

/**
 * PUT 整体替换。用于修改本人密码与月度规则配置。
 *
 * 与 POST 一样，契约中 PUT /system/rules/monthly-assessment 未声明幂等键，
 * 而 PUT /auth/password 亦未声明，因此这里不强制 idempotencyKey。
 */
export function put<T>(url: string, data: unknown, config: RequestOptions = {}): Promise<T> {
  return request<T>({ ...config, method: 'PUT', url, data })
}

/** 导出裸认证实例，供 auth 模块调用不需要拦截器的登录/刷新接口。 */
export { authClient }

/** 契约里业务接口统一挂在 /api/v1 下。 */
export { API_PREFIX }
