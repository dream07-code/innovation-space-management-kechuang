/**
 * Mock：日报与周报。
 *
 * 两种报告的结构高度对称，用泛型收敛。关键契约行为：
 *   - 学生只看/写本人；负责人看本人全部 + 所辖方向正式报告；管理员只看正式报告；
 *   - 每成员每日期/自然周仅一份（含草稿），重复 409 REPORT_ALREADY_EXISTS；
 *   - 提交是异步评分开始：状态变 SUBMITTED/QUEUED、入账保底 1 分，finalScore 仍 null；
 *   - 修改需 version 一致且 editable=true；SUBMITTED 修改对合并后正文重验必填；
 *   - 相同 version 重复提交返回现状不加分。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import type { DailyReport, WeeklyReport } from '@/api/types'
import {
  DAILY_REPORTS,
  WEEKLY_REPORTS,
  appendAudit,
  appendPointEntry,
  currentWeekStart,
  endOfDay,
  endOfWeek,
  findMember,
  nextDailyReportId,
  nextWeeklyReportId,
  nowIso,
  paginate,
  parsePageParams,
  todayBusiness,
  weekEndOf,
} from '../data'
import {
  bearerFrom,
  errors,
  findAccountByUserId,
  findSessionByAccess,
  guardSession,
  ok,
} from '../state'
import { withIdempotency } from './idempotency'

function caller(request: Request) {
  const session = findSessionByAccess(bearerFrom(request))
  return session ? findAccountByUserId(session.userId) : null
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

function validateDailyBody(body: Record<string, unknown>): Response | null {
  const learningContent = body.learningContent
  if (typeof learningContent !== 'string' || !learningContent.trim() || learningContent.trim().length > 10000) {
    return errors.invalidRequest()
  }
  if ('gains' in body && body.gains !== undefined && (typeof body.gains !== 'string' || body.gains.length > 10000)) {
    return errors.invalidRequest()
  }
  if (
    'problems' in body &&
    body.problems !== undefined &&
    body.problems !== null &&
    (typeof body.problems !== 'string' || body.problems.length > 5000)
  ) {
    return errors.invalidRequest()
  }
  return null
}

// ---------------------------------------------------------------------------
// 泛型报告逻辑
// ---------------------------------------------------------------------------

type AnyReport = DailyReport | WeeklyReport

interface ReportSpec<R extends AnyReport> {
  base: string
  store: R[]
  nextId: () => string
  reportType: 'DAILY' | 'WEEKLY'
  periodKey: 'reportDate' | 'weekStart'
  requiredFields: readonly (keyof R)[]
  createAllowed: readonly string[]
  updateAllowed: readonly string[]
  automaticPeriod?: () => string
  makeDraft: (id: string, memberId: string, directionId: string, body: Record<string, unknown>) => R
}

/** 逐对象可见性：本人全部；负责人加所辖方向正式报告；管理员仅正式报告。 */
function visibleTo(account: NonNullable<ReturnType<typeof caller>>, report: AnyReport): boolean {
  if (report.memberId === account.user.memberId) return true
  if (account.user.role === 'COLLEGE_ADMIN') return report.status === 'SUBMITTED'
  if (account.user.role === 'DIRECTION_LEADER') {
    return report.status === 'SUBMITTED' && account.user.managedDirectionIds.includes(report.directionId)
  }
  return false
}

