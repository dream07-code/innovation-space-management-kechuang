/**
 * OJ 考核接口。
 *
 * 契约：docs/api/openapi.yaml 的 OJ 考核路径。此模块只访问标准化 DTO，
 * 不读取第三方 OJ 私有字段、完整代码、测试数据、原始 AI 响应或任何凭据。
 * 所有写操作都必须调用方生成并复用 Idempotency-Key。
 */
import { API_PREFIX, get, patch, post, postWithoutIdempotency } from '../client'
import type {
  Assessment,
  AssessmentCommandRequest,
  AssessmentProblem,
  AssessmentProblemPracticeTestCases,
  AssessmentProblemTestCases,
  AssessmentMemberScore,
  AssessmentResult,
  AssessmentReview,
  AssessmentSubmission,
  AssessmentSubmissionCode,
  CreateAssessmentProblemRequest,
  CreateOjPracticeRunRequest,
  OjPracticeRunResult,
  AssessmentProblemImportResponse,
  ImportAssessmentProblemsRequest,
  CreateAssessmentRequest,
  CreateAssessmentReviewRequest,
  CreateAssessmentSubmissionRequest,
  PageData,
  UpdateAssessmentProblemRequest,
  UpdateAssessmentRequest,
} from '../types'

const ASSESSMENTS = `${API_PREFIX}/assessments`
const PROBLEMS = `${API_PREFIX}/assessment-problems`
const SUBMISSIONS = `${API_PREFIX}/assessment-submissions`

// ---------------------------------------------------------------------------
// 考核
// ---------------------------------------------------------------------------

export async function listAssessments(): Promise<PageData<Assessment>> {
  return get<PageData<Assessment>>(ASSESSMENTS)
}

export async function createAssessment(
  payload: CreateAssessmentRequest,
  idempotencyKey: string,
): Promise<Assessment> {
  return post<Assessment>(ASSESSMENTS, payload, { idempotencyKey })
}

export async function getAssessment(assessmentId: string): Promise<Assessment> {
  return get<Assessment>(`${ASSESSMENTS}/${assessmentId}`)
}

export async function updateAssessment(
  assessmentId: string,
  payload: UpdateAssessmentRequest,
): Promise<Assessment> {
  return patch<Assessment>(`${ASSESSMENTS}/${assessmentId}`, payload)
}

export async function publishAssessment(
  assessmentId: string,
  payload: AssessmentCommandRequest,
  idempotencyKey: string,
): Promise<Assessment> {
  return post<Assessment>(`${ASSESSMENTS}/${assessmentId}/publish`, payload, { idempotencyKey })
}

export async function closeAssessment(
  assessmentId: string,
  payload: AssessmentCommandRequest,
  idempotencyKey: string,
): Promise<Assessment> {
  return post<Assessment>(`${ASSESSMENTS}/${assessmentId}/close`, payload, { idempotencyKey })
}

// ---------------------------------------------------------------------------
// 题目
// ---------------------------------------------------------------------------

export async function listAssessmentProblems(): Promise<PageData<AssessmentProblem>> {
  return get<PageData<AssessmentProblem>>(PROBLEMS)
}

export async function createAssessmentProblem(
  payload: CreateAssessmentProblemRequest,
  idempotencyKey: string,
): Promise<AssessmentProblem> {
  return post<AssessmentProblem>(PROBLEMS, payload, { idempotencyKey })
}

export async function getAssessmentProblem(problemId: string): Promise<AssessmentProblem> {
  return get<AssessmentProblem>(`${PROBLEMS}/${problemId}`)
}

export async function importAssessmentProblems(
  payload: ImportAssessmentProblemsRequest,
  idempotencyKey: string,
): Promise<AssessmentProblemImportResponse> {
  return post<AssessmentProblemImportResponse>(`${PROBLEMS}/import`, payload, { idempotencyKey })
}

export async function getPracticeTestCases(
  problemId: string,
): Promise<AssessmentProblemPracticeTestCases> {
  return get<AssessmentProblemPracticeTestCases>(`${PROBLEMS}/${problemId}/practice-test-cases`)
}

export async function runPractice(
  problemId: string,
  payload: CreateOjPracticeRunRequest,
  idempotencyKey: string,
): Promise<OjPracticeRunResult> {
  return post<OjPracticeRunResult>(`${PROBLEMS}/${problemId}/practice-run`, payload, {
    idempotencyKey,
  })
}

/**
 * 正式考核做题时的试运行：随机抽 1 个正式考核测试点执行当前代码。
 *
 * 每次结果不同、不产生提交记录，属于只读试跑，因此契约没有声明 Idempotency-Key；
 * 后端按成员每分钟限 10 次。
 */
