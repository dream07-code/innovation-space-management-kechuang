/**
 * 通用 AI 助教接口：对话、会话管理与学习画像。
 *
 * 与「按提交追问」是两条独立入口：这里不绑定提交，上下文只有会话关联的题目（可空）。
 * 会话与消息都由后端保存，历史不再由前端上传；前端只需要记住当前 conversationId。
 * 新建/删除会话是写操作，必须带调用方生成的 Idempotency-Key。
 */
import { API_PREFIX, get, patch, post, postWithoutIdempotency } from '../client'
import type {
  CreateTutorConversationRequest,
  TutorChatReply,
  TutorChatRequest,
  TutorConversation,
  TutorConversationDeleted,
  TutorConversationList,
  TutorConversationMessages,
  TutorLearningProfile,
} from '../types'

const BASE = `${API_PREFIX}/tutor`

/**
 * 就当前会话（或纯知识点）向 AI 助教提问。
 *
 * 不带 conversationId 时后端自动新建会话，并在响应里回传 id。
 * AI 未配置或调用失败时仍返回 200，`available=false` 并用 `error` 说明原因；
 * 前端只展示原因，不渲染占位回答。
 */
export async function askTutorChat(payload: TutorChatRequest): Promise<TutorChatReply> {
  return postWithoutIdempotency<TutorChatReply>(`${BASE}/chat`, payload)
}

/** 本人会话列表，最近更新在前。 */
export async function listTutorConversations(
  page = 1,
  size = 20,
): Promise<TutorConversationList> {
  return get<TutorConversationList>(`${BASE}/conversations`, { params: { page, size } })
}

/** 新建会话。title 可空（后端会用关联题目标题兜底）。 */
export async function createTutorConversation(
  payload: CreateTutorConversationRequest,
  idempotencyKey: string,
): Promise<TutorConversation> {
  return post<TutorConversation>(`${BASE}/conversations`, payload, { idempotencyKey })
}

/** 一个会话的元信息与消息（按时间正序）。 */
export async function getTutorConversationMessages(
  conversationId: string,
): Promise<TutorConversationMessages> {
  return get<TutorConversationMessages>(`${BASE}/conversations/${conversationId}/messages`)
}

export async function renameTutorConversation(
  conversationId: string,
  title: string,
): Promise<TutorConversation> {
  return patch<TutorConversation>(`${BASE}/conversations/${conversationId}`, { title })
}

/** 删除会话；消息由后端级联删除。 */
export async function deleteTutorConversation(
  conversationId: string,
  idempotencyKey: string,
): Promise<TutorConversationDeleted> {
  return post<TutorConversationDeleted>(
    `${BASE}/conversations/${conversationId}/delete`,
    {},
    { idempotencyKey },
  )
}

/** 学习画像：后端读时聚合提交/判题/成绩并刷新快照；没数据时 hasData=false。 */
export async function getTutorLearningProfile(): Promise<TutorLearningProfile> {
  return get<TutorLearningProfile>(`${BASE}/profile`)
}