function listReports<R extends AnyReport>(spec: ReportSpec<R>, request: Request): Response {
  const denied = guardSession(request)
  if (denied) return denied
  const account = caller(request)
  if (!account) return errors.unauthorized()

  const url = new URL(request.url)
  const paging = parsePageParams(url)
  if (!paging) return errors.invalidRequest()

  const memberId = url.searchParams.get('memberId')
  const directionId = url.searchParams.get('directionId')
  const fromDate = url.searchParams.get('fromDate')
  const toDate = url.searchParams.get('toDate')
  const status = url.searchParams.get('status')
  const scoreStatus = url.searchParams.get('scoreStatus')
  if (status !== null && status !== 'DRAFT' && status !== 'SUBMITTED') return errors.invalidRequest()
  if (fromDate && toDate && toDate < fromDate) return errors.invalidRequest()

  // 学生只能看本人：显式查他人直接 403（契约：越权筛选 403）。
  if (account.user.role === 'STUDENT' && memberId && memberId !== account.user.memberId) {
    return errors.forbidden()
  }
  // 负责人指定方向时，不能超出授权范围。
  if (
    account.user.role === 'DIRECTION_LEADER' &&
    directionId &&
    !account.user.managedDirectionIds.includes(directionId)
  ) {
    return errors.forbidden()
  }

  let items = spec.store.filter((r) => visibleTo(account, r))
  // periodKey 是「reportDate | weekStart」，而 AnyReport 是联合类型，
  // 必须收窄到 DailyReport/WeeklyReport 上各自存在的键。
  const periodOf = (r: AnyReport): string =>
    spec.periodKey === 'reportDate'
      ? (r as DailyReport).reportDate
      : (r as WeeklyReport).weekStart
  if (memberId) items = items.filter((r) => r.memberId === memberId)
  if (directionId) items = items.filter((r) => r.directionId === directionId)
  if (fromDate) items = items.filter((r) => periodOf(r) >= fromDate)
  if (toDate) items = items.filter((r) => periodOf(r) <= toDate)
  if (status) items = items.filter((r) => r.status === status)
  if (scoreStatus) items = items.filter((r) => r.scoreStatus === scoreStatus)

  // 契约：按周期起点 DESC, id 数值 DESC。
  items = [...items].sort(
    (a, b) => periodOf(b).localeCompare(periodOf(a)) || (BigInt(b.id) > BigInt(a.id) ? 1 : -1),
  )
  return ok(paginate(items, paging.page, paging.size))
}

function createReport<R extends AnyReport>(spec: ReportSpec<R>, request: Request): Promise<Response> {
  const preDenied = guardSession(request)
  if (preDenied) return Promise.resolve(preDenied)
  const account = caller(request)
  if (!account || (account.user.role !== 'STUDENT' && account.user.role !== 'DIRECTION_LEADER')) {
    return Promise.resolve(errors.forbidden())
  }
  const memberId = account.user.memberId
  if (!memberId) return Promise.resolve(errors.forbidden())

  return withIdempotency(request, (bodyText) => {
    const body = parseJson(bodyText)
    if (!body) return errors.invalidRequest()
    const bad = rejectUnknownFields(body, spec.createAllowed)
    if (bad) return bad

    const dailyInvalid = spec.reportType === 'DAILY' ? validateDailyBody(body) : null
    if (dailyInvalid) return dailyInvalid

    const periodValue = spec.automaticPeriod ? spec.automaticPeriod() : body[spec.periodKey]
    if (typeof periodValue !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(periodValue)) {
      return errors.invalidRequest()
    }
    // 周报 weekStart 必须为周一。
    if (spec.periodKey === 'weekStart') {
      const day = new Date(`${periodValue}T00:00:00Z`).getUTCDay()
      if (day !== 1) return errors.invalidRequest()
    }

    // 每成员每周期仅一份（含草稿）。
    if (
      spec.store.some(
        (r) =>
          r.memberId === memberId &&
          (spec.periodKey === 'reportDate'
            ? (r as DailyReport).reportDate
            : (r as WeeklyReport).weekStart) === periodValue,
      )
    ) {
      return errors.reportAlreadyExists()
    }

    const member = findMember(memberId)
    const draft = spec.makeDraft(nextDailyReportIdOr(spec), memberId, member?.directionId ?? '1', body)
    spec.store.push(draft)
    appendAudit(account.user.userId, `${spec.reportType}_REPORT_CREATED`, 'REPORT', draft.id, null, '创建报告草稿')
    return ok(draft, 201)
  })
}

function nextDailyReportIdOr<R extends AnyReport>(spec: ReportSpec<R>): string {
  return spec.nextId()
}

