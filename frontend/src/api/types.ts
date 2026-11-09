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

export type ReportImage = Schemas['ReportImage']
export type ReportImageUpload = Schemas['ReportImageUpload']
/** 图片跟随的正文段落；end 表示统一放在文末。 */
export type ReportImageAnchor = ReportImage['anchor']

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
/**
 * V12 起考核以混合条目（程序题+客观题）挂题；契约重生成前先在此叠加
 * items 字段，旧 problemIds 兼容保留（见下方 AssessmentItem）。
 */
export type Assessment = Schemas['Assessment'] & {
  items?: AssessmentItem[]
  /** 成绩是否已由负责人/管理员发布；未发布时学生看不到分数与明细。 */
  resultsPublished?: boolean
  resultsPublishedAt?: string | null
}
export type AssessmentProblem = Schemas['AssessmentProblem']
export type AssessmentSubmission = Schemas['AssessmentSubmission']
export type OjJudgeResult = Schemas['OjJudgeResult']
export type OjSampleCase = Schemas['OjSampleCase']
export type OjTestCaseResult = Schemas['OjTestCaseResult']
export type AssessmentReview = Schemas['AssessmentReview']
export type AssessmentResult = Schemas['AssessmentResult']
export type CreateAssessmentRequest = Schemas['CreateAssessmentRequest']
/** 挂题载荷改为混合条目数组（按数组顺序挂题），契约 V12 起替代 problemIds。 */
export type UpdateAssessmentRequest = Schemas['UpdateAssessmentRequest'] & {
  items?: Omit<AssessmentItem, 'position'>[]
}
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
/** AI 批改报告：判题结果 + 错因解析 + 改进建议 + 推荐下一题。 */
export type SubmissionReport = Schemas['SubmissionReportResponse']['data']
/** AI 助教的一轮回答；available=false 时用 error 展示原因，不渲染占位回答。 */
export type SubmissionTutorReply = Schemas['SubmissionTutorResponse']['data']
/** 助教请求体：本次问题 + 前端持有的历史对话（服务端不保存会话）。 */
export type SubmissionTutorRequest = Schemas['SubmissionTutorRequest']
export type SubmissionTutorTurn = Schemas['SubmissionTutorTurn']
/** 通用 AI 助教（不绑定提交）：可选题目上下文 + 历史对话，回答只有文本。 */
export type TutorChatReply = Schemas['TutorChatResponse']['data']
export type TutorChatRequest = Schemas['TutorChatRequest']
export type CreateAssessmentReviewRequest = Schemas['CreateAssessmentReviewRequest']

export type AssessmentLanguage = Assessment['allowedLanguages'][number]
export type AssessmentStatus = Assessment['status']
export type AssessmentProblemStatus = AssessmentProblem['status']
export type AssessmentSubmissionStatus = AssessmentSubmission['status']
export type OjVerdict = OjJudgeResult['verdict']
export type AssessmentReviewStatus = AssessmentReview['status']
export type AssessmentScoreSource = AssessmentResult['scoreSource']
export type AssessmentResultStatus = AssessmentResult['resultStatus']
/**
 * V12 起按分值口径汇总：itemCount/scoreRate 替代 problemCount/averageScore，
 * 契约重生成前先叠加（旧字段保留兼容，页面取新字段、缺省回退旧字段）。
 */
export type AssessmentMemberScore = Schemas['AssessmentMemberScore'] & {
  itemCount?: number
  scoreRate?: number
}
export type AssessmentMemberScorePage = Schemas['AssessmentMemberScorePage']

// ---------------------------------------------------------------------------
// 客观题与混合试卷（第三阶段）
//
// 契约 schema 尚未随 openapi.yaml 重新生成（后端任务并行），以下形状按
// docs/superpowers/specs/2026-10-24-mixed-exam-quiz-design.md §5/§6 手工钉死，
// 契约落地后应迁回 Schemas[...] 别名。
// ---------------------------------------------------------------------------

/** 客观题题型：单选/多选/判断/填空。 */
export type QuizQuestionType = 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'TRUE_FALSE' | 'FILL_BLANK'

/**
 * 客观题（题库管理端视角，可见 answer）。answer 形态随题型：
 * 单选一个字母、多选字母数组、判断布尔、填空备选答案数组。
 */
export interface QuizQuestion {
  id: string
  type: QuizQuestionType
  stem: string
  /** 选择题选项数组（下标对应字母序）；判断/填空为 null。 */
  options: string[] | null
  answer: string | string[] | boolean
  analysis: string | null
  tags: string[]
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  createdAt: string
  updatedAt: string
}

/** 创建客观题；编辑时禁止改 type，因此 update 复用 Partial。 */
export interface CreateQuizQuestionRequest {
  type: QuizQuestionType
  stem: string
  options?: string[] | null
  answer: string | string[] | boolean
  analysis?: string | null
  tags?: string[]
}

