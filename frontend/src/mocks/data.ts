/**
 * Mock 专用：业务数据模型（成员/报告/积分流水/审计）。
 *
 * 与 state.ts 的分工：state.ts 管会话与账号，本文件管业务实体。
 * 所有数据均为虚构，符合仓库「不提交真实学生名单、学号、联系方式」的要求。
 *
 * 一致性约定：
 *   - 成员的累计积分永远等于其积分流水的净和（恢复时清零也是通过状态重置体现）；
 *   - 被自动移出的成员保留历史流水（含冲正 REVERSAL），净积分为 0；
 *   - 每个写操作都追加一条脱敏审计记录，与后端行为对齐。
 */
import { API_PREFIX } from '@/api/constants'
import type {
  AuditEntry,
  DailyReport,
  Member,
  PointEntry,
  WeeklyReport,
} from '@/api/types'
import { ACCOUNTS, registerResetHook, type MockAccount } from './state'

// ---------------------------------------------------------------------------
// 业务时钟：固定 Asia/Shanghai（UTC+8），与后端一致
// ---------------------------------------------------------------------------

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 当前时间的 +08:00 ISO 串（契约的时间戳格式）。 */
export function nowIso(): string {
  const d = new Date(Date.now() + 8 * 3600_000)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}+08:00`
}

/** 当前业务日期 YYYY-MM-DD（+08:00）。 */
export function todayBusiness(): string {
  return nowIso().slice(0, 10)
}

/** 某业务日期 23:59:59+08:00，日报的可编辑截止。 */
export function endOfDay(date: string): string {
  return `${date}T23:59:59+08:00`
}

/** 某周起点（周一）的周日 23:59:59+08:00，周报的可编辑截止。 */
export function endOfWeek(weekStart: string): string {
  const d = new Date(`${weekStart}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + 6)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T23:59:59+08:00`
}

/** 由 weekStart 推 weekEnd（周日）。 */
export function weekEndOf(weekStart: string): string {
  return endOfWeek(weekStart).slice(0, 10)
}

// ---------------------------------------------------------------------------
// 成员
// ---------------------------------------------------------------------------

function baseMember(partial: Partial<Member> & Pick<Member, 'id' | 'userId' | 'studentNo' | 'name' | 'role' | 'directionId'>): Member {
  return {
    major: null,
    className: null,
    grade: null,
    contact: null,
    managedDirectionIds: [],
    membershipStatus: 'ACTIVE',
    accountStatus: 'ENABLED',
    mustChangePassword: false,
    joinedAt: '2026-09-01T09:00:00+08:00',
    membershipStartedAt: '2026-09-01T09:00:00+08:00',
    removedAt: null,
    removalReason: null,
    totalPoints: 0,
    version: 1,
    createdAt: '2026-09-01T09:00:00+08:00',
    updatedAt: '2026-09-01T09:00:00+08:00',
    ...partial,
  }
}

/** 初始成员 fixture。与 state.ts 的 ACCOUNTS 一一对应，另加四名填充排行的学生。 */
const INITIAL_MEMBERS: Member[] = [
  baseMember({ id: '201', userId: '101', studentNo: 'MOCK20260001', name: '示例同学', role: 'STUDENT', directionId: '1' }),
  baseMember({
    id: '202', userId: '102', studentNo: 'MOCK20260002', name: '示例负责人',
    role: 'DIRECTION_LEADER', directionId: '1', managedDirectionIds: ['1', '2'],
  }),
  baseMember({ id: '203', userId: '103', studentNo: 'MOCK20260003', name: '首次登录同学', role: 'STUDENT', directionId: '1', mustChangePassword: true }),
  baseMember({
    id: '204', userId: '104', studentNo: 'MOCK20260004', name: '已移出同学', role: 'STUDENT', directionId: '2',
    membershipStatus: 'REMOVED',
    removedAt: '2026-10-05T00:30:00+08:00',
    removalReason: '连续 14 个完整自然日未正式提交日报，系统自动移出',
    totalPoints: 0,
    version: 3,
  }),
  baseMember({ id: '205', userId: '105', studentNo: 'MOCK20260005', name: '停用同学', role: 'STUDENT', directionId: '3', accountStatus: 'DISABLED' }),
  baseMember({ id: '206', userId: '106', studentNo: 'MOCK20260006', name: '同学乙', role: 'STUDENT', directionId: '1' }),
  baseMember({ id: '207', userId: '107', studentNo: 'MOCK20260007', name: '同学丙', role: 'STUDENT', directionId: '2' }),
  baseMember({ id: '208', userId: '108', studentNo: 'MOCK20260008', name: '同学丁', role: 'STUDENT', directionId: '3' }),
  baseMember({ id: '209', userId: '109', studentNo: 'MOCK20260009', name: '同学戊', role: 'STUDENT', directionId: '1' }),
]

export const MEMBERS: Member[] = INITIAL_MEMBERS.map((m) => ({ ...m }))

export function findMember(memberId: string): Member | null {
  return MEMBERS.find((m) => m.id === memberId) ?? null
}

// ---------------------------------------------------------------------------
// 积分流水
// ---------------------------------------------------------------------------

let pointEntrySeq = 0

function entry(
  memberId: string,
  delta: number,
  source: PointEntry['source'],
  reason: string,
  opts: { reportType?: 'DAILY' | 'WEEKLY'; reportId?: string; reportVersion?: number; effectiveAt?: string } = {},
): PointEntry {
  pointEntrySeq += 1
  const at = opts.effectiveAt ?? nowIso()
  return {
    id: `9${String(pointEntrySeq).padStart(3, '0')}`,
    memberId,
    reportType: opts.reportType ?? 'DAILY',
    reportId: opts.reportId ?? '0',
    reportVersion: opts.reportVersion ?? 1,
    delta,
    source,
    reason,
    effectiveAt: at,
    recordedAt: at,
    ruleVersion: 'mock-rule-1',
  }
}

const INITIAL_POINT_ENTRIES: PointEntry[] = [
  // 201 示例同学：日报 301 保底+AI补分、日报 302 保底+AI补分 → 合计 5
  entry('201', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportId: '301', effectiveAt: '2026-10-09T10:00:00+08:00' }),
  entry('201', 2, 'AI_ADJUSTMENT', 'AI 评分 3 分，差额入账', { reportId: '301', reportVersion: 2, effectiveAt: '2026-10-09T10:05:00+08:00' }),
  entry('201', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportId: '302', effectiveAt: '2026-10-10T10:00:00+08:00' }),
  entry('201', 1, 'AI_ADJUSTMENT', 'AI 评分 2 分，差额入账', { reportId: '302', reportVersion: 2, effectiveAt: '2026-10-10T10:05:00+08:00' }),
  // 202 示例负责人：周报 405 → 合计 3
  entry('202', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportType: 'WEEKLY', reportId: '405', effectiveAt: '2026-10-06T10:00:00+08:00' }),
  entry('202', 2, 'AI_ADJUSTMENT', 'AI 评分 3 分，差额入账', { reportType: 'WEEKLY', reportId: '405', reportVersion: 2, effectiveAt: '2026-10-06T10:05:00+08:00' }),
  // 204 已移出同学：历史流水保留，移出时冲正清零
  entry('204', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportId: '399', effectiveAt: '2026-09-18T10:00:00+08:00' }),
  entry('204', 1, 'AI_ADJUSTMENT', 'AI 评分 2 分，差额入账', { reportId: '399', reportVersion: 2, effectiveAt: '2026-09-18T10:05:00+08:00' }),
  entry('204', -2, 'REVERSAL', '成员自动移出，净积分冲正清零', { effectiveAt: '2026-10-05T00:30:00+08:00' }),
  // 206 同学乙：合计 8
  entry('206', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportId: '304', effectiveAt: '2026-10-08T10:00:00+08:00' }),
  entry('206', 2, 'AI_ADJUSTMENT', 'AI 评分 3 分，差额入账', { reportId: '304', reportVersion: 2, effectiveAt: '2026-10-08T10:05:00+08:00' }),
  entry('206', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportType: 'WEEKLY', reportId: '402', effectiveAt: '2026-10-01T10:00:00+08:00' }),
  entry('206', 1, 'AI_ADJUSTMENT', 'AI 评分 2 分，差额入账', { reportType: 'WEEKLY', reportId: '402', reportVersion: 2, effectiveAt: '2026-10-01T10:05:00+08:00' }),
  entry('206', 3, 'MANUAL_ADJUSTMENT', '管理员直接加分', { effectiveAt: '2026-10-10T15:00:00+08:00' }),
  // 207 同学丙：合计 2
  entry('207', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportId: '306', effectiveAt: '2026-10-10T09:00:00+08:00' }),
  entry('207', 1, 'AI_ADJUSTMENT', 'AI 评分 2 分，差额入账', { reportId: '306', reportVersion: 2, effectiveAt: '2026-10-10T09:05:00+08:00' }),
  // 209 同学戊：合计 5（与 201 同分，验证竞赛排名并列）
  entry('209', 1, 'SUBMISSION_BASE', '报告正式提交保底积分', { reportId: '307', effectiveAt: '2026-10-09T11:00:00+08:00' }),
  entry('209', 2, 'AI_ADJUSTMENT', 'AI 评分 3 分，差额入账', { reportId: '307', reportVersion: 2, effectiveAt: '2026-10-09T11:05:00+08:00' }),
  entry('209', 2, 'MANUAL_ADJUSTMENT', '管理员直接加分', { effectiveAt: '2026-10-10T16:00:00+08:00' }),
]

export const POINT_ENTRIES: PointEntry[] = INITIAL_POINT_ENTRIES.map((e) => ({ ...e }))

/** 成员累计积分 = 流水净和（唯一口径，前端不另算）。 */
export function netPointsOf(memberId: string): number {
  return POINT_ENTRIES.filter((e) => e.memberId === memberId).reduce((sum, e) => sum + e.delta, 0)
}

/** 某业务月份（YYYY-MM）的积分净和。 */
export function monthlyPointsOf(memberId: string, month: string): number {
  return POINT_ENTRIES.filter((e) => e.memberId === memberId && e.effectiveAt.startsWith(month)).reduce(
    (sum, e) => sum + e.delta,
    0,
  )
}

/** 追加一条流水并同步成员累计积分。 */
export function appendPointEntry(
  memberId: string,
  delta: number,
  source: PointEntry['source'],
  reason: string,
  opts: { reportType?: 'DAILY' | 'WEEKLY'; reportId?: string; reportVersion?: number } = {},
): PointEntry {
  const e = entry(memberId, delta, source, reason, opts)
  POINT_ENTRIES.push(e)
  const member = findMember(memberId)
  if (member) {
    member.totalPoints = netPointsOf(memberId)
    member.updatedAt = nowIso()
  }
  return e
}

// ---------------------------------------------------------------------------
// 报告
// ---------------------------------------------------------------------------

let dailySeq = 400
let weeklySeq = 500

type ReportSeed<T> = Omit<T, 'memberName' | 'memberStudentNo'>

const INITIAL_DAILY_REPORTS: ReportSeed<DailyReport>[] = [
  {
    id: '301', memberId: '201', directionId: '1', status: 'SUBMITTED', version: 2,
    scoreStatus: 'SCORED', awardedPoints: 3, finalScore: 3,
    scoreReason: '内容完整，收获具体，示例评语。',
    firstSubmittedAt: '2026-10-09T10:00:00+08:00', updatedAt: '2026-10-09T10:05:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-09',
    learningContent: '完成接口请求与参数校验练习。', gains: '理解了参数校验与异常映射。',
    problems: null, nextPlan: null, links: [],
  },
  {
    id: '302', memberId: '201', directionId: '1', status: 'SUBMITTED', version: 2,
    scoreStatus: 'SCORED', awardedPoints: 2, finalScore: 2,
    scoreReason: '内容基本完整，示例评语。',
    firstSubmittedAt: '2026-10-10T10:00:00+08:00', updatedAt: '2026-10-10T10:05:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-10',
    learningContent: '练习分页与筛选参数的组合使用。', gains: '熟悉了列表接口的过滤口径。',
    problems: '对同分排名规则理解不深。', nextPlan: '继续阅读契约示例。', links: [],
  },
  {
    id: '303', memberId: '201', directionId: '1', status: 'DRAFT', version: 1,
    scoreStatus: 'NOT_REQUESTED', awardedPoints: 0, finalScore: null, scoreReason: null,
    firstSubmittedAt: null, updatedAt: `${todayBusiness()}T09:00:00+08:00`,
    editable: true, editableUntil: endOfDay(todayBusiness()), ruleVersion: 'mock-rule-1',
    reportDate: todayBusiness(),
    learningContent: '', gains: '', problems: null, nextPlan: null, links: [],
  },
  {
    id: '304', memberId: '206', directionId: '1', status: 'SUBMITTED', version: 2,
    scoreStatus: 'SCORED', awardedPoints: 3, finalScore: 3, scoreReason: '示例评语。',
    firstSubmittedAt: '2026-10-08T10:00:00+08:00', updatedAt: '2026-10-08T10:05:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-08',
    learningContent: '完成组件状态管理练习。', gains: '理解了响应式数据流。',
    problems: null, nextPlan: null, links: [],
  },
  {
    id: '305', memberId: '206', directionId: '1', status: 'SUBMITTED', version: 1,
    scoreStatus: 'QUEUED', awardedPoints: 1, finalScore: null, scoreReason: null,
    firstSubmittedAt: '2026-10-10T11:00:00+08:00', updatedAt: '2026-10-10T11:00:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-10',
    learningContent: '完成路由守卫练习。', gains: '理解了导航守卫的执行顺序。',
    problems: null, nextPlan: null, links: [],
  },
  {
    id: '306', memberId: '207', directionId: '2', status: 'SUBMITTED', version: 2,
    scoreStatus: 'SCORED', awardedPoints: 2, finalScore: 2, scoreReason: '示例评语。',
    firstSubmittedAt: '2026-10-10T09:00:00+08:00', updatedAt: '2026-10-10T09:05:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-10',
    learningContent: '学习鸿蒙应用模型基础。', gains: '了解了 Stage 模型。',
    problems: null, nextPlan: null, links: [],
  },
  {
    id: '307', memberId: '209', directionId: '1', status: 'SUBMITTED', version: 2,
    scoreStatus: 'SCORED', awardedPoints: 3, finalScore: 3, scoreReason: '示例评语。',
    firstSubmittedAt: '2026-10-09T11:00:00+08:00', updatedAt: '2026-10-09T11:05:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-09',
    learningContent: '完成表单校验练习。', gains: '掌握了异步校验器的写法。',
    problems: null, nextPlan: null, links: [],
  },
  {
    id: '308', memberId: '202', directionId: '1', status: 'SUBMITTED', version: 1,
    scoreStatus: 'QUEUED', awardedPoints: 1, finalScore: null, scoreReason: null,
    firstSubmittedAt: '2026-10-10T08:00:00+08:00', updatedAt: '2026-10-10T08:00:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-10',
    learningContent: '梳理本周方向内成员的报告情况。', gains: '跟进了两位同学的进度。',
    problems: null, nextPlan: null, links: [],
  },
]

const INITIAL_WEEKLY_REPORTS: ReportSeed<WeeklyReport>[] = [
  {
    id: '401', memberId: '201', directionId: '1', status: 'DRAFT', version: 1,
    scoreStatus: 'NOT_REQUESTED', awardedPoints: 0, finalScore: null, scoreReason: null,
    firstSubmittedAt: null, updatedAt: `${todayBusiness()}T09:00:00+08:00`,
    editable: true, editableUntil: endOfWeek(currentWeekStart()), ruleVersion: 'mock-rule-1',
    weekStart: currentWeekStart(), weekEnd: weekEndOf(currentWeekStart()),
    summary: '', achievements: '', reflections: null, nextWeekPlan: null, links: [],
  },
  {
    id: '402', memberId: '206', directionId: '1', status: 'SUBMITTED', version: 2,
    scoreStatus: 'SCORED', awardedPoints: 2, finalScore: 2, scoreReason: '示例评语。',
    firstSubmittedAt: '2026-10-01T10:00:00+08:00', updatedAt: '2026-10-01T10:05:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    weekStart: '2026-09-28', weekEnd: '2026-10-04',
    summary: '本周完成基础 API 学习。', achievements: '完成了独立练习和复盘。',
    reflections: null, nextWeekPlan: null, links: [],
  },
  {
    id: '405', memberId: '202', directionId: '1', status: 'SUBMITTED', version: 2,
    scoreStatus: 'SCORED', awardedPoints: 3, finalScore: 3, scoreReason: '示例评语。',
    firstSubmittedAt: '2026-10-06T10:00:00+08:00', updatedAt: '2026-10-06T10:05:00+08:00',
    editable: false, editableUntil: null, ruleVersion: 'mock-rule-1',
    weekStart: '2026-10-05', weekEnd: '2026-10-11',
    summary: '本周完成方向内例会与个别辅导。', achievements: '组织了一次代码评审。',
    reflections: null, nextWeekPlan: null, links: [],
  },
]

/** 本周周一（业务时区）。 */
export function currentWeekStart(): string {
  const d = new Date(Date.now() + 8 * 3600_000)
  const day = d.getUTCDay() // 0=周日
  const offset = day === 0 ? 6 : day - 1
  d.setUTCDate(d.getUTCDate() - offset)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

/** 报告列表同样返回成员姓名与学号，避免管理端只能看到成员 ID。 */
function withMember<T extends { memberId: string }>(
  report: T,
): T & { memberName: string | null; memberStudentNo: string | null } {
  const member = findMember(report.memberId)
  return { ...report, memberName: member?.name ?? null, memberStudentNo: member?.studentNo ?? null }
}

export const DAILY_REPORTS: DailyReport[] = INITIAL_DAILY_REPORTS.map(withMember)
export const WEEKLY_REPORTS: WeeklyReport[] = INITIAL_WEEKLY_REPORTS.map(withMember)

export function nextDailyReportId(): string {
  dailySeq += 1
  return String(dailySeq)
}

export function nextWeeklyReportId(): string {
  weeklySeq += 1
  return String(weeklySeq)
}

// ---------------------------------------------------------------------------
// 审计
// ---------------------------------------------------------------------------

let auditSeq = 0

function audit(
  actorUserId: string | null,
  action: string,
  resourceType: string,
  resourceId: string | null,
  reason: string | null,
  summary: string,
  occurredAt?: string,
): AuditEntry {
  auditSeq += 1
  return {
    id: `8${String(auditSeq).padStart(3, '0')}`,
    actorUserId,
    action,
    resourceType,
    resourceId,
    reason,
    occurredAt: occurredAt ?? nowIso(),
    summary,
  }
}

const INITIAL_AUDITS: AuditEntry[] = [
  audit(null, 'MEMBER_AUTO_REMOVED', 'MEMBER', '204', '连续 14 个完整自然日未正式提交日报', '系统自动移出成员', '2026-10-05T00:30:00+08:00'),
  audit('1', 'PASSWORD_RESET', 'MEMBER', '203', '示例管理员协助首次激活', '重置成员密码', '2026-10-08T14:00:00+08:00'),
  audit('1', 'MEMBER_CREATED', 'MEMBER', '209', null, '创建示例成员', '2026-10-08T10:00:00+08:00'),
  audit('1', 'POINTS_MANUAL_ADDED', 'MEMBER', '206', null, '管理员直接加分 3 分', '2026-10-10T15:00:00+08:00'),
]

export const AUDITS: AuditEntry[] = INITIAL_AUDITS.map((a) => ({ ...a }))

/** 追加审计记录（脱敏摘要，不含密码/token/报告全文）。 */
export function appendAudit(
  actorUserId: string | null,
  action: string,
  resourceType: string,
  resourceId: string | null,
  reason: string | null,
  summary: string,
): AuditEntry {
  const a = audit(actorUserId, action, resourceType, resourceId, reason, summary)
  AUDITS.unshift(a)
  return a
}

// ---------------------------------------------------------------------------
// 动态开户（createMember 使用）
// ---------------------------------------------------------------------------

let memberSeq = 300
let userSeq = 200

export function nextMemberIds(): { memberId: string; userId: string } {
  memberSeq += 1
  userSeq += 1
  return { memberId: String(memberSeq), userId: String(userSeq) }
}

/** 为新成员登记一个可登录账号（统一初始口令，首次登录强制改密）。 */
export function registerAccountFor(member: Member): MockAccount {
  const account: MockAccount = {
    account: member.studentNo,
    password: 'Example-Only9!',
    user: {
      userId: member.userId,
      memberId: member.id,
      account: member.studentNo,
      displayName: member.name,
      role: member.role,
      directionId: member.directionId,
      managedDirectionIds: [...member.managedDirectionIds],
    },
    mustChangePassword: true,
    accountStatus: 'ENABLED',
    membershipStatus: 'ACTIVE',
    profile: {
      studentNo: member.studentNo,
      major: member.major,
      className: member.className,
      grade: member.grade,
      contact: member.contact,
    },
  }
  ACCOUNTS.push(account)
  return account
}

// ---------------------------------------------------------------------------
// 查询辅助
// ---------------------------------------------------------------------------

/** 解析分页参数；越界返回 null（调用方应回 400）。 */
export function parsePageParams(url: URL): { page: number; size: number } | null {
  const page = Number(url.searchParams.get('page') ?? '1')
  const size = Number(url.searchParams.get('size') ?? '20')
  if (!Number.isInteger(page) || !Number.isInteger(size) || page < 1 || size < 1 || size > 200) {
    return null
  }
  return { page, size }
}

export function paginate<T>(items: T[], page: number, size: number) {
  const start = (page - 1) * size
  return { items: items.slice(start, start + size), page, size, total: items.length }
}

/** 按 id 数值排序（服务端排序口径；id 是十进制字符串）。 */
export function byNumericIdDesc(a: { id: string }, b: { id: string }): number {
  const x = BigInt(a.id)
  const y = BigInt(b.id)
  return x === y ? 0 : x > y ? -1 : 1
}

export function byNumericIdAsc(a: string, b: string): number {
  const x = BigInt(a)
  const y = BigInt(b)
  return x === y ? 0 : x > y ? 1 : -1
}

// ---------------------------------------------------------------------------
// 状态重置（测试隔离）
// ---------------------------------------------------------------------------

const INITIAL_LENGTHS = {
  members: MEMBERS.length,
  accounts: ACCOUNTS.length,
  pointEntries: POINT_ENTRIES.length,
  dailyReports: DAILY_REPORTS.length,
  weeklyReports: WEEKLY_REPORTS.length,
  audits: AUDITS.length,
}

function resetBusinessData(): void {
  MEMBERS.splice(0, MEMBERS.length, ...INITIAL_MEMBERS.map((m) => ({ ...m, managedDirectionIds: [...m.managedDirectionIds] })))
  POINT_ENTRIES.splice(0, POINT_ENTRIES.length, ...INITIAL_POINT_ENTRIES.map((e) => ({ ...e })))
  DAILY_REPORTS.splice(0, DAILY_REPORTS.length, ...INITIAL_DAILY_REPORTS.map((r) => withMember({ ...r, links: [...r.links] })))
  WEEKLY_REPORTS.splice(0, WEEKLY_REPORTS.length, ...INITIAL_WEEKLY_REPORTS.map((r) => withMember({ ...r, links: [...r.links] })))
  AUDITS.splice(0, AUDITS.length, ...INITIAL_AUDITS.map((a) => ({ ...a })))
  ACCOUNTS.splice(INITIAL_LENGTHS.accounts)
  memberSeq = 300
  userSeq = 200
  dailySeq = 400
  weeklySeq = 500
  pointEntrySeq = INITIAL_POINT_ENTRIES.length
  auditSeq = INITIAL_AUDITS.length
}

registerResetHook(resetBusinessData)

// 初始化序号，避免与 fixture 冲突。
pointEntrySeq = INITIAL_POINT_ENTRIES.length
auditSeq = INITIAL_AUDITS.length

export { API_PREFIX }