function getReport<R extends AnyReport>(spec: ReportSpec<R>, request: Request, reportId: string): Response {
  const denied = guardSession(request)
  if (denied) return denied
  const account = caller(request)
  if (!account) return errors.unauthorized()

  const report = spec.store.find((r) => r.id === reportId)
  if (!report || !visibleTo(account, report)) return errors.notFound()
  return ok(report)
}

async function updateReport<R extends AnyReport>(spec: ReportSpec<R>, request: Request, reportId: string): Promise<Response> {
  const denied = guardSession(request)
  if (denied) return denied
  const account = caller(request)
  if (!account) return errors.unauthorized()

  const report = spec.store.find((r) => r.id === reportId)
  if (!report) return errors.notFound()
  // 仅作者可改。
  if (report.memberId !== account.user.memberId) return errors.forbidden()
  if (!report.editable) return errors.reportNotEditable()

  const body = parseJson(await request.text())
  if (!body) return errors.invalidRequest()
  const bad = rejectUnknownFields(body, spec.updateAllowed)
  if (bad) return bad
  if (Object.keys(body).length < 2) return errors.invalidRequest()
  if (body.version !== report.version) return errors.versionConflict()
  const dailyInvalid = spec.reportType === 'DAILY' ? validateDailyBody(body) : null
  if (dailyInvalid) return dailyInvalid

  // 合并后校验：SUBMITTED 修改必须保证必填内容非空白。
  const merged = { ...report, ...body } as R
  for (const field of spec.requiredFields) {
    const value = merged[field]
    if (report.status === 'SUBMITTED' && (typeof value !== 'string' || !value.trim())) {
      return errors.invalidRequest()
    }
  }

  for (const key of Object.keys(body)) {
    if (key === 'version') continue
    ;(report as unknown as Record<string, unknown>)[key] = body[key]
  }
  report.version += 1
  report.updatedAt = nowIso()

  // 有效更新产生新版本重评：评分状态回到 QUEUED，最终分清空等待重评。
  if (report.status === 'SUBMITTED') {
    report.scoreStatus = 'QUEUED'
    report.finalScore = null
    report.scoreReason = null
  }
  appendAudit(account.user.userId, `${spec.reportType}_REPORT_UPDATED`, 'REPORT', report.id, null, '更新报告内容')
  return ok(report)
}

function submitReport<R extends AnyReport>(spec: ReportSpec<R>, request: Request, reportId: string): Promise<Response> {
  const preDenied = guardSession(request)
  if (preDenied) return Promise.resolve(preDenied)
  const account = caller(request)
  if (!account) return Promise.resolve(errors.unauthorized())

  const report = spec.store.find((r) => r.id === reportId)
  if (!report) return Promise.resolve(errors.notFound())
  if (report.memberId !== account.user.memberId) return Promise.resolve(errors.forbidden())

  return withIdempotency(request, (bodyText) => {
    const body = parseJson(bodyText)
    if (!body) return errors.invalidRequest()
    const bad = rejectUnknownFields(body, ['version'])
    if (bad) return bad
    if (body.version !== report.version) return errors.versionConflict()

    // 相同 version 已 SUBMITTED：返回现状不加分。
    if (report.status === 'SUBMITTED') return ok(report)

    // 正文两项必填且非空白。
    for (const field of spec.requiredFields) {
      const value = report[field]
      if (typeof value !== 'string' || !value.trim()) return errors.invalidRequest()
    }

    report.status = 'SUBMITTED'
    report.scoreStatus = 'QUEUED'
    report.awardedPoints = 1
    report.firstSubmittedAt = nowIso()
    report.updatedAt = nowIso()
    report.version += 1

    appendPointEntry(report.memberId, 1, 'SUBMISSION_BASE', '报告正式提交保底积分', {
      reportType: spec.reportType,
      reportId: report.id,
      reportVersion: report.version,
    })
    appendAudit(account!.user.userId, `${spec.reportType}_REPORT_SUBMITTED`, 'REPORT', report.id, null, '正式提交报告')
    return ok(report)
  })
}

