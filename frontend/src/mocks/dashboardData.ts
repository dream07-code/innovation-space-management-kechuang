/**
 * 角色数据看板 Mock DTO。
 *
 * 数据全部为虚构，字段与 docs/api/openapi.yaml 完全一致；
 * 不包含学号、联系方式、报告正文、代码、测试数据、Token 或 Provider 原始输出。
 */
import type {
  AdminDashboard,
  AttentionItem,
  DashboardRange,
  DashboardTrendPoint,
  DirectionDashboardRow,
  LeaderDashboard,
  PointEntrySummary,
  RankingEntry,
  RecentActivityItem,
  StudentAssessmentSummary,
  StudentDashboard,
  StudentDashboardRow,
  StudentReportSummary,
} from '@/api/types'

function isoDaysAgo(days: number, hour = 10, minute = 0): string {
  const date = new Date(Date.now() - days * 86_400_000)
  date.setHours(hour, minute, 0, 0)
  return date.toISOString()
}

function dateDaysAgo(days: number): string {
  return isoDaysAgo(days).slice(0, 10)
}

export function dashboardTrends(range: DashboardRange): DashboardTrendPoint[] {
  const length = range === '30d' ? 30 : 7
  return Array.from({ length }, (_, index) => {
    const daysAgo = length - index - 1
    const wave = (index * 7) % 5
    return {
      date: dateDaysAgo(daysAgo),
      dailySubmitted: 8 + wave + (index % 3),
      dailyScored: 6 + ((wave + 2) % 6),
      pointsAdded: 12 + wave * 2 + (index % 4),
    }
  })
}

const ADMIN_ATTENTION: AttentionItem[] = [
  { type: 'SCORING_FAILED', memberId: '206', displayName: '同学乙', directionName: 'AI 全栈', occurredAt: isoDaysAgo(0, 9), status: '待重新评分' },
  { type: 'PENDING_REVIEW', memberId: '207', displayName: '同学丙', directionName: '鸿蒙', occurredAt: isoDaysAgo(0, 8), status: '待人工复核' },
  { type: 'PENDING_OJ', memberId: '201', displayName: '示例同学', directionName: 'AI 全栈', occurredAt: isoDaysAgo(1, 16), status: 'OJ 判定中' },
  { type: 'INACTIVITY_RISK', memberId: '208', displayName: '同学丁', directionName: '网络安全', occurredAt: isoDaysAgo(2, 12), status: '连续 3 天未交日报' },
  { type: 'PENDING_REVIEW', memberId: '209', displayName: '同学戊', directionName: 'AI 全栈', occurredAt: isoDaysAgo(2, 11), status: '评分结果待确认' },
  { type: 'SCORING_FAILED', memberId: '203', displayName: '首次登录同学', directionName: 'AI 全栈', occurredAt: isoDaysAgo(3, 15), status: '评分服务不可用' },
  { type: 'PENDING_OJ', memberId: '206', displayName: '同学乙', directionName: 'AI 全栈', occurredAt: isoDaysAgo(3, 13), status: '提交进入判定队列' },
  { type: 'INACTIVITY_RISK', memberId: '207', displayName: '同学丙', directionName: '鸿蒙', occurredAt: isoDaysAgo(4, 10), status: '日报节奏需要关注' },
]

const ADMIN_DIRECTIONS: DirectionDashboardRow[] = [
  { directionId: '1', directionName: 'AI 全栈', activeStudents: 18, dailyCompletionRate: 86, weeklyCompletionRate: 78, averagePoints: 12.6, pendingItems: 3 },
  { directionId: '2', directionName: '鸿蒙', activeStudents: 14, dailyCompletionRate: 79, weeklyCompletionRate: 71, averagePoints: 10.8, pendingItems: 2 },
  { directionId: '3', directionName: '网络安全', activeStudents: 12, dailyCompletionRate: 83, weeklyCompletionRate: 75, averagePoints: 11.7, pendingItems: 1 },
  { directionId: '4', directionName: 'C++', activeStudents: 10, dailyCompletionRate: 74, weeklyCompletionRate: 68, averagePoints: 9.9, pendingItems: 2 },
  { directionId: '5', directionName: '大数据', activeStudents: 13, dailyCompletionRate: 81, weeklyCompletionRate: 73, averagePoints: 11.2, pendingItems: 1 },
  { directionId: '6', directionName: '人工智能', activeStudents: 15, dailyCompletionRate: 88, weeklyCompletionRate: 82, averagePoints: 13.1, pendingItems: 0 },
  { directionId: '7', directionName: '项目创意设计', activeStudents: 11, dailyCompletionRate: 77, weeklyCompletionRate: 70, averagePoints: 10.4, pendingItems: 2 },
]

