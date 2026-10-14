/**
 * Mock：积分与排行榜。
 *
 * 排行榜口径（契约 排行榜 tag）：
 *   - 仅 ACTIVE 成员上榜；展示最小字段，绝不含学号；
 *   - 按 points DESC, memberId 数值 ASC；同分并列竞赛排名（1,1,3）；
 *   - period=MONTH 时按当月流水净和排名，只用于展示，不触发任何移出。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import type { RankingEntry } from '@/api/types'
import {
  MEMBERS,
  POINT_ENTRIES,
  monthlyPointsOf,
  netPointsOf,
  paginate,
  parsePageParams,
} from '../data'
import { DIRECTIONS, bearerFrom, errors, findAccountByUserId, findSessionByAccess, guardSession, ok } from '../state'

function caller(request: Request) {
  const session = findSessionByAccess(bearerFrom(request))
  return session ? findAccountByUserId(session.userId) : null
}

const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/

/** 组装榜单条目：仅契约允许的六个字段，绝不补学号。 */
function buildRanking(directionId: string | null, month: string | null): RankingEntry[] {
  const active = MEMBERS.filter(
    (m) =>
      m.role === 'STUDENT' &&
      m.membershipStatus === 'ACTIVE' &&
      (directionId === null || m.directionId === directionId),
  )
  const rows = active.map((m) => {
    const direction = DIRECTIONS.find((d) => d.id === m.directionId)
    return {
      memberId: m.id,
      displayName: m.name,
      directionId: m.directionId,
      directionName: direction?.name ?? '',
      points: month ? monthlyPointsOf(m.id, month) : netPointsOf(m.id),
    }
  })
  rows.sort((a, b) => b.points - a.points || (BigInt(a.memberId) > BigInt(b.memberId) ? 1 : -1))

  // 竞赛排名：同分同名次，下一名跳位（1,1,3）。
  let rank = 0
  let lastPoints: number | null = null
  return rows.map((row, index) => {
    if (row.points !== lastPoints) {
      rank = index + 1
      lastPoints = row.points
    }
    return { ...row, rank }
  })
}

function validatePeriod(url: URL): { period: 'ALL_TIME' | 'MONTH'; month: string | null } | Response {
  const period = url.searchParams.get('period') ?? 'ALL_TIME'
  const month = url.searchParams.get('month')
  if (period !== 'ALL_TIME' && period !== 'MONTH') return errors.invalidRequest()
  if (period === 'MONTH' && (!month || !MONTH_PATTERN.test(month))) return errors.invalidRequest()
  if (period === 'ALL_TIME' && month) return errors.invalidRequest()
  return { period, month }
}

function rankingResponse(url: URL, directionId: string | null): Response {
  const paging = parsePageParams(url)
  if (!paging) return errors.invalidRequest()
  const parsed = validatePeriod(url)
  if (parsed instanceof Response) return parsed

  const entries = buildRanking(directionId, parsed.month)
  const pageData = paginate(entries, paging.page, paging.size)
  return ok({
    ...pageData,
    period: parsed.period,
    month: parsed.month,
    asOf: new Date().toISOString(),
  })
}

export const pointHandlers = [
  // -- 本人积分概览 ---------------------------------------------------------------
  http.get(`${API_PREFIX}/points/me`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || !account.user.memberId) return errors.forbidden()

    const url = new URL(request.url)
    const month = url.searchParams.get('month')
    if (month && !MONTH_PATTERN.test(month)) return errors.invalidRequest()

    const memberId = account.user.memberId
    const effectiveMonth = month ?? new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 7)
    return ok({
      memberId,
      totalPoints: netPointsOf(memberId),
      month: effectiveMonth,
      monthlyPoints: monthlyPointsOf(memberId, effectiveMonth),
      asOf: new Date().toISOString(),
    })
  }),

  // -- 积分流水 ------------------------------------------------------------------
  http.get(`${API_PREFIX}/points/ledger`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account) return errors.unauthorized()

    const url = new URL(request.url)
    const paging = parsePageParams(url)
    if (!paging) return errors.invalidRequest()

    const memberId = url.searchParams.get('memberId')
    const month = url.searchParams.get('month')
    if (month && !MONTH_PATTERN.test(month)) return errors.invalidRequest()

    // 契约：管理员必须传 memberId；学生/负责人不传默认本人。
    let effectiveMemberId = memberId
    if (account.user.role === 'COLLEGE_ADMIN') {
      if (!memberId) return errors.invalidRequest()
    } else {
      effectiveMemberId = memberId ?? account.user.memberId
      // 学生不得指定他人；负责人指定他人时只能当前所辖成员。
      if (effectiveMemberId !== account.user.memberId) {
        if (account.user.role === 'STUDENT') return errors.forbidden()
        const target = MEMBERS.find((m) => m.id === effectiveMemberId)
        if (!target || !account.user.managedDirectionIds.includes(target.directionId)) {
          return errors.forbidden()
        }
      }
    }

    let items = POINT_ENTRIES.filter((e) => e.memberId === effectiveMemberId)
    if (month) items = items.filter((e) => e.effectiveAt.startsWith(month))
    items = [...items].sort((a, b) => b.recordedAt.localeCompare(a.recordedAt) || (BigInt(b.id) > BigInt(a.id) ? 1 : -1))
    return ok(paginate(items, paging.page, paging.size))
  }),
]

export const rankingHandlers = [
  http.get(`${API_PREFIX}/rankings/overall`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    return rankingResponse(new URL(request.url), null)
  }),

  http.get(`${API_PREFIX}/rankings/directions/:directionId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const directionId = params.directionId as string
    if (!DIRECTIONS.some((d) => d.id === directionId)) return errors.notFound()
    return rankingResponse(new URL(request.url), directionId)
  }),

  http.get(`${API_PREFIX}/rankings/me`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role !== 'STUDENT' || !account.user.memberId) {
      return errors.forbidden()
    }

    const url = new URL(request.url)
    const parsed = validatePeriod(url)
    if (parsed instanceof Response) return parsed

    const memberId = account.user.memberId
    const member = MEMBERS.find((m) => m.id === memberId)
    const eligible = member?.role === 'STUDENT' && member?.membershipStatus === 'ACTIVE'
    const points = parsed.month ? monthlyPointsOf(memberId, parsed.month) : netPointsOf(memberId)

    const overall = buildRanking(null, parsed.month)
    const direction = member ? buildRanking(member.directionId, parsed.month) : []
    const myOverall = overall.find((e) => e.memberId === memberId)
    const myDirection = direction.find((e) => e.memberId === memberId)

    return ok({
      memberId,
      directionId: member?.directionId ?? null,
      period: parsed.period,
      month: parsed.month,
      points,
      overallRank: eligible ? (myOverall?.rank ?? null) : null,
      directionRank: eligible ? (myDirection?.rank ?? null) : null,
      overallCount: overall.length,
      directionCount: direction.length,
      eligible,
      asOf: new Date().toISOString(),
    })
  }),
]
