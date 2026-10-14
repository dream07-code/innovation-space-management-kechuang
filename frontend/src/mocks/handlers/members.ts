/**
 * Mock：成员管理。
 *
 * 权限矩阵（契约 §5 与成员 tag 各接口描述）：
 *   - 列表/详情/新建/改资料：管理员全量；负责人仅所辖方向的普通学生；
 *   - 更正学号、重置密码、恢复、直接加分：仅学院管理员；
 *   - 手动移出（removeMember）契约中仍是 pending —— 这里不提供 handler，
 *     REMOVED 状态只由 fixture 中的「自动移出」结果呈现。
 *
 * 幂等：声明了 Idempotency-Key 的接口按「同 key 同 body → 重放首个成功结果」模拟。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import type { Member } from '@/api/types'
import {
  MEMBERS,
  appendAudit,
  appendPointEntry,
  findMember,
  nextMemberIds,
  nowIso,
  paginate,
  parsePageParams,
  registerAccountFor,
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

/** 当前调用者账号（guardSession 通过后必然存在）。 */
function caller(request: Request) {
  const session = findSessionByAccess(bearerFrom(request))
  return session ? findAccountByUserId(session.userId) : null
}

/** 负责人可见的成员范围：所辖方向的普通学生。 */
function visibleTo(account: NonNullable<ReturnType<typeof caller>>, member: Member): boolean {
  if (account.user.role === 'COLLEGE_ADMIN') return true
  if (account.user.role === 'DIRECTION_LEADER') {
    return member.role === 'STUDENT' && account.user.managedDirectionIds.includes(member.directionId)
  }
  return false
}

/** 校验请求体只包含契约声明的字段（Jackson 严格模式，多一个字段 400）。 */
function rejectUnknownFields(body: Record<string, unknown>, allowed: readonly string[]): Response | null {
  for (const key of Object.keys(body)) {
    if (!allowed.includes(key)) return errors.invalidRequest()
  }
  return null
}

function parseJson(bodyText: string): Record<string, unknown> | null {
  try {
    const body = JSON.parse(bodyText || 'null')
    return typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : null
  } catch {
    return null
  }
}