/** 考核条目：position 全考核统一排序，PROGRAM 引用程序题、QUIZ 引用客观题。 */
export interface AssessmentItem {
  position: number
  type: 'PROGRAM' | 'QUIZ'
  /** PROGRAM 时为 assessment_problem id，QUIZ 时为 quiz_question id。 */
  refId: string
  /** 该题分值，1–100。 */
  score: number
}

/** 学生试卷条目：客观题条目绝不含 answer/analysis（服务端契约保证）。 */
export interface PaperItem {
  position: number
  type: AssessmentItem['type']
  refId: string
  score: number
  /** QUIZ 条目：题型细分与题干、选项、标签。 */
  quizType?: QuizQuestionType | null
  stem?: string | null
  options?: string[] | null
  tags?: string[] | null
  /** PROGRAM 条目：题目元信息。 */
  problemTitle?: string | null
  difficulty?: 'EASY' | 'MEDIUM' | 'HARD' | null
}

/** 学生试卷：PUBLISHED 且本方向才可读。 */
export interface AssessmentPaper {
  assessmentId: string
  title: string
  startAt: string
  endAt: string
  items: PaperItem[]
  /** 本人答题卡草稿；从未暂存过时为 null。 */
  mySheet: { status: 'DRAFT' | 'SUBMITTED'; answers: AnswerDraft | null } | null
}

/** 草稿答案：position（字符串键）到作答值的映射。 */
export type AnswerDraft = Record<string, unknown>

/** 逐生答题卡行（负责人/管理员视角）；带 memberId 下钻时附逐题明细。 */
export interface AnswerSheetSummary {
  memberId: string
  memberName: string
  status: 'DRAFT' | 'SUBMITTED'
  answeredCount: number
  /** 交卷判分后写入；未判分为 null。 */
  totalScore: number | null
  submittedAt: string | null
  /** 下钻明细：题号/学生答案/正确答案/对错/得分/解析（交卷后立即可见）。 */
  items?: Array<{
    position: number
    answer: unknown
    correct: boolean
    awarded: number
    correctAnswer: unknown
    analysis: string | null
  }>
}

/**
 * 答题卡明细（本人视角）。考核 CLOSED/ARCHIVED 前服务端剥离判分字段，
 * items 为 null；结束后含逐题对错、正确答案与解析。
 */
export interface AnswerSheetDetail {
  status: 'DRAFT' | 'SUBMITTED'
  /** 成绩是否已发布；false 时 totalScore 与 items 均为 null。 */
  resultsPublished?: boolean
  answeredCount: number
  totalScore: number | null
  submittedAt: string | null
  items:
    | {
        position: number
        answer: unknown
        correct: boolean
        awarded: number
        correctAnswer: unknown
        analysis: string | null
        /** 阅卷评语，学生可见。 */
        reviewComment?: string | null
        /** 以下仅教师视图返回。 */
        autoAwarded?: number
        manualScore?: number | null
        reviewed?: boolean
        reviewedAt?: string | null
      }[]
    | null
}

// ---------------------------------------------------------------------------
// 填空人工阅卷
// ---------------------------------------------------------------------------

export type QuizReviewFilter = 'all' | 'pending' | 'auto_zero' | 'manual'

export interface QuizReviewRow {
  sheetId: string
  memberId: string
  memberName: string
  studentNo: string
  answer: string | null
  answered: boolean
  autoAwarded: number
  awarded: number
  manualScore: number | null
  reviewed: boolean
  comment: string | null
  version: number | null
  reviewedAt: string | null
}

export interface QuizReviewQuestion {
  position: number
  questionId: string
  stem: string
  score: number
  referenceAnswers: string[] | null
  analysis: string | null
}

export interface QuizReviewPage {
  assessment: {
    id: string
    title: string
    status: string
    directionId: string
    resultsPublished: boolean
  }
  question: QuizReviewQuestion
  progress: { graded: number; total: number }
  items: QuizReviewRow[]
  page: number
  size: number
  total: number
}

export interface QuizReviewSummary {
  assessment: { id: string; title: string; status: string; resultsPublished: boolean }
  /** 本场全部填空题（含完整题干与参考答案），随进度一次下发。 */
  questions: QuizReviewQuestion[]
  totalItems: number
  gradedItems: number
  pendingItems: number
  submittedSheets: number
  resultsPublished: boolean
  canPublish: boolean
  pointsEnabled: boolean
}

export interface GradeQuizItem {
  sheetId: string
  position: number
  score: number
  comment: string
  version: number
}

export interface GradeQuizScoresRequest {
  items: GradeQuizItem[]
}

export interface GradeQuizScoresResponse {
  updated: number
  sheets: Array<{ sheetId: string; totalScore: number }>
}

export interface PublishAssessmentResultsResponse {
  publishedAt: string
  publisher: string
  settled: boolean
}

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
export type AnnouncementItem = Schemas['AnnouncementItem']
export type CreateAnnouncementRequest = Schemas['CreateAnnouncementRequest']

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