const ADMIN_RECENT: RecentActivityItem[] = [
  { type: 'MEMBER_RESTORED', occurredAt: isoDaysAgo(0, 9, 30), summary: '管理员恢复了一名学生，历史积分流水保留。' },
  { type: 'POINTS_ADDED', occurredAt: isoDaysAgo(0, 9), summary: '管理员为表现突出的学生增加了积分。' },
  { type: 'LEADER_APPOINTED', occurredAt: isoDaysAgo(1, 16, 20), summary: '一名学生被任命为方向负责人。' },
  { type: 'PASSWORD_RESET', occurredAt: isoDaysAgo(1, 15), summary: '管理员重置了一个成员账号的登录密码。' },
  { type: 'ACCOUNT_CORRECTED', occurredAt: isoDaysAgo(2, 14), summary: '管理员更正了一条成员账号信息并撤销旧会话。' },
  { type: 'OJ_ASSESSMENT_PUBLISHED', occurredAt: isoDaysAgo(2, 11), summary: '方向负责人发布了一次 OJ 考核。' },
  { type: 'REPORT_REVIEWED', occurredAt: isoDaysAgo(3, 17), summary: '一份待人工复核的报告已完成批阅。' },
  { type: 'MEMBER_CREATED', occurredAt: isoDaysAgo(4, 10), summary: '方向负责人添加了一名新学生。' },
]

const LEADER_STUDENTS: StudentDashboardRow[] = [
  { memberId: '201', displayName: '示例同学', membershipStatus: 'ACTIVE', lastDailyAt: isoDaysAgo(0, 9), lastWeeklyAt: isoDaysAgo(2, 16), points: 8, directionRank: 1 },
  { memberId: '206', displayName: '同学乙', membershipStatus: 'ACTIVE', lastDailyAt: isoDaysAgo(0, 8), lastWeeklyAt: isoDaysAgo(3, 12), points: 7, directionRank: 2 },
  { memberId: '203', displayName: '首次登录同学', membershipStatus: 'ACTIVE', lastDailyAt: isoDaysAgo(1, 14), lastWeeklyAt: isoDaysAgo(4, 11), points: 5, directionRank: 3 },
  { memberId: '209', displayName: '同学戊', membershipStatus: 'ACTIVE', lastDailyAt: isoDaysAgo(1, 12), lastWeeklyAt: isoDaysAgo(5, 17), points: 5, directionRank: 3 },
  { memberId: '210', displayName: '同学己', membershipStatus: 'ACTIVE', lastDailyAt: isoDaysAgo(2, 10), lastWeeklyAt: isoDaysAgo(6, 10), points: 3, directionRank: 5 },
  { memberId: '211', displayName: '同学庚', membershipStatus: 'ACTIVE', lastDailyAt: isoDaysAgo(3, 16), lastWeeklyAt: isoDaysAgo(8, 15), points: 2, directionRank: 6 },
]

const LEADER_RANKING: RankingEntry[] = [
  { memberId: '201', displayName: '示例同学', directionId: '1', directionName: 'AI 全栈', points: 8, rank: 1 },
  { memberId: '206', displayName: '同学乙', directionId: '1', directionName: 'AI 全栈', points: 7, rank: 2 },
  { memberId: '203', displayName: '首次登录同学', directionId: '1', directionName: 'AI 全栈', points: 5, rank: 3 },
  { memberId: '209', displayName: '同学戊', directionId: '1', directionName: 'AI 全栈', points: 5, rank: 3 },
  { memberId: '210', displayName: '同学己', directionId: '1', directionName: 'AI 全栈', points: 3, rank: 5 },
  { memberId: '211', displayName: '同学庚', directionId: '1', directionName: 'AI 全栈', points: 2, rank: 6 },
]

const STUDENT_REPORTS: StudentReportSummary[] = [
  { reportId: '301', reportType: 'DAILY', status: 'SUBMITTED', scoreStatus: 'SCORED', submittedAt: isoDaysAgo(0, 9), editable: false },
  { reportId: '401', reportType: 'WEEKLY', status: 'SUBMITTED', scoreStatus: 'PROCESSING', submittedAt: isoDaysAgo(2, 16), editable: true },
  { reportId: '302', reportType: 'DAILY', status: 'SUBMITTED', scoreStatus: 'SCORED', submittedAt: isoDaysAgo(1, 10), editable: false },
  { reportId: '402', reportType: 'WEEKLY', status: 'DRAFT', scoreStatus: 'NOT_REQUESTED', submittedAt: null, editable: true },
]

