/**
 * 通用 AI 助教接口。
 *
 * 与「按提交追问」是两条独立入口：这里不绑定提交，上下文只有学生选中的题目（可空）。
 * 对话历史由前端保存（localStorage）并逐轮带回，服务端不保存会话，接口无副作用。
 */
import { API_PREFIX, postWithoutIdempotency } from '../client'
import type { TutorChatReply, TutorChatRequest } from '../types'

const BASE = `${API_PREFIX}/tutor`

/**
 * 就当前选中的题目（或纯知识点）向 AI 助教提问。
 *
 * AI 未配置或调用失败时仍返回 200，`available=false` 并用 `error` 说明原因；
 * 前端只展示原因，不渲染占位回答。
 */
export async function askTutorChat(payload: TutorChatRequest): Promise<TutorChatReply> {
  return postWithoutIdempotency<TutorChatReply>(`${BASE}/chat`, payload)
}
