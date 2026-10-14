/**
 * Mock：审计列表。仅学院管理员可读，只返回脱敏摘要。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import { AUDITS, paginate, parsePageParams } from '../data'
import { bearerFrom, errors, findAccountByUserId, findSessionByAccess, guardSession, ok } from '../state'

export const auditHandlers = [
  http.get(`${API_PREFIX}/audits`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied

    const session = findSessionByAccess(bearerFrom(request))
    const account = session ? findAccountByUserId(session.userId) : null
    if (account?.user.role !== 'COLLEGE_ADMIN') return errors.forbidden()

    const url = new URL(request.url)
    const paging = parsePageParams(url)
    if (!paging) return errors.invalidRequest()

    const resourceId = url.searchParams.get('resourceId')
    const action = url.searchParams.get('action')

    let items = AUDITS
    if (resourceId) items = items.filter((a) => a.resourceId === resourceId)
    if (action) items = items.filter((a) => a.action.includes(action))
    // 契约：按 occurredAt DESC, id 数值 DESC。fixture 本身即按时间倒序维护。
    items = [...items].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt) || (BigInt(b.id) > BigInt(a.id) ? 1 : -1))
    return ok(paginate(items, paging.page, paging.size))
  }),
]
