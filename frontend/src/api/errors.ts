/**
 * API 错误归一化。
 *
 * 契约约定（docs/api/README.md §4.3、§4.1）：
 *   - 失败使用正确的 HTTP 状态码，data 为 null，不把失败包装成 HTTP 200；
 *   - ErrorResponse.code 是一个扁平枚举，不按 HTTP 状态码区分；
 *     后端目前只实现了其中一部分，其余为待实现业务码。
 *
 * 因此本模块把任意失败（HTTP 错误响应 / 网络错误 / 非信封响应）统一成 ApiError，
 * 由调用方按 code 决定行为，而不是按 HTTP 状态码硬编码判断。
 */
import type { ErrorCode } from './types'

/** 已知业务错误码。仅用于提供语义化判断，不限制实际收到的 code 取值。 */
const KNOWN_CODES = new Set<string>([
  'INVALID_REQUEST',
  'OLD_PASSWORD_INVALID',
  'PASSWORD_POLICY_VIOLATION',
  'UNAUTHORIZED',
  'TOKEN_EXPIRED',
  'INVALID_CREDENTIALS',
  'REFRESH_TOKEN_INVALID',
  'FORBIDDEN',
  'PASSWORD_CHANGE_REQUIRED',
  'ACCOUNT_DISABLED',
  'MEMBER_REMOVED',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'VERSION_CONFLICT',
  'ACCOUNT_ALREADY_EXISTS',
  'REPORT_ALREADY_EXISTS',
  'REPORT_NOT_EDITABLE',
  'INVALID_STATE',
  'ASSESSMENT_CLOSED',
  'IDEMPOTENCY_CONFLICT',
  'MONTH_CLOSED',
  'SCORING_PENDING',
  'RATE_LIMITED',
  'INTERNAL_ERROR',
  'RULES_NOT_CONFIGURED',
  'SERVICE_UNAVAILABLE',
])

/** 后端未返回可识别 code 时使用的本地码。 */
export type LocalErrorCode = 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'

export type AnyErrorCode = ErrorCode | LocalErrorCode

export class ApiError extends Error {
  /** 业务错误码；后端未返回时为本地码。 */
  readonly code: AnyErrorCode
  /** HTTP 状态码；网络层失败时为 0。 */
  readonly httpStatus: number
  /** 429 时来自 Retry-After 响应头的建议退避秒数。 */
  readonly retryAfterSeconds: number | null
  /** 后端返回的完整错误体，便于排查。 */
  readonly body: unknown

