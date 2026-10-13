/**
 * OJ 考核接口。
 *
 * 契约：docs/api/openapi.yaml 的 OJ 考核路径。此模块只访问标准化 DTO，
 * 不读取第三方 OJ 私有字段、完整代码、测试数据、原始 AI 响应或任何凭据。
 * 所有写操作都必须调用方生成并复用 Idempotency-Key。
 */
import { API_PREFIX, get, patch, post } from '../client'
import type {
  AiEvaluation,
  Assessment,
  AssessmentCommandRequest,
  AssessmentProblem,
  AssessmentProblemPracticeTestCases,
  AssessmentProblemTestCases,
  AssessmentResult,
  AssessmentReview,
  AssessmentSubmission,
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

// ---------------------------------------------------------------------------
// 提交、AI 评分和人工批阅
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

export async function listAssessmentSubmissions(): Promise<PageData<AssessmentSubmission>> {
  return get<PageData<AssessmentSubmission>>(SUBMISSIONS)
}

export async function getAssessmentSubmission(
  submissionId: string,
): Promise<AssessmentSubmission> {
  return get<AssessmentSubmission>(`${SUBMISSIONS}/${submissionId}`)
}

export async function getAiEvaluation(submissionId: string): Promise<AiEvaluation> {
  return get<AiEvaluation>(`${SUBMISSIONS}/${submissionId}/ai-evaluation`)
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

export async function listAssessmentResults(
  assessmentId: string,
): Promise<PageData<AssessmentResult>> {
  return get<PageData<AssessmentResult>>(`${ASSESSMENTS}/${assessmentId}/results`)
}
