/**
 * 站内公告接口。
 *
 * 契约：两级作用域——学院级（全体可见）与方向级（该方向成员可见）。
 * 读取对所有登录用户开放且后端按身份过滤；创建/撤回仅管理员与负责人，
 * POST 写操作必须带调用方生成的 Idempotency-Key。
 */
import { API_PREFIX, get, post } from '../client'
import type { AnnouncementItem, CreateAnnouncementRequest } from '../types'

const BASE = `${API_PREFIX}/announcements`

/** 当前用户可见且未撤回的公告，created_at 倒序，最多 5 条。 */
export async function listAnnouncements(): Promise<{ items: AnnouncementItem[]; total: number }> {
  return get<{ items: AnnouncementItem[]; total: number }>(BASE)
}

export async function createAnnouncement(
  payload: CreateAnnouncementRequest,
  idempotencyKey: string,
): Promise<AnnouncementItem> {
  return post<AnnouncementItem>(BASE, payload, { idempotencyKey })
}

export async function withdrawAnnouncement(
  announcementId: string,
  idempotencyKey: string,
): Promise<AnnouncementItem> {
  return post<AnnouncementItem>(`${BASE}/${announcementId}/withdraw`, {}, { idempotencyKey })
}