  constructor(init: {
    code: AnyErrorCode
    httpStatus: number
    message: string
    retryAfterSeconds?: number | null
    body?: unknown
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.code = init.code
    this.httpStatus = init.httpStatus
    this.retryAfterSeconds = init.retryAfterSeconds ?? null
    this.body = init.body
  }

  // -- 认证相关 --------------------------------------------------------------

  /**
   * access token 失效。应触发 single-flight 刷新并重放原请求一次；
   * 刷新失败则清空状态回登录页，绝不循环刷新。
   */
  get isTokenExpired(): boolean {
    return this.code === 'TOKEN_EXPIRED' || this.code === 'UNAUTHORIZED'
  }

  /** refresh token 失效。不能重试，必须重新登录。 */
  get isRefreshInvalid(): boolean {
    return this.code === 'REFRESH_TOKEN_INVALID'
  }

  /** 登录凭据错误。契约要求账号不存在/密码错/账号被禁用统一返回此码，避免枚举学号。 */
  get isInvalidCredentials(): boolean {
    return this.code === 'INVALID_CREDENTIALS'
  }

  /**
   * 受限改密会话访问业务接口。后端强制执行；前端路由守卫只是 UX。
   * 收到此码应切到改密页，不重试。
   */
  get isPasswordChangeRequired(): boolean {
    return this.code === 'PASSWORD_CHANGE_REQUIRED'
  }

  /** 账号被停用，阻断使用，不重试。 */
  get isAccountDisabled(): boolean {
    return this.code === 'ACCOUNT_DISABLED'
  }

  /** 成员已被移出，撤销在籍权限。是否允许只读登录属待确认事项。 */
  get isMemberRemoved(): boolean {
    return this.code === 'MEMBER_REMOVED'
  }

  /** 权限不足。不重试。 */
  get isForbidden(): boolean {
    return this.code === 'FORBIDDEN'
  }

  // -- 状态与并发 ------------------------------------------------------------

  /**
   * 乐观锁冲突。必须重新获取详情后由用户重新提交，不能盲目重试覆盖。
   * 同样适用于幂等键冲突（同 key 不同请求体）。
   */
  get isVersionConflict(): boolean {
    return this.code === 'VERSION_CONFLICT'
  }

  /** 幂等键冲突：同 key 但请求体不同。属于客户端 bug，不应自动重试。 */
  get isIdempotencyConflict(): boolean {
    return this.code === 'IDEMPOTENCY_CONFLICT'
  }

  /** 资源已存在（重复学号、重复周期报告等）。 */
  get isAlreadyExists(): boolean {
    return this.code === 'ACCOUNT_ALREADY_EXISTS' || this.code === 'REPORT_ALREADY_EXISTS'
  }

  // -- 限流与不可用 ----------------------------------------------------------

  /** 触发限流，应按 retryAfterSeconds 退避。 */
  get isRateLimited(): boolean {
    return this.code === 'RATE_LIMITED'
  }

  /**
   * 规则未配置齐（如月度评定参数缺失）。这是一等 UI 状态：
   * 应禁用相关执行按钮并提示，不是普通错误提示。
   */
  get isRulesNotConfigured(): boolean {
    return this.code === 'RULES_NOT_CONFIGURED'
  }

  /** 评分未结清。不得按保底分继续执行淘汰或重复累计积分。 */
  get isScoringPending(): boolean {
    return this.code === 'SCORING_PENDING'
  }

  get isServiceUnavailable(): boolean {
    return this.code === 'SERVICE_UNAVAILABLE'
  }

  // -- 分类 ----------------------------------------------------------------

  /** 网络层失败（无响应），可安全重试。 */
  get isNetworkError(): boolean {
    return this.code === 'NETWORK_ERROR'
  }

  /** 该错误是否适合自动重试。 */
  get isRetryable(): boolean {
    return this.code === 'NETWORK_ERROR' || this.code === 'SERVICE_UNAVAILABLE' || this.isRateLimited
  }
}

/** 从 Retry-After 响应头解析秒数；仅接受整数秒，忽略 HTTP-date 形式。 */
function parseRetryAfter(header: unknown): number | null {
  if (typeof header !== 'string') return null
  const seconds = Number.parseInt(header.trim(), 10)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * 把 axios 抛出的任意错误归一化成 ApiError。
 *
 * 后端保证失败响应体形如 {code, message, data: null}；但契约还处于设计阶段、
 * 业务接口尚未实现，反向代理或网关也可能返回非信封内容，因此这里做防御性解析，
 * 无法识别时退化为 UNEXPECTED_RESPONSE，而不是让调用方假设字段存在。
 */
export function toApiError(error: unknown): ApiError {
  // 已经是 ApiError（例如拦截器重复处理）则原样返回。
  if (error instanceof ApiError) return error

  const response = isRecord(error) && isRecord(error.response) ? error.response : null

  if (!response) {
    const message =
      isRecord(error) && typeof error.message === 'string' && error.message
        ? error.message
        : '网络请求失败'
    return new ApiError({ code: 'NETWORK_ERROR', httpStatus: 0, message, body: error })
  }

  const httpStatus = typeof response.status === 'number' ? response.status : 0
  const rawBody = response.data
  const headers = response.headers
  const retryAfterSeconds = isRecord(headers) ? parseRetryAfter(headers['retry-after']) : null

  if (isRecord(rawBody) && typeof rawBody.code === 'string') {
    const code = KNOWN_CODES.has(rawBody.code) ? (rawBody.code as ErrorCode) : 'UNEXPECTED_RESPONSE'
    const message =
      typeof rawBody.message === 'string' && rawBody.message ? rawBody.message : '请求失败'
    return new ApiError({
      code,
      httpStatus,
      message,
      retryAfterSeconds,
      body: rawBody,
    })
  }

  // 非信封响应（网关错误页、代理拦截等）。
  return new ApiError({
    code: 'UNEXPECTED_RESPONSE',
    httpStatus,
    message: `服务返回了非预期响应（HTTP ${httpStatus}）`,
    retryAfterSeconds,
    body: rawBody,
  })
}

/**
 * 面向用户的中文提示。
 *
 * 注意：契约要求生产环境使用通用错误信息，不能通过登录响应枚举学号，
 * 因此登录失败一律提示「账号或密码错误」，不区分账号是否存在。
 */
export function describeError(error: ApiError): string {
  switch (error.code) {
    case 'NETWORK_ERROR':
      return '无法连接服务器，请检查网络后重试'
    case 'UNEXPECTED_RESPONSE':
      return '服务返回异常，请稍后重试'
    case 'INVALID_CREDENTIALS':
      return '账号或密码错误'
    case 'OLD_PASSWORD_INVALID':
      return '当前密码不正确'
    case 'PASSWORD_POLICY_VIOLATION':
      return '新密码不符合要求：12–64 个字符，不能包含全空白，且不能与当前密码相同'
    case 'TOKEN_EXPIRED':
    case 'UNAUTHORIZED':
      return '登录状态已失效，请重新登录'
    case 'REFRESH_TOKEN_INVALID':
      return '登录已过期，请重新登录'
    case 'PASSWORD_CHANGE_REQUIRED':
      return '请先修改初始密码后再继续使用'
    case 'ACCOUNT_DISABLED':
      return '账号已被停用，请联系学院管理员'
    case 'MEMBER_REMOVED':
      return '该成员已不在籍，无法继续使用'
    case 'FORBIDDEN':
      return '没有权限执行该操作'
    case 'VERSION_CONFLICT':
      return '数据已被他人修改，请刷新后重试'
    case 'IDEMPOTENCY_CONFLICT':
      return '重复提交了不同的内容，请刷新后重试'
    case 'ACCOUNT_ALREADY_EXISTS':
      return '该学号已存在'
    case 'REPORT_ALREADY_EXISTS':
      return '该周期的报告已存在'
    case 'REPORT_NOT_EDITABLE':
      return '该报告当前不可修改'
    case 'INVALID_STATE':
      return '当前状态不允许该操作'
    case 'ASSESSMENT_CLOSED':
      return '该考核已关闭，不能再提交代码；如需补交请联系老师'
    case 'MONTH_CLOSED':
      return '该月份已结算，需管理员重新开启'
    case 'SCORING_PENDING':
      return '存在待处理的评分或复核，暂不能执行'
    case 'RULES_NOT_CONFIGURED':
      return '相关规则尚未配置完成，暂不能执行'
    case 'PROBLEM_IN_USE':
      return '该题目已被考核引用或已有提交记录，无法删除；可改为退役'
    case 'MEMBER_IN_USE':
      return '该学生已有日报、积分或考核记录，无法删除；历史记录需保留'
    case 'RATE_LIMITED':
      return error.retryAfterSeconds
        ? `操作过于频繁，请 ${error.retryAfterSeconds} 秒后重试`
        : '操作过于频繁，请稍后重试'
    case 'NOT_FOUND':
      return '请求的资源不存在'
    case 'METHOD_NOT_ALLOWED':
      return '请求方式不正确'
    case 'INVALID_REQUEST': {
      // 后端对字段级校验会回「Invalid request: unknown field: problemId」这类信息，
      // 有细节就带上，只说「内容不合法」时根本不知道改哪儿。
      const detail = (error.message ?? '').replace(/^Invalid request:\s*/, '').trim()
      return detail && detail !== 'Invalid request' ? `请求内容不合法：${detail}` : '请求内容不合法'
    }
    case 'SERVICE_UNAVAILABLE':
      return '服务暂时不可用，请稍后重试'
    case 'INTERNAL_ERROR':
      return '服务器内部错误，请稍后重试'
    default:
      return error.message || '请求失败'
  }
}
