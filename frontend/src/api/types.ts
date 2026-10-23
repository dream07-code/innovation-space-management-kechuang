/**
 * 契约类型的语义化别名。
 *
 * 原始类型由 openapi-typescript 从 docs/api/openapi.yaml 生成到 ./types.gen.ts，
 * 不要手改那个文件。本文件只做两件事：
 *   1. 把深层的 components['schemas'][...] 索引收成有名字的类型，便于业务代码引用；
 *   2. 集中所有「契约约定」的类型，使约定在编译期可被检查。
 *
 * 对应契约版本：backend@74cd50c（docs/api/openapi.yaml）。
 * 月度评定/任务相关类型已随契约删除；/score-reviews 与 /system/rules
 * 仍为规划状态，这里只保留类型，不做运行时调用。
 */
import type { components } from './types.gen'

type Schemas = components['schemas']

// ---------------------------------------------------------------------------
// 通用信封
// ---------------------------------------------------------------------------

/**
 * 业务信封。契约里没有泛型 ApiResponse，每个响应都是具体的包装 schema，
 * 但字段形状一致，因此这里给出一个结构化类型供客户端解包使用。
 */
export interface Envelope<T> {
  code: 'OK'
  message: string
  data: T
}

/** 失败响应。使用真实 HTTP 状态码，data 恒为 null，绝不包装成 HTTP 200。 */
export type ApiErrorBody = Schemas['ErrorResponse']

/** 成功但无数据的响应（data: null）。 */
export type EmptyBody = Schemas['EmptyResponse']

/** 分页载荷。total 是权限过滤后的记录数，不是页数。 */
export interface PageData<T> {
  items: T[]
  page: number
  size: number
  total: number
}

// ---------------------------------------------------------------------------
// 认证与身份
// ---------------------------------------------------------------------------

export type Role = Schemas['Role']
export type Identity = Schemas['Identity']
export type Session = Schemas['Session']
export type Me = Schemas['Me']
export type LoginRequest = Schemas['LoginRequest']
export type RefreshRequest = Schemas['RefreshRequest']
export type ChangePasswordRequest = Schemas['ChangePasswordRequest']

// ---------------------------------------------------------------------------
// 方向
// ---------------------------------------------------------------------------

export type Direction = Schemas['Direction']
export type DirectionCode = Direction['code']

// ---------------------------------------------------------------------------
// 成员与负责人
// ---------------------------------------------------------------------------

export type Member = Schemas['Member']
export type MemberPage = PageData<Member>
export type CreateMemberRequest = Schemas['CreateMemberRequest']
export type UpdateMemberRequest = Schemas['UpdateMemberRequest']
export type ReasonVersionRequest = Schemas['ReasonVersionRequest']
export type AccountCorrectionRequest = Schemas['AccountCorrectionRequest']
export type AppointLeaderRequest = Schemas['AppointLeaderRequest']
export type UpdateLeaderRequest = Schemas['UpdateLeaderRequest']
export type AddMemberPointsRequest = Schemas['AddMemberPointsRequest']

// ---------------------------------------------------------------------------
// 报告（日报/周报）
// ---------------------------------------------------------------------------

export type DailyReport = Schemas['DailyReport']
export type WeeklyReport = Schemas['WeeklyReport']
export type CreateDailyReportRequest = Schemas['CreateDailyReportRequest']
export type UpdateDailyReportRequest = Schemas['UpdateDailyReportRequest']
export type CreateWeeklyReportRequest = Schemas['CreateWeeklyReportRequest']
export type UpdateWeeklyReportRequest = Schemas['UpdateWeeklyReportRequest']
export type VersionRequest = Schemas['VersionRequest']
/** 人工评分请求：1–3 分，reason 可选。 */
export type ManualScoreReportRequest = Schemas['ManualScoreReportRequest']

export type ReportStatus = DailyReport['status']
export type ScoreStatus = DailyReport['scoreStatus']

// ---------------------------------------------------------------------------
// 积分与排行
// ---------------------------------------------------------------------------

export type PointsSummary = Schemas['PointsSummary']
export type PointEntry = Schemas['PointEntry']
export type PointSource = PointEntry['source']
export type RankingEntry = Schemas['RankingEntry']
export type RankingPage = Schemas['RankingPage']
export type MyRanking = Schemas['MyRanking']
export type RankingPeriod = NonNullable<MyRanking['period']>

// ---------------------------------------------------------------------------
// 审计
// ---------------------------------------------------------------------------

export type AuditEntry = Schemas['AuditEntry']

// ---------------------------------------------------------------------------
// OJ 考核（第二阶段）
// ---------------------------------------------------------------------------

