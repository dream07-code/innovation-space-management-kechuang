/**
 * Mock：幂等键模拟。
 *
 * 契约 §4.2：同一操作者 + 方法 + 路径 + key + 相同 JSON 语义，24 小时内
 * 返回首次成功结果；同 key 不同 body → 409 IDEMPOTENCY_CONFLICT。
 *
 * 这里做简化：不实现 24 小时过期（mock 生命周期就是一次页面会话），
 * 只校验「同 key 必须同 body」，成功结果（2xx）会被缓存并重放。
 */
import { errors } from '../state'

interface IdempotentRecord {
  bodyText: string
  status: number
  responseBody: string
}

const store = new Map<string, IdempotentRecord>()

function compositeKey(request: Request): string {
  const key = request.headers.get('Idempotency-Key') ?? ''
  const { pathname } = new URL(request.url)
  return `${request.method} ${pathname} ${key}`
}

/** 清空记录，供测试重置。 */
export function resetIdempotencyStore(): void {
  store.clear()
}

/**
 * 包裹一个写操作 handler：
 *   - 缺少 Idempotency-Key → 400（契约声明 required: true）；
 *   - 同 key 同 body → 直接重放首次成功结果，不重复执行；
 *   - 同 key 不同 body → 409 IDEMPOTENCY_CONFLICT；
 *   - 失败结果（非 2xx）不缓存，允许客户端修正后重试。
 *
 * 注意 body 只能读一次，因此由这里读取文本后交给 fn 解析。
 */
export async function withIdempotency(
  request: Request,
  fn: (bodyText: string) => Promise<Response> | Response,
): Promise<Response> {
  const key = request.headers.get('Idempotency-Key')
  if (!key) return errors.invalidRequest()

  const bodyText = await request.text()
  const composite = compositeKey(request)
  const existing = store.get(composite)
  if (existing) {
    if (existing.bodyText !== bodyText) return errors.idempotencyConflict()
    return new Response(existing.responseBody, {
      status: existing.status,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  const response = await fn(bodyText)
  if (response.status >= 200 && response.status < 300) {
    store.set(composite, {
      bodyText,
      status: response.status,
      responseBody: await response.clone().text(),
    })
  }
  return response
}