export const memberHandlers = [
  // -- 列表 ------------------------------------------------------------------
  http.get(`${API_PREFIX}/members`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || (account.user.role !== 'COLLEGE_ADMIN' && account.user.role !== 'DIRECTION_LEADER')) {
      return errors.forbidden()
    }

    const url = new URL(request.url)
    const paging = parsePageParams(url)
    if (!paging) return errors.invalidRequest()

    const directionId = url.searchParams.get('directionId')
    const keyword = url.searchParams.get('keyword')?.trim()
    const status = url.searchParams.get('status')
    if (status !== null && status !== 'ACTIVE' && status !== 'REMOVED') return errors.invalidRequest()

    // 先授权过滤再计算 total（契约明确要求）。
    let items = MEMBERS.filter((m) => visibleTo(account, m))
    if (directionId) items = items.filter((m) => m.directionId === directionId)
    if (status) items = items.filter((m) => m.membershipStatus === status)
    if (keyword) {
      items = items.filter((m) => m.name.includes(keyword) || m.studentNo.includes(keyword))
    }

    // 契约：按 createdAt DESC, id 数值 DESC。
    items = [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || (BigInt(b.id) > BigInt(a.id) ? 1 : -1))
    return ok(paginate(items, paging.page, paging.size))
  }),

  // -- 新建学生 ----------------------------------------------------------------
  http.post(`${API_PREFIX}/members`, async ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || (account.user.role !== 'COLLEGE_ADMIN' && account.user.role !== 'DIRECTION_LEADER')) {
      return errors.forbidden()
    }

    return withIdempotency(request, (bodyText) => {
      const body = parseJson(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['studentNo', 'name', 'directionId', 'major', 'className', 'grade', 'contact'])
      if (bad) return bad

      const { studentNo, name, directionId } = body as { studentNo?: unknown; name?: unknown; directionId?: unknown }
      if (typeof studentNo !== 'string' || !/^[A-Za-z0-9_-]{1,32}$/.test(studentNo)) return errors.invalidRequest()
      if (typeof name !== 'string' || !name.trim()) return errors.invalidRequest()
      if (typeof directionId !== 'string' || !/^[1-7]$/.test(directionId)) return errors.invalidRequest()

      // 负责人仅可添加到所辖方向。
      if (account.user.role === 'DIRECTION_LEADER' && !account.user.managedDirectionIds.includes(directionId)) {
        return errors.forbidden()
      }

      // 学号/统一 account 唯一。
      if (MEMBERS.some((m) => m.studentNo === studentNo)) return errors.alreadyExists()

      const { memberId, userId } = nextMemberIds()
      const member: Member = {
        id: memberId,
        userId,
        studentNo,
        name: name.trim(),
        major: typeof body.major === 'string' ? body.major : null,
        className: typeof body.className === 'string' ? body.className : null,
        grade: typeof body.grade === 'number' ? body.grade : null,
        contact: typeof body.contact === 'string' ? body.contact : null,
        role: 'STUDENT',
        directionId,
        managedDirectionIds: [],
        membershipStatus: 'ACTIVE',
        accountStatus: 'ENABLED',
        mustChangePassword: true,
        joinedAt: nowIso(),
        membershipStartedAt: nowIso(),
        removedAt: null,
        removalReason: null,
        totalPoints: 0,
        version: 1,
        createdAt: nowIso(),
        updatedAt: nowIso(),
      }
      MEMBERS.push(member)
      registerAccountFor(member)
      appendAudit(account.user.userId, 'MEMBER_CREATED', 'MEMBER', member.id, null, '创建示例成员')
      return ok(member, 201)
    })
  }),

  // -- 详情 ------------------------------------------------------------------
  http.get(`${API_PREFIX}/members/:memberId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account) return errors.unauthorized()

    const member = findMember(params.memberId as string)
    if (!member || !visibleTo(account, member)) return errors.notFound()
    return ok(member)
  }),

  // -- 修改资料（乐观锁） -------------------------------------------------------
  http.patch(`${API_PREFIX}/members/:memberId`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account) return errors.unauthorized()

    const member = findMember(params.memberId as string)
    if (!member || !visibleTo(account, member)) return errors.notFound()

    const body = parseJson(await request.text())
    if (!body) return errors.invalidRequest()
    const bad = rejectUnknownFields(body, ['version', 'name', 'major', 'className', 'grade', 'contact', 'directionId'])
    if (bad) return bad
    // minProperties: 2 —— 除 version 外至少一个变更字段。
    if (Object.keys(body).length < 2) return errors.invalidRequest()

    // 契约：仅 STUDENT 走此接口，负责人资料走专用接口。
    if (member.role !== 'STUDENT') return errors.invalidRequest()
    // 负责人不能改方向。
    if (account.user.role === 'DIRECTION_LEADER' && 'directionId' in body) return errors.forbidden()
    if (body.version !== member.version) return errors.versionConflict()
    if ('directionId' in body && (typeof body.directionId !== 'string' || !/^[1-7]$/.test(body.directionId))) {
      return errors.invalidRequest()
    }

    if (typeof body.name === 'string' && body.name.trim()) member.name = body.name.trim()
    if ('major' in body) member.major = typeof body.major === 'string' ? body.major : null
    if ('className' in body) member.className = typeof body.className === 'string' ? body.className : null
    if ('grade' in body) member.grade = typeof body.grade === 'number' ? body.grade : null
    if ('contact' in body) member.contact = typeof body.contact === 'string' ? body.contact : null
    if (typeof body.directionId === 'string') member.directionId = body.directionId

    member.version += 1
    member.updatedAt = nowIso()
    appendAudit(account.user.userId, 'MEMBER_UPDATED', 'MEMBER', member.id, null, '更新成员资料')
    return ok(member)
  }),

  // -- 更正学号（仅管理员） ------------------------------------------------------
  http.post(`${API_PREFIX}/members/:memberId/account-corrections`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (account?.user.role !== 'COLLEGE_ADMIN') return errors.forbidden()

    const member = findMember(params.memberId as string)
    if (!member) return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseJson(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version', 'newStudentNo', 'reason'])
      if (bad) return bad
      const { newStudentNo, reason } = body as { newStudentNo?: unknown; reason?: unknown }
      if (typeof newStudentNo !== 'string' || !/^[A-Za-z0-9_-]{1,32}$/.test(newStudentNo)) return errors.invalidRequest()
      if (typeof reason !== 'string' || reason.length < 5) return errors.invalidRequest()
      if (body.version !== member.version) return errors.versionConflict()
      if (MEMBERS.some((m) => m.id !== member.id && m.studentNo === newStudentNo)) return errors.alreadyExists()

      member.studentNo = newStudentNo
      member.version += 1
      member.updatedAt = nowIso()

      // 同步登录账号并撤销全部会话（旧学号不再登录）。
      const target = findAccountByUserId(member.userId)
      if (target) {
        target.account = newStudentNo
        target.user.account = newStudentNo
        target.profile.studentNo = newStudentNo
        revokeSessionsOfUser(target.user.userId)
      }
      appendAudit(account!.user.userId, 'ACCOUNT_CORRECTED', 'MEMBER', member.id, reason, '更正学号')
      return ok(member)
    })
  }),

  // -- 重置密码（仅管理员） ------------------------------------------------------
  http.post(`${API_PREFIX}/members/:memberId/password-reset`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (account?.user.role !== 'COLLEGE_ADMIN') return errors.forbidden()

    const member = findMember(params.memberId as string)
    if (!member) return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseJson(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version', 'reason'])
      if (bad) return bad
      if (typeof body.reason !== 'string' || body.reason.length < 5) return errors.invalidRequest()
      if (body.version !== member.version) return errors.versionConflict()

      member.mustChangePassword = true
      member.version += 1
      member.updatedAt = nowIso()

      const target = findAccountByUserId(member.userId)
      if (target) {
        target.mustChangePassword = true
        target.password = 'Example-Only9!'
        revokeSessionsOfUser(target.user.userId)
      }
      appendAudit(account!.user.userId, 'PASSWORD_RESET', 'MEMBER', member.id, body.reason as string, '重置成员密码')
      return ok(member)
    })
  }),

  // -- 恢复学生（仅管理员，仅 REMOVED） -------------------------------------------
  http.post(`${API_PREFIX}/members/:memberId/restore`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (account?.user.role !== 'COLLEGE_ADMIN') return errors.forbidden()

    const member = findMember(params.memberId as string)
    if (!member) return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseJson(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version', 'reason'])
      if (bad) return bad
      if (typeof body.reason !== 'string' || body.reason.length < 5) return errors.invalidRequest()
      if (body.version !== member.version) return errors.versionConflict()
      // 契约：只恢复 REMOVED 学生。
      if (member.membershipStatus !== 'REMOVED') return errors.invalidState()

      member.membershipStatus = 'ACTIVE'
      member.removedAt = null
      member.removalReason = null
      // 历史流水保留，净积分清零；开始新的 14 天观察期。
      member.totalPoints = 0
      member.membershipStartedAt = nowIso()
      member.version += 1
      member.updatedAt = nowIso()

      const target = findAccountByUserId(member.userId)
      if (target) target.membershipStatus = 'ACTIVE'

      appendAudit(account!.user.userId, 'MEMBER_RESTORED', 'MEMBER', member.id, body.reason as string, '恢复学生在籍资格')
      return ok(member)
    })
  }),

  // -- 管理员直接加分（契约明确：不需要 reason/version/Idempotency-Key） -------------
  http.post(`${API_PREFIX}/members/:memberId/points`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (account?.user.role !== 'COLLEGE_ADMIN') return errors.forbidden()

    const member = findMember(params.memberId as string)
    if (!member) return errors.notFound()

    const body = parseJson(await request.text())
    if (!body) return errors.invalidRequest()
    const bad = rejectUnknownFields(body, ['points'])
    if (bad) return bad
    // 契约：只接受正整数。
    if (typeof body.points !== 'number' || !Number.isInteger(body.points) || body.points < 1) {
      return errors.invalidRequest()
    }
    if (member.membershipStatus === 'REMOVED') return errors.invalidState()

    appendPointEntry(member.id, body.points, 'MANUAL_ADJUSTMENT', '管理员直接加分')
    member.version += 1
    appendAudit(account.user.userId, 'POINTS_MANUAL_ADDED', 'MEMBER', member.id, null, `管理员直接加分 ${body.points} 分`)
    return ok(member)
  }),
]