/**
 * 人工评分：学院管理员可评全部方向，方向负责人仅可评所辖方向且不能评本人报告。
 * 评分后 scoreStatus=MANUAL，积分按差额调整。
 */
function scoreReport<R extends AnyReport>(
  spec: ReportSpec<R>,
  request: Request,
  reportId: string,
): Promise<Response> {
  const denied = guardSession(request)
  if (denied) return Promise.resolve(denied)
  const account = caller(request)
  if (!account) return Promise.resolve(errors.unauthorized())

  const report = spec.store.find((r) => r.id === reportId)
  if (!report) return Promise.resolve(errors.notFound())
  if (account.user.role !== 'COLLEGE_ADMIN' && account.user.role !== 'DIRECTION_LEADER') {
    return Promise.resolve(errors.forbidden())
  }
  if (
    account.user.role === 'DIRECTION_LEADER' &&
    (report.memberId === account.user.memberId ||
      !account.user.managedDirectionIds.includes(report.directionId))
  ) {
    return Promise.resolve(errors.forbidden())
  }

  return request.text().then((bodyText) => {
    const body = parseJson(bodyText)
    if (!body) return errors.invalidRequest()
    const bad = rejectUnknownFields(body, ['score', 'reason'])
    if (bad) return bad
    const score = body.score
    if (typeof score !== 'number' || !Number.isInteger(score) || score < 1 || score > 3) {
      return errors.invalidRequest()
    }
    if (
      body.reason !== undefined &&
      body.reason !== null &&
      (typeof body.reason !== 'string' || body.reason.length > 1000)
    ) {
      return errors.invalidRequest()
    }
    if (report.status !== 'SUBMITTED') return errors.invalidState()

    const delta = score - report.awardedPoints
    report.finalScore = score
    report.scoreStatus = 'MANUAL'
    report.scoreReason =
      typeof body.reason === 'string' && body.reason.trim() ? body.reason.trim() : null
    report.awardedPoints = score
    report.updatedAt = nowIso()
    if (delta !== 0) {
      appendPointEntry(report.memberId, delta, 'MANUAL_ADJUSTMENT', '人工评分调整', {
        reportType: spec.reportType,
        reportId: report.id,
        reportVersion: report.version,
      })
    }
    appendAudit(
      account.user.userId,
      'REPORT_MANUAL_SCORE',
      'REPORT',
      report.id,
      report.scoreReason,
      `人工评分 ${score} 分`,
    )
    return ok(report)
  })
}

// ---------------------------------------------------------------------------
// 日报与周报的具体定义
// ---------------------------------------------------------------------------
const DAILY_SPEC: ReportSpec<DailyReport> = {
  base: `${API_PREFIX}/daily-reports`,
  store: DAILY_REPORTS,
  nextId: nextDailyReportId,
  reportType: 'DAILY',
  periodKey: 'reportDate',
  requiredFields: ['learningContent'],
  createAllowed: ['learningContent', 'gains', 'problems', 'nextPlan', 'links', 'images'],
  updateAllowed: ['version', 'learningContent', 'gains', 'problems', 'nextPlan', 'links', 'images'],
  automaticPeriod: todayBusiness,
  makeDraft(id, memberId, directionId, body) {
    const reportDate = todayBusiness()
    const member = findMember(memberId)
    return {
      id,
      memberId,
      memberName: member?.name ?? null,
      memberStudentNo: member?.studentNo ?? null,
      directionId,
      status: 'DRAFT',
      version: 1,
      scoreStatus: 'NOT_REQUESTED',
      awardedPoints: 0,
      finalScore: null,
      scoreReason: null,
      firstSubmittedAt: null,
      updatedAt: nowIso(),
      editable: true,
      editableUntil: endOfDay(reportDate),
      ruleVersion: 'mock-rule-1',
      reportDate,
      learningContent: typeof body.learningContent === 'string' ? body.learningContent : '',
      gains: typeof body.gains === 'string' ? body.gains : '',
      problems: typeof body.problems === 'string' ? body.problems : null,
      nextPlan: typeof body.nextPlan === 'string' ? body.nextPlan : null,
      links: Array.isArray(body.links) ? (body.links as string[]) : [],
      images: Array.isArray(body.images) ? body.images : [],
    }
  },
}