const STUDENT_ASSESSMENTS: StudentAssessmentSummary[] = [
  { assessmentId: '9001', title: '十月算法基础考核', status: 'PUBLISHED', resultsPublished: true, submissionStatus: 'JUDGED', technicalVerdict: 'ACCEPTED', finalScore: 90, scoreSource: 'HUMAN' },
  { assessmentId: '9004', title: '十月算法进阶考核', status: 'PUBLISHED', resultsPublished: true, submissionStatus: 'JUDGED', technicalVerdict: 'WRONG_ANSWER', finalScore: 86, scoreSource: 'AUTO' },
  { assessmentId: '9002', title: '鸿蒙方向摸底考核', status: 'PUBLISHED', resultsPublished: false, submissionStatus: 'NONE', technicalVerdict: null, finalScore: null, scoreSource: 'PENDING' },
  { assessmentId: '9003', title: '九月结项考核', status: 'CLOSED', resultsPublished: true, submissionStatus: 'NEEDS_REVIEW', technicalVerdict: 'INTERNAL_ERROR', finalScore: null, scoreSource: 'PENDING' },
]

const STUDENT_POINTS: PointEntrySummary[] = [
  { entryId: '701', delta: 2, source: 'AI_ADJUSTMENT', reason: '日报评分调整', effectiveAt: isoDaysAgo(0, 10) },
  { entryId: '702', delta: 1, source: 'SUBMISSION_BASE', reason: '日报正式提交保底积分', effectiveAt: isoDaysAgo(0, 9) },
  { entryId: '703', delta: 3, source: 'MANUAL_ADJUSTMENT', reason: '管理员加分', effectiveAt: isoDaysAgo(1, 16) },
  { entryId: '704', delta: 1, source: 'SUBMISSION_BASE', reason: '周报正式提交保底积分', effectiveAt: isoDaysAgo(2, 16) },
]

export function adminDashboard(range: DashboardRange): AdminDashboard {
  return {
    range,
    overview: {
      activeStudents: 93,
      totalStudents: 101,
      directionLeaders: 7,
      removedStudents: 8,
      todayDailySubmitted: 71,
      dailyCompletionRate: 82.6,
      weeklyCompletionRate: 76.4,
      pendingAi: 6,
      failedAi: 2,
      pendingReview: 3,
      pendingOj: 4,
    },
    trends: dashboardTrends(range),
    directions: ADMIN_DIRECTIONS,
    attention: ADMIN_ATTENTION.slice(0, 10),
    recentActivity: ADMIN_RECENT,
  }
}

export function leaderDashboard(range: DashboardRange): LeaderDashboard {
  return {
    range,
    overview: {
      activeStudents: 18,
      totalStudents: 19,
      todayDailySubmitted: 15,
      dailyCompletionRate: 83.3,
      weeklyCompletionRate: 77.8,
      pendingAi: 2,
      failedAi: 1,
      pendingReview: 1,
      pendingOj: 2,
      pointsTotal: 86,
    },
    students: LEADER_STUDENTS,
    trends: dashboardTrends(range),
    attention: ADMIN_ATTENTION.slice(0, 6),
    rankingPreview: LEADER_RANKING,
  }
}

export function studentDashboard(range: DashboardRange): StudentDashboard {
  return {
    range,
    overview: {
      points: 8,
      overallRank: 3,
      directionRank: 1,
      daysSinceLastDaily: 0,
      dailyStatus: 'SUBMITTED',
      weeklyStatus: 'DUE',
      pendingOj: 1,
    },
    progress: {
      dailySubmitted: 5,
      dailyTarget: 7,
      weeklySubmitted: 1,
      weeklyTarget: 2,
      pointsAdded: 6,
      daysSinceLastDaily: 0,
    },
    reports: STUDENT_REPORTS,
    assessments: STUDENT_ASSESSMENTS,
    points: STUDENT_POINTS,
    rankingPreview: {
      overallRank: 3,
      overallCount: 93,
      directionRank: 1,
      directionCount: 18,
      eligible: true,
    },
  }
}