export async function runTrial(
  problemId: string,
  payload: CreateOjPracticeRunRequest,
): Promise<OjPracticeRunResult> {
  return postWithoutIdempotency<OjPracticeRunResult>(`${PROBLEMS}/${problemId}/trial-run`, payload)
}

export async function getAssessmentProblemTestCases(
  problemId: string,
): Promise<AssessmentProblemTestCases> {
  return get<AssessmentProblemTestCases>(`${PROBLEMS}/${problemId}/test-cases`)
}

export async function updateAssessmentProblem(
  problemId: string,
  payload: UpdateAssessmentProblemRequest,
): Promise<AssessmentProblem> {
  return patch<AssessmentProblem>(`${PROBLEMS}/${problemId}`, payload)
}

export async function retireAssessmentProblem(
  problemId: string,
  payload: AssessmentCommandRequest,
  idempotencyKey: string,
): Promise<AssessmentProblem> {
  return post<AssessmentProblem>(`${PROBLEMS}/${problemId}/retire`, payload, { idempotencyKey })
}

/**
 * 物理删除未被引用的题目；被考核条目引用或已有提交记录时后端返回
 * 409 PROBLEM_IN_USE，此时应改用退役接口。
 */
export async function deleteAssessmentProblem(
  problemId: string,
  payload: AssessmentCommandRequest,
  idempotencyKey: string,
): Promise<null> {
  return post<null>(`${PROBLEMS}/${problemId}/delete`, payload, { idempotencyKey })
}

// ---------------------------------------------------------------------------
// 提交、按测试点计分和人工批阅
// ---------------------------------------------------------------------------

export async function createAssessmentSubmission(
  assessmentId: string,
  payload: CreateAssessmentSubmissionRequest,
  idempotencyKey: string,
): Promise<AssessmentSubmission> {
  return post<AssessmentSubmission>(`${ASSESSMENTS}/${assessmentId}/submissions`, payload, {
    idempotencyKey,
  })
}

export interface AssessmentSubmissionListQuery {
  assessmentId?: string
  problemId?: string
  memberId?: string
  status?: AssessmentSubmission['status']
}

/** 学生调用时服务端强制只返回本人提交。 */
export async function listAssessmentSubmissions(
  query: AssessmentSubmissionListQuery = {},
): Promise<PageData<AssessmentSubmission>> {
  return get<PageData<AssessmentSubmission>>(SUBMISSIONS, { params: query })
}

/** 读取提交代码；学生读本人代码不写审计，读他人代码会留 SUBMISSION_CODE_VIEWED 审计。 */
export async function getAssessmentSubmissionCode(
  submissionId: string,
): Promise<{ submissionId: string; language: string; code: string }> {
  return get<{ submissionId: string; language: string; code: string }>(
    `${SUBMISSIONS}/${submissionId}/code`,
  )
}

export async function getAssessmentSubmission(
  submissionId: string,
): Promise<AssessmentSubmission> {
  return get<AssessmentSubmission>(`${SUBMISSIONS}/${submissionId}`)
}

export async function listAssessmentReviews(
  submissionId: string,
): Promise<PageData<AssessmentReview>> {
  return get<PageData<AssessmentReview>>(`${SUBMISSIONS}/${submissionId}/reviews`)
}

export async function createAssessmentReview(
  submissionId: string,
  payload: CreateAssessmentReviewRequest,
  idempotencyKey: string,
): Promise<AssessmentReview> {
  return post<AssessmentReview>(`${SUBMISSIONS}/${submissionId}/reviews`, payload, {
    idempotencyKey,
  })
}

export interface AssessmentResultQuery {
  page?: number
  size?: number
  /** 只看某个成员；仅管理员与方向负责人可用，学生始终只有自己的数据。 */
  memberId?: string
}

/** 按提交平铺的成绩，一次提交一行。 */
export async function listAssessmentResults(
  assessmentId: string,
  query: AssessmentResultQuery = {},
): Promise<PageData<AssessmentResult>> {
  return get<PageData<AssessmentResult>>(`${ASSESSMENTS}/${assessmentId}/results`, {
    params: query,
  })
}

/** 按成员汇总的成绩，每人一行，带总分与平均分。 */
export async function listAssessmentMemberScores(
  assessmentId: string,
  query: { page?: number; size?: number } = {},
): Promise<PageData<AssessmentMemberScore>> {
  return get<PageData<AssessmentMemberScore>>(
    `${ASSESSMENTS}/${assessmentId}/member-scores`,
    { params: query },
  )
}

/** 读取提交的源代码：管理员、方向负责人或提交者本人可见。 */
export async function getSubmissionCode(
  submissionId: string,
): Promise<AssessmentSubmissionCode> {
  return get<AssessmentSubmissionCode>(`${SUBMISSIONS}/${submissionId}/code`)
}