export type ProblemExample = Schemas['ProblemExample']
export type Assessment = Schemas['Assessment']
export type AssessmentProblem = Schemas['AssessmentProblem']
export type AssessmentSubmission = Schemas['AssessmentSubmission']
export type OjJudgeResult = Schemas['OjJudgeResult']
export type OjSampleCase = Schemas['OjSampleCase']
export type OjTestCaseResult = Schemas['OjTestCaseResult']
export type AiEvaluation = Schemas['AiEvaluation']
export type AssessmentReview = Schemas['AssessmentReview']
export type AssessmentResult = Schemas['AssessmentResult']
export type CreateAssessmentRequest = Schemas['CreateAssessmentRequest']
export type UpdateAssessmentRequest = Schemas['UpdateAssessmentRequest']
export type AssessmentCommandRequest = Schemas['AssessmentCommandRequest']
export type CreateAssessmentProblemRequest = Schemas['CreateAssessmentProblemRequest']
export type ImportAssessmentProblemsRequest = Schemas['ImportAssessmentProblemsRequest']
export type AssessmentProblemImportError = Schemas['AssessmentProblemImportError']
export type AssessmentProblemImportResult = Schemas['AssessmentProblemImportResult']
export type AssessmentProblemImportResponse = Schemas['AssessmentProblemImportResponse']
export type AssessmentProblemTestCaseInput = Schemas['AssessmentProblemTestCaseInput']
export type AssessmentProblemTestCase = Schemas['AssessmentProblemTestCase']
export type AssessmentProblemTestCasesResponse = Schemas['AssessmentProblemTestCasesResponse']
export type AssessmentProblemTestCases = AssessmentProblemTestCasesResponse['data']
export type AssessmentProblemPracticeTestCasesResponse = Schemas['AssessmentProblemPracticeTestCasesResponse']
export type AssessmentProblemPracticeTestCases = AssessmentProblemPracticeTestCasesResponse['data']
export type CreateOjPracticeRunRequest = Schemas['CreateOjPracticeRunRequest']
export type OjPracticeTestCaseResult = Schemas['OjPracticeTestCaseResult']
export type OjPracticeRunResult = Schemas['OjPracticeRunResult']
export type OjPracticeRunResponse = Schemas['OjPracticeRunResponse']
export type UpdateAssessmentProblemRequest = Schemas['UpdateAssessmentProblemRequest']
export type CreateAssessmentSubmissionRequest = Schemas['CreateAssessmentSubmissionRequest']
export type AssessmentSubmissionCode =
  Schemas['AssessmentSubmissionCodeResponse']['data']
export type CreateAssessmentReviewRequest = Schemas['CreateAssessmentReviewRequest']

export type AssessmentLanguage = Assessment['allowedLanguages'][number]
export type AssessmentStatus = Assessment['status']
export type AssessmentProblemStatus = AssessmentProblem['status']
export type AssessmentSubmissionStatus = AssessmentSubmission['status']
export type OjVerdict = OjJudgeResult['verdict']
export type AiEvaluationStatus = AiEvaluation['status']
export type AssessmentReviewStatus = AssessmentReview['status']
export type AssessmentScoreSource = AssessmentResult['scoreSource']
export type AssessmentResultStatus = AssessmentResult['resultStatus']

// ---------------------------------------------------------------------------
// 角色数据看板
// ---------------------------------------------------------------------------

export type DashboardRange = '7d' | '30d'
export type AdminDashboardOverview = Schemas['AdminDashboardOverview']
export type LeaderDashboardOverview = Schemas['LeaderDashboardOverview']
export type StudentDashboardOverview = Schemas['StudentDashboardOverview']
export type DashboardTrendPoint = Schemas['DashboardTrendPoint']
export type DirectionDashboardRow = Schemas['DirectionDashboardRow']
export type StudentDashboardRow = Schemas['StudentDashboardRow']
export type AttentionItem = Schemas['AttentionItem']
export type RecentActivityItem = Schemas['RecentActivityItem']
export type StudentProgress = Schemas['StudentProgress']
export type StudentReportSummary = Schemas['StudentReportSummary']
export type StudentAssessmentSummary = Schemas['StudentAssessmentSummary']
export type PointEntrySummary = Schemas['PointEntrySummary']
export type StudentRankingPreview = Schemas['StudentRankingPreview']
export type AdminDashboard = Schemas['AdminDashboard']
export type LeaderDashboard = Schemas['LeaderDashboard']
export type StudentDashboard = Schemas['StudentDashboard']

// ---------------------------------------------------------------------------
// 枚举（供 UI 映射与筛选使用）
// ---------------------------------------------------------------------------

export type MembershipStatus = NonNullable<Member['membershipStatus']>
export type AccountStatus = Member['accountStatus']

/**
 * 全部业务错误码。契约中 ErrorResponse.code 是一个扁平枚举，
 * 不按 HTTP 状态码区分，且后端只实现了其中一部分 —— 因此客户端必须
 * 通用地解析 code 字符串，不要 switch 硬编码枚举。
 */
export type ErrorCode = ApiErrorBody['code']
