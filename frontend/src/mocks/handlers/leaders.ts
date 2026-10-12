/**
 * Mock：方向负责人管理。全部仅学院管理员可用。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import {
  MEMBERS,
  appendAudit,
  findMember,
  nowIso,
  paginate,
  parsePageParams,
} from '../data'
import {
  bearerFrom,
  errors,
  findAccountByUserId,
  findSessionByAccess,
  guardSession,
  ok,
  revokeSessionsOfUser,
} from '../state'
import { withIdempotency } from './idempotency'

function caller(request: Request) {
  const session = findSessionByAccess(bearerFrom(request))
  return session ? findAccountByUserId(session.userId) : null
}

function adminOnly(request: Request): Response | null {
  const account = caller(request)
  return account?.user.role === 'COLLEGE_ADMIN' ? null : errors.forbidden()
}

function parseJson(bodyText: string): Record<string, unknown> | null {
  try {
    const body = JSON.parse(bodyText || 'null')
    return typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function rejectUnknownFields(body: Record<string, unknown>, allowed: readonly string[]): Response | null {
  for (const key of Object.keys(body)) {
    if (!allowed.includes(key)) return errors.invalidRequest()
  }
  return null
}

function validDirectionIds(value: unknown): value is string[] {
  return Array.isArray(value) && value.length >= 1 && value.every((v) => typeof v === 'string' && /^[1-7]$/.test(v))
}

export const leaderHandlers = [
  // -- 负责人列表 ----------------------------------------------------------------
  http.get(`${API_PREFIX}/direction-leaders`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const notAdmin = adminOnly(request)
    if (notAdmin) return notAdmin

    const url = new URL(request.url)
    const paging = parsePageParams(url)
    if (!paging) return errors.invalidRequest()

    const directionId = url.searchParams.get('directionId')
    let items = MEMBERS.filter((m) => m.role === 'DIRECTION_LEADER')
    if (directionId) items = items.filter((m) => m.managedDirectionIds.includes(directionId))
    // 契约：按 memberId 数值升序。
    items = [...items].sort((a, b) => (BigInt(a.id) > BigInt(b.id) ? 1 : -1))
    return ok(paginate(items, paging.page, paging.size))
  }),

  // -- 任命负责人 ----------------------------------------------------------------
  http.post(`${API_PREFIX}/direction-leaders`, async ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const notAdmin = adminOnly(request)
    if (notAdmin) return notAdmin

    return withIdempotency(request, (bodyText) => {
      const body = parseJson(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['memberId', 'version', 'managedDirectionIds', 'reason'])
      if (bad) return bad
      if (typeof body.memberId !== 'string') return errors.invalidRequest()
      if (typeof body.reason !== 'string' || body.reason.length < 5) return errors.invalidRequest()
      if (!validDirectionIds(body.managedDirectionIds)) return errors.invalidRequest()

      const member = findMember(body.memberId)
      if (!member) return errors.notFound()
      if (body.version !== member.version) return errors.versionConflict()
      // 契约：必须是 ACTIVE 且 ENABLED 的 STUDENT。
      if (member.role !== 'STUDENT' || member.membershipStatus !== 'ACTIVE' || member.accountStatus !== 'ENABLED') {
        return errors.invalidState()
      }

      member.role = 'DIRECTION_LEADER'
      member.managedDirectionIds = [...new Set(body.managedDirectionIds)]
      member.version += 1
      member.updatedAt = nowIso()

      const target = findAccountByUserId(member.userId)
      if (target) {
        target.user.role = 'DIRECTION_LEADER'
        target.user.managedDirectionIds = [...member.managedDirectionIds]
        revokeSessionsOfUser(target.user.userId)
      }
      appendAudit(caller(request)!.user.userId, 'LEADER_APPOINTED', 'MEMBER', member.id, body.reason as string, '任命方向负责人')
      return ok(member)
    })
  }),

  // -- 修改负责人 ----------------------------------------------------------------
  http.patch(`${API_PREFIX}/direction-leaders/:memberId`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const notAdmin = adminOnly(request)
    if (notAdmin) return notAdmin

    const member = findMember(params.memberId as string)
    if (!member) return errors.notFound()
    if (member.role !== 'DIRECTION_LEADER') return errors.invalidRequest()

    const body = parseJson(await request.text())
    if (!body) return errors.invalidRequest()
    const bad = rejectUnknownFields(body, ['version', 'reason', 'name', 'major', 'className', 'grade', 'contact', 'directionId', 'managedDirectionIds', 'accountStatus'])
    if (bad) return bad
    // minProperties: 3 —— version + reason + 至少一个变更字段。
    if (Object.keys(body).length < 3) return errors.invalidRequest()
    if (typeof body.reason !== 'string' || body.reason.length < 5) return errors.invalidRequest()
    if (body.version !== member.version) return errors.versionConflict()
    if ('managedDirectionIds' in body && !validDirectionIds(body.managedDirectionIds)) return errors.invalidRequest()
    if ('accountStatus' in body && body.accountStatus !== 'ENABLED' && body.accountStatus !== 'DISABLED') {
      return errors.invalidRequest()
    }

    const scopeChanged = 'managedDirectionIds' in body || 'accountStatus' in body

    if (typeof body.name === 'string' && body.name.trim()) member.name = body.name.trim()
    if ('major' in body) member.major = typeof body.major === 'string' ? body.major : null
    if ('className' in body) member.className = typeof body.className === 'string' ? body.className : null
    if ('grade' in body) member.grade = typeof body.grade === 'number' ? body.grade : null
    if ('contact' in body) member.contact = typeof body.contact === 'string' ? body.contact : null
    if (typeof body.directionId === 'string') member.directionId = body.directionId
    if (validDirectionIds(body.managedDirectionIds)) member.managedDirectionIds = [...new Set(body.managedDirectionIds)]
    if (body.accountStatus === 'ENABLED' || body.accountStatus === 'DISABLED') member.accountStatus = body.accountStatus

    member.version += 1
    member.updatedAt = nowIso()

    const target = findAccountByUserId(member.userId)
    if (target) {
      target.user.managedDirectionIds = [...member.managedDirectionIds]
      target.accountStatus = member.accountStatus
      // 契约：变更授权或账号状态撤销全部会话。
      if (scopeChanged) revokeSessionsOfUser(target.user.userId)
    }
    appendAudit(caller(request)!.user.userId, 'LEADER_UPDATED', 'MEMBER', member.id, body.reason as string, '修改负责人资料或授权')
    return ok(member)
  }),

  // -- 撤销负责人 ----------------------------------------------------------------
  http.post(`${API_PREFIX}/direction-leaders/:memberId/revoke`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const notAdmin = adminOnly(request)
    if (notAdmin) return notAdmin

    const member = findMember(params.memberId as string)
    if (!member) return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseJson(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version', 'reason'])
      if (bad) return bad
      if (typeof body.reason !== 'string' || body.reason.length < 5) return errors.invalidRequest()
      if (body.version !== member.version) return errors.versionConflict()
      if (member.role !== 'DIRECTION_LEADER') return errors.invalidState()

      member.role = 'STUDENT'
      member.managedDirectionIds = []
      member.version += 1
      member.updatedAt = nowIso()

      const target = findAccountByUserId(member.userId)
      if (target) {
        target.user.role = 'STUDENT'
        target.user.managedDirectionIds = []
        revokeSessionsOfUser(target.user.userId)
      }
      appendAudit(caller(request)!.user.userId, 'LEADER_REVOKED', 'MEMBER', member.id, body.reason as string, '撤销方向负责人')
      return ok(member)
    })
  }),
]