const WEEKLY_SPEC: ReportSpec<WeeklyReport> = {
  base: `${API_PREFIX}/weekly-reports`,
  store: WEEKLY_REPORTS,
  nextId: nextWeeklyReportId,
  reportType: 'WEEKLY',
  periodKey: 'weekStart',
  requiredFields: ['summary', 'achievements'],
  createAllowed: ['weekStart', 'summary', 'achievements', 'reflections', 'nextWeekPlan', 'links', 'images'],
  updateAllowed: ['version', 'summary', 'achievements', 'reflections', 'nextWeekPlan', 'links', 'images'],
  makeDraft(id, memberId, directionId, body) {
    const weekStart = body.weekStart as string
    const member = findMember(memberId)
    return {
      id,
      memberId,
      memberName: member?.name ?? null,
      memberStudentNo: member?.studentNo ?? null,
      directionId,
      status: 'DRAFT',
      version: 1,
      scoreStatus: 'NOT_REQUESTED',
      awardedPoints: 0,
      finalScore: null,
      scoreReason: null,
      firstSubmittedAt: null,
      updatedAt: nowIso(),
      editable: true,
      editableUntil: endOfWeek(weekStart),
      ruleVersion: 'mock-rule-1',
      weekStart,
      weekEnd: weekEndOf(weekStart),
      summary: typeof body.summary === 'string' ? body.summary : '',
      achievements: typeof body.achievements === 'string' ? body.achievements : '',
      reflections: typeof body.reflections === 'string' ? body.reflections : null,
      nextWeekPlan: typeof body.nextWeekPlan === 'string' ? body.nextWeekPlan : null,
      links: Array.isArray(body.links) ? (body.links as string[]) : [],
      images: Array.isArray(body.images) ? body.images : [],
    }
  },
}

// 保持对 currentWeekStart 的引用，避免 tree-shake 误删（fixture 初始化依赖它）。
void currentWeekStart

export const reportHandlers = [
  http.get(DAILY_SPEC.base, ({ request }) => listReports(DAILY_SPEC, request)),
  http.post(DAILY_SPEC.base, ({ request }) => createReport(DAILY_SPEC, request)),
  http.get(`${DAILY_SPEC.base}/:reportId`, ({ request, params }) => getReport(DAILY_SPEC, request, params.reportId as string)),
  http.patch(`${DAILY_SPEC.base}/:reportId`, ({ request, params }) => updateReport(DAILY_SPEC, request, params.reportId as string)),
  http.post(`${DAILY_SPEC.base}/:reportId/submit`, ({ request, params }) => submitReport(DAILY_SPEC, request, params.reportId as string)),
  http.post(`${DAILY_SPEC.base}/:reportId/score`, ({ request, params }) => scoreReport(DAILY_SPEC, request, params.reportId as string)),

  http.get(WEEKLY_SPEC.base, ({ request }) => listReports(WEEKLY_SPEC, request)),
  http.post(WEEKLY_SPEC.base, ({ request }) => createReport(WEEKLY_SPEC, request)),
  http.get(`${WEEKLY_SPEC.base}/:reportId`, ({ request, params }) => getReport(WEEKLY_SPEC, request, params.reportId as string)),
  http.patch(`${WEEKLY_SPEC.base}/:reportId`, ({ request, params }) => updateReport(WEEKLY_SPEC, request, params.reportId as string)),
  http.post(`${WEEKLY_SPEC.base}/:reportId/submit`, ({ request, params }) => submitReport(WEEKLY_SPEC, request, params.reportId as string)),
  http.post(`${WEEKLY_SPEC.base}/:reportId/score`, ({ request, params }) => scoreReport(WEEKLY_SPEC, request, params.reportId as string)),
]
