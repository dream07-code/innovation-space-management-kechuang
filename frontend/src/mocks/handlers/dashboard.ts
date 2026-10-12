/**
 * Mock：三个角色的数据看板聚合接口。
 *
 * 只返回 OpenAPI 中定义的安全摘要字段；不返回学号、联系方式、报告正文、
 * 代码、私有测试数据、Token 或 Provider 原始输出。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import type { DashboardRange } from '@/api/types'
import {
  adminDashboard,
  leaderDashboard,
  studentDashboard,
} from '../dashboardData'
import {
  bearerFrom,
  errors,
  findAccountByUserId,
  findSessionByAccess,
  guardSession,
  ok,
} from '../state'

const BASE = `${API_PREFIX}/dashboard`

function caller(request: Request) {
  const session = findSessionByAccess(bearerFrom(request))
  return session ? findAccountByUserId(session.userId) : null
}

function parseRange(url: URL, fallback: DashboardRange): DashboardRange | null {
  const range = url.searchParams.get('range') ?? fallback
  return range === '7d' || range === '30d' ? range : null
}

export const dashboardHandlers = [
  http.get(`${BASE}/admin`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role !== 'COLLEGE_ADMIN') return errors.forbidden()

    const range = parseRange(new URL(request.url), '30d')
    if (!range) return errors.invalidRequest()
    return ok(adminDashboard(range))
  }),

  http.get(`${BASE}/leader`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role !== 'DIRECTION_LEADER') return errors.forbidden()

    const range = parseRange(new URL(request.url), '7d')
    if (!range) return errors.invalidRequest()
    return ok(leaderDashboard(range))
  }),

  http.get(`${BASE}/student`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role !== 'STUDENT') return errors.forbidden()

    const range = parseRange(new URL(request.url), '7d')
    if (!range) return errors.invalidRequest()
    return ok(studentDashboard(range))
  }),
]
