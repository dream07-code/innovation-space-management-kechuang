/**
 * Idempotency-Key 生成与复用。
 *
 * 契约约束（docs/api/README.md §4.2）：
 *   - 所有非认证的 POST 业务写操作都要求 Idempotency-Key 请求头，值为客户端生成的 UUID；
 *   - 同一操作者 + 方法 + 路径 + key + 相同 JSON 语义，在 24 小时内返回首次成功结果；
 *   - 同 key 但请求体不同 → 409 IDEMPOTENCY_CONFLICT。
 *
 * 关键设计：一个「逻辑操作」对应一个 key，网络重试必须复用同一个 key，
 * 否则重复提交会绕过服务端的去重而重复执行（例如重复加分、重复移出）。
 * 因此 key 由调用方在操作发起时生成一次，重试时把同一个 key 传回去，
 * 而不是在每次请求发出时临时生成。
 */

/**
 * 生成一个 UUID v4。
 *
 * 优先使用 crypto.randomUUID（需要安全上下文：https 或 localhost）；
 * 退化到 crypto.getRandomValues 手工拼装，保证在非安全上下文下仍可工作。
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    // 按 RFC 4122 设置版本位（4）与变体位（10xx）。
    bytes[6] = (bytes[6]! & 0x0f) | 0x40
    bytes[8] = (bytes[8]! & 0x3f) | 0x80
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    return [
      hex.slice(0, 8),
      hex.slice(8, 12),
      hex.slice(12, 16),
      hex.slice(16, 20),
      hex.slice(20, 32),
    ].join('-')
  }

  throw new Error('当前环境不支持生成 UUID，无法保证写操作的幂等性')
}

/**
 * 一次逻辑写操作的幂等键。
 *
 * 用法：
 *   const op = new WriteOperation()
 *   await submit(op.key)          // 首次
 *   await submit(op.key)          // 重试：复用同一个 key，服务端回放首次结果
 *
 * 新的一次用户操作应新建实例，拿到新的 key。
 */
export class WriteOperation {
  readonly key: string
  /** 记录已发起的尝试次数，便于 UI 区分首次提交与重试。 */
  attempts = 0

  constructor(key: string = newIdempotencyKey()) {
    this.key = key
  }

  /** 取用一次尝试的 key（每次尝试都返回同一个值）。 */
  nextKey(): string {
    this.attempts += 1
    return this.key
  }
}

/**
 * 幂等键的生命周期管理，用于「表单重试」场景。
 *
 * 同一份表单内容被重复提交时应复用 key；但一旦用户改动了内容，
 * 就必须换新 key —— 否则会命中 409 IDEMPOTENCY_CONFLICT。
 * 这里用内容指纹来判定是否需要换 key。
 */
export class FormSubmissionKey {
  private key: string | null = null
  private fingerprint: string | null = null

  /**
   * 取得本次提交应使用的 key。
   *
   * @param fingerprint 当前表单内容的指纹（例如 JSON.stringify 的稳定序列化结果）。
   *                    内容变化时自动换新 key。
   */
  keyFor(fingerprint: string): string {
    if (this.key === null || this.fingerprint !== fingerprint) {
      this.key = newIdempotencyKey()
      this.fingerprint = fingerprint
    }
    return this.key
  }

  /** 提交成功后重置，使下一次提交使用新 key。 */
  reset(): void {
    this.key = null
    this.fingerprint = null
  }
}
