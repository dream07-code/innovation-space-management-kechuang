/**
 * OJ 考核 Mock 数据。
 *
 * 只保存普通 DTO 与安全摘要；不保存完整代码、私有测试数据、OJ 原始异常、
 * AI 原始响应或任何第三方凭据。提交代码只在请求处理期间参与 hash 计算。
 */
import type {
  AiEvaluation,
  Assessment,
  AssessmentProblem,
  AssessmentResult,
  AssessmentReview,
  AssessmentSubmission,
  AssessmentProblemTestCaseInput,
  CreateAssessmentProblemRequest,
  CreateAssessmentRequest,
  CreateAssessmentReviewRequest,
  CreateAssessmentSubmissionRequest,
  OjJudgeResult,
  OjTestCaseResult,
  ProblemExample,
  UpdateAssessmentProblemRequest,
  UpdateAssessmentRequest,
} from '@/api/types'
import { MEMBERS, findMember } from './data'
import { DIRECTIONS, registerResetHook } from './state'

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

export function ojNowIso(): string {
  const d = new Date(Date.now() + 8 * 3600_000)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}+08:00`
}

/** 仅用于 Mock 的稳定 hash，不落盘、不返回原文。 */
export async function codeHashOf(code: string): Promise<string> {
  const bytes = new TextEncoder().encode(code)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function mockTestCaseResults(
  total: number,
  passed: number,
  verdict: OjTestCaseResult['verdict'] = 'ACCEPTED',
  safeError: string | null = null,
): OjTestCaseResult[] {
  return Array.from({ length: total }, (_, index) => ({
    position: index + 1,
    verdict: index < passed ? 'ACCEPTED' : verdict,
    timeMs: index < passed ? 12 : 10,
    memoryKb: index < passed ? 2048 : 1024,
    safeError: index < passed ? null : safeError,
  }))
}

const example = (input: string, output: string, explanation?: string): ProblemExample => ({
  input,
  output,
  explanation: explanation ?? null,
})

const INITIAL_ASSESSMENTS: Assessment[] = [
  {
    id: '9001',
    title: '十月算法基础考核',
    description: '完成两道基础算法题，提交代码后由 OJ 判定并进入独立质量评分。',
    directionId: '1',
    directionName: 'AI 全栈',
    status: 'PUBLISHED',
    startAt: '2026-10-10T00:00:00+08:00',
    endAt: '2026-10-31T23:59:59+08:00',
    allowedLanguages: ['java', 'python', 'cpp', 'javascript'],
    problemIds: ['9101', '9102'],
    version: 2,
    createdAt: '2026-10-09T09:00:00+08:00',
    updatedAt: '2026-10-10T09:00:00+08:00',
  },
  {
    id: '9002',
    title: '鸿蒙方向摸底考核',
    description: '方向负责人草稿，发布前可调整题目和时间。',
    directionId: '2',
    directionName: '鸿蒙',
    status: 'DRAFT',
    startAt: '2026-10-15T00:00:00+08:00',
    endAt: '2026-10-25T23:59:59+08:00',
    allowedLanguages: ['java', 'javascript'],
    problemIds: ['9103'],
    version: 1,
    createdAt: '2026-10-11T09:00:00+08:00',
    updatedAt: '2026-10-11T09:00:00+08:00',
  },
  {
    id: '9003',
    title: '九月结项考核',
    description: '已关闭的历史考核，结果仍可查看和批阅。',
    directionId: '1',
    directionName: 'AI 全栈',
    status: 'CLOSED',
    startAt: '2026-09-01T00:00:00+08:00',
    endAt: '2026-09-10T23:59:59+08:00',
    allowedLanguages: ['python'],
    problemIds: ['9102'],
    version: 3,
    createdAt: '2026-08-28T09:00:00+08:00',
    updatedAt: '2026-09-10T23:59:59+08:00',
  },
]

const INITIAL_PROBLEMS: AssessmentProblem[] = [
  {
    id: '9101',
    title: '两数之和',
    summary: '找出数组中和为目标值的两个下标。',
    description: '给定一个整数数组和目标值，返回任意一组和为目标值的下标。',
    inputFormat: '第一行输入 n 和 target，第二行输入 n 个整数。',
    outputFormat: '输出两个从 0 开始的下标，用空格分隔。',
    examples: [example('4 9\\n2 7 11 15', '0 1', '2 + 7 = 9')],
    difficulty: 'EASY',
    tags: ['数组', '哈希'],
    timeLimitMs: 1000,
    memoryLimitKb: 131072,
    allowedLanguages: ['java', 'python', 'cpp', 'javascript'],
    judgeMode: 'STDIO',
    status: 'PUBLISHED',
    version: 1,
    createdAt: '2026-10-09T09:00:00+08:00',
    updatedAt: '2026-10-09T09:00:00+08:00',
  },
  {
    id: '9102',
    title: '括号匹配',
    summary: '判断括号字符串是否有效。',
    description: '给定只包含圆括号、方括号和花括号的字符串，判断括号是否正确闭合。',
    inputFormat: '一行括号字符串。',
    outputFormat: '有效输出 true，否则输出 false。',
    examples: [example('([]{})', 'true', '所有括号均正确闭合')],
    difficulty: 'MEDIUM',
    tags: ['栈', '字符串'],
    timeLimitMs: 1000,
    memoryLimitKb: 131072,
    allowedLanguages: ['java', 'python', 'cpp', 'javascript'],
    judgeMode: 'STDIO',
    status: 'PUBLISHED',
    version: 2,
    createdAt: '2026-10-09T09:10:00+08:00',
    updatedAt: '2026-10-10T09:10:00+08:00',
  },
  {
    id: '9103',
    title: '日志分组',
    summary: '按首字母和数字尾缀整理日志。',
    description: '给定日志数组，将字母日志排在数字日志前，并保持相对顺序。',
    inputFormat: '第一行输入 n，随后 n 行日志。',
    outputFormat: '输出整理后的日志，每行一条。',
    examples: [example('2\\na1 act car\\ng1 own kit tow', 'a1 act car\\ng1 own kit tow')],
    difficulty: 'HARD',
    tags: ['排序', '字符串'],
    timeLimitMs: 1500,
    memoryLimitKb: 131072,
    allowedLanguages: ['java', 'javascript'],
    judgeMode: 'STDIO',
    status: 'DRAFT',
    version: 1,
    createdAt: '2026-10-11T09:00:00+08:00',
    updatedAt: '2026-10-11T09:00:00+08:00',
  },
]

/** 模拟后端 JOIN member 的结果：提交/结果 DTO 同时带上姓名与学号。 */
function memberFields(memberId: string): Pick<AssessmentSubmission, 'memberName' | 'memberStudentNo'> {
  const member = findMember(memberId)
  return { memberName: member?.name ?? null, memberStudentNo: member?.studentNo ?? null }
}

const INITIAL_SUBMISSIONS: AssessmentSubmission[] = [
  {
    id: '9201',
    assessmentId: '9001',
    problemId: '9101',
    memberId: '201',
    ...memberFields('201'),
    language: 'python',
    status: 'JUDGED',
    judgeResult: {
      verdict: 'ACCEPTED',
      passedTestCases: 3,
      totalTestCases: 3,
      timeMs: 42,
      memoryKb: 18432,
      safeError: null,
      testCaseResults: mockTestCaseResults(3, 3),
    },
    codeHash: 'mock-safe-hash-9201',
    version: 1,
    submittedAt: '2026-10-11T10:00:00+08:00',
    judgedAt: '2026-10-11T10:00:02+08:00',
  },
  {
    id: '9202',
    assessmentId: '9001',
    problemId: '9102',
    memberId: '201',
    ...memberFields('201'),
    language: 'java',
    status: 'JUDGED',
    judgeResult: {
      verdict: 'WRONG_ANSWER',
      passedTestCases: 1,
      totalTestCases: 3,
      timeMs: 58,
      memoryKb: 22118,
      safeError: 'answer mismatch on hidden case',
      testCaseResults: mockTestCaseResults(3, 1, 'WRONG_ANSWER', 'answer mismatch on hidden case'),
    },
    codeHash: 'mock-safe-hash-9202',
    version: 2,
    submittedAt: '2026-10-11T10:05:00+08:00',
    judgedAt: '2026-10-11T10:05:03+08:00',
  },
  {
    id: '9203',
    assessmentId: '9001',
    problemId: '9101',
    memberId: '206',
    ...memberFields('206'),
    language: 'cpp',
    status: 'JUDGED',
    judgeResult: {
      verdict: 'ACCEPTED',
      passedTestCases: 3,
      totalTestCases: 3,
      timeMs: 21,
      memoryKb: 16384,
      safeError: null,
      testCaseResults: mockTestCaseResults(3, 3),
    },
    codeHash: 'mock-safe-hash-9203',
    version: 1,
    submittedAt: '2026-10-11T11:00:00+08:00',
    judgedAt: '2026-10-11T11:00:02+08:00',
  },
  {
    id: '9204',
    assessmentId: '9003',
    problemId: '9102',
    memberId: '206',
    ...memberFields('206'),
    language: 'python',
    status: 'NEEDS_REVIEW',
    judgeResult: {
      verdict: 'INTERNAL_ERROR',
      passedTestCases: 0,
      totalTestCases: 3,
      timeMs: 0,
      memoryKb: 0,
      safeError: 'judge service unavailable',
      testCaseResults: mockTestCaseResults(3, 0, 'INTERNAL_ERROR', 'judge service unavailable'),
    },
    codeHash: 'mock-safe-hash-9204',
    version: 3,
    submittedAt: '2026-09-05T10:00:00+08:00',
    judgedAt: '2026-09-05T10:00:04+08:00',
  },
]

const INITIAL_AI_EVALUATIONS: AiEvaluation[] = [
  {
    id: '9301',
    submissionId: '9201',
    status: 'COMPLETE',
    score: 82,
    reason: '实现正确且结构清晰，建议补充边界说明。',
    model: 'mock-quality-rater',
    rubricVersion: 'mock-rubric-1',
    createdAt: '2026-10-11T10:01:00+08:00',
  },
  {
    id: '9302',
    submissionId: '9202',
    status: 'PENDING',
    score: null,
    reason: null,
    model: null,
    rubricVersion: 'mock-rubric-1',
    createdAt: '2026-10-11T10:06:00+08:00',
  },
  {
    id: '9303',
    submissionId: '9203',
    status: 'COMPLETE',
    score: 91,
    reason: '算法正确、复杂度合适，代码风格稳定。',
    model: 'mock-quality-rater',
    rubricVersion: 'mock-rubric-1',
    createdAt: '2026-10-11T11:01:00+08:00',
  },
  {
    id: '9304',
    submissionId: '9204',
    status: 'FAILED',
    score: null,
    reason: '评分服务暂时不可用，等待人工复核。',
    model: null,
    rubricVersion: 'mock-rubric-1',
    createdAt: '2026-09-05T10:01:00+08:00',
  },
]

const INITIAL_REVIEWS: AssessmentReview[] = [
  {
    id: '9401',
    submissionId: '9201',
    memberId: '201',
    memberName: '示例同学',
    memberStudentNo: 'MOCK20260001',
    reviewerId: '102',
    reviewerName: '示例负责人',
    score: 90,
    comment: '人工复核通过，结果以本评分为准。',
    status: 'ACTIVE',
    version: 1,
    createdAt: '2026-10-11T10:10:00+08:00',
    updatedAt: '2026-10-11T10:10:00+08:00',
  },
]

export const OJ_ASSESSMENTS: Assessment[] = INITIAL_ASSESSMENTS.map((item) => ({ ...item }))
export const OJ_PROBLEMS: AssessmentProblem[] = INITIAL_PROBLEMS.map((item) => ({ ...item }))
export const OJ_SUBMISSIONS: AssessmentSubmission[] = INITIAL_SUBMISSIONS.map((item) => ({ ...item }))
export const OJ_AI_EVALUATIONS: AiEvaluation[] = INITIAL_AI_EVALUATIONS.map((item) => ({ ...item }))
export const OJ_REVIEWS: AssessmentReview[] = INITIAL_REVIEWS.map((item) => ({ ...item }))

export interface StoredProblemTestCases {
  assessmentTestCases: AssessmentProblemTestCaseInput[]
  practiceTestCases: AssessmentProblemTestCaseInput[]
}

export const OJ_PROBLEM_TEST_CASES: Record<string, StoredProblemTestCases> = Object.fromEntries(
  INITIAL_PROBLEMS.map((problem) => [
    problem.id,
    {
      assessmentTestCases: [{ input: `hidden-${problem.id}`, expectedOutput: `accepted-${problem.id}` }],
      practiceTestCases: [{ input: `practice-${problem.id}`, expectedOutput: `practice-${problem.id}` }],
    },
  ]),
)

export function findProblemTestCases(problemId: string): StoredProblemTestCases | undefined {
  return OJ_PROBLEM_TEST_CASES[problemId]
}

export function resetOjData(): void {
  OJ_ASSESSMENTS.splice(0, OJ_ASSESSMENTS.length, ...INITIAL_ASSESSMENTS.map((item) => ({ ...item })))
  OJ_PROBLEMS.splice(0, OJ_PROBLEMS.length, ...INITIAL_PROBLEMS.map((item) => ({ ...item })))
  OJ_SUBMISSIONS.splice(0, OJ_SUBMISSIONS.length, ...INITIAL_SUBMISSIONS.map((item) => ({ ...item })))
  OJ_AI_EVALUATIONS.splice(0, OJ_AI_EVALUATIONS.length, ...INITIAL_AI_EVALUATIONS.map((item) => ({ ...item })))
  OJ_REVIEWS.splice(0, OJ_REVIEWS.length, ...INITIAL_REVIEWS.map((item) => ({ ...item })))
  for (const key of Object.keys(OJ_PROBLEM_TEST_CASES)) delete OJ_PROBLEM_TEST_CASES[key]
  for (const problem of INITIAL_PROBLEMS) {
    OJ_PROBLEM_TEST_CASES[problem.id] = {
      assessmentTestCases: [{ input: `hidden-${problem.id}`, expectedOutput: `accepted-${problem.id}` }],
      practiceTestCases: [{ input: `practice-${problem.id}`, expectedOutput: `practice-${problem.id}` }],
    }
  }
}

registerResetHook(resetOjData)

export function findAssessment(id: string): Assessment | undefined {
  return OJ_ASSESSMENTS.find((item) => item.id === id)
}

export function findProblem(id: string): AssessmentProblem | undefined {
  return OJ_PROBLEMS.find((item) => item.id === id)
}

export function findSubmission(id: string): AssessmentSubmission | undefined {
  return OJ_SUBMISSIONS.find((item) => item.id === id)
}

export function activeReviewOf(submissionId: string): AssessmentReview | undefined {
  return OJ_REVIEWS.find((item) => item.submissionId === submissionId && item.status === 'ACTIVE')
}

export function buildResult(submission: AssessmentSubmission): AssessmentResult {
  const ai = OJ_AI_EVALUATIONS.find((item) => item.submissionId === submission.id)
  const review = activeReviewOf(submission.id)
  const judge = submission.judgeResult
  const humanScore = review?.score ?? null
  const aiScore = ai?.status === 'COMPLETE' ? ai.score : null
  // 与后端一致：只有 AI 评分调用失败才会出现“暂不可用”，判题失败不算。
  const scoreSource = humanScore !== null
    ? 'HUMAN'
    : aiScore !== null
      ? 'AI'
      : ai?.status === 'FAILED'
        ? 'UNAVAILABLE'
        : 'PENDING'
  return {
    assessmentId: submission.assessmentId,
    submissionId: submission.id,
    problemId: submission.problemId,
    memberId: submission.memberId,
    ...memberFields(submission.memberId),
    technicalVerdict: judge?.verdict ?? 'INTERNAL_ERROR',
    passedTestCases: judge?.passedTestCases ?? 0,
    totalTestCases: judge?.totalTestCases ?? 0,
    timeMs: judge?.timeMs ?? 0,
    memoryKb: judge?.memoryKb ?? 0,
    aiScore,
    humanScore,
    finalScore: humanScore ?? aiScore,
    scoreSource,
    aiStatus: ai?.status ?? null,
    aiReason: ai?.reason ?? null,
    resultStatus: submission.status === 'JUDGED' && (humanScore !== null || aiScore !== null)
      ? 'COMPLETE'
      : submission.status === 'NEEDS_REVIEW' || submission.status === 'FAILED' || ai?.status === 'FAILED'
        ? 'NEEDS_REVIEW'
        : 'PENDING',
    publishedAt: submission.status === 'JUDGED' ? submission.judgedAt ?? null : null,
  }
}

export async function createSubmission(
  assessment: Assessment,
  payload: CreateAssessmentSubmissionRequest,
  memberId: string,
): Promise<AssessmentSubmission> {
  const nextId = String(9200 + OJ_SUBMISSIONS.length + 1)
  const submission: AssessmentSubmission = {
    id: nextId,
    assessmentId: assessment.id,
    problemId: payload.problemId,
    memberId,
    ...memberFields(memberId),
    language: payload.language,
    status: 'JUDGED',
    judgeResult: {
      verdict: 'ACCEPTED',
      passedTestCases: 3,
      totalTestCases: 3,
      timeMs: 36,
      memoryKb: 18432,
      safeError: null,
      testCaseResults: mockTestCaseResults(3, 3),
    },
    codeHash: await codeHashOf(payload.code),
    version: 1,
    submittedAt: ojNowIso(),
    judgedAt: ojNowIso(),
  }
  OJ_SUBMISSIONS.push(submission)
  OJ_AI_EVALUATIONS.push({
    id: String(9300 + OJ_AI_EVALUATIONS.length + 1),
    submissionId: submission.id,
    status: 'PENDING',
    score: null,
    reason: null,
    model: null,
    rubricVersion: 'mock-rubric-1',
    createdAt: ojNowIso(),
  })
  return submission
}

export function createProblem(payload: CreateAssessmentProblemRequest): AssessmentProblem {
  const now = ojNowIso()
  const { assessmentTestCases, practiceTestCases = [], ...publicPayload } = payload
  const problem: AssessmentProblem = {
    id: String(9100 + OJ_PROBLEMS.length + 1),
    ...publicPayload,
    judgeMode: publicPayload.judgeMode ?? 'STDIO',
    functionName: publicPayload.functionName ?? null,
    parameterTypes: publicPayload.parameterTypes ?? null,
    returnType: publicPayload.returnType ?? null,
    status: 'DRAFT',
    version: 1,
    createdAt: now,
    updatedAt: now,
  }
  OJ_PROBLEMS.push(problem)
  OJ_PROBLEM_TEST_CASES[problem.id] = {
    assessmentTestCases: [...assessmentTestCases],
    practiceTestCases: [...practiceTestCases],
  }
  return problem
}

export function updateProblem(
  problem: AssessmentProblem,
  payload: UpdateAssessmentProblemRequest,
): AssessmentProblem {
  const { version: _version, assessmentTestCases, practiceTestCases, ...publicPayload } = payload
  Object.assign(problem, publicPayload, { version: problem.version + 1, updatedAt: ojNowIso() })
  const stored = OJ_PROBLEM_TEST_CASES[problem.id]
  if (stored) {
    if (assessmentTestCases) stored.assessmentTestCases = [...assessmentTestCases]
    if (practiceTestCases) stored.practiceTestCases = [...practiceTestCases]
  }
  return problem
}

export function createAssessment(payload: CreateAssessmentRequest): Assessment {
  const direction = DIRECTIONS.find((item) => item.id === payload.directionId)
  const now = ojNowIso()
  const assessment: Assessment = {
    id: String(9000 + OJ_ASSESSMENTS.length + 1),
    ...payload,
    directionName: direction?.name ?? '',
    status: 'DRAFT',
    version: 1,
    createdAt: now,
    updatedAt: now,
  }
  OJ_ASSESSMENTS.push(assessment)
  return assessment
}

export function updateAssessment(
  assessment: Assessment,
  payload: UpdateAssessmentRequest,
): Assessment {
  Object.assign(assessment, payload, { version: assessment.version + 1, updatedAt: ojNowIso() })
  return assessment
}

export function createReview(
  submission: AssessmentSubmission,
  payload: CreateAssessmentReviewRequest,
  reviewerId: string,
): AssessmentReview {
  for (const review of OJ_REVIEWS) {
    if (review.submissionId === submission.id && review.status === 'ACTIVE') {
      review.status = 'SUPERSEDED'
      review.updatedAt = ojNowIso()
    }
  }
  const now = ojNowIso()
  const review: AssessmentReview = {
    id: String(9400 + OJ_REVIEWS.length + 1),
    submissionId: submission.id,
    memberId: submission.memberId,
    ...memberFields(submission.memberId),
    reviewerId,
    reviewerName: MEMBERS.find((member) => member.userId === reviewerId)?.name ?? null,
    score: payload.score,
    comment: payload.comment,
    status: 'ACTIVE',
    version: payload.reviewVersion,
    createdAt: now,
    updatedAt: now,
  }
  OJ_REVIEWS.push(review)
  submission.version += 1
  return review
}

export function judgeResultForNewSubmission(payload: CreateAssessmentSubmissionRequest): OjJudgeResult {
  void payload
  return {
    verdict: 'ACCEPTED',
    passedTestCases: 3,
    totalTestCases: 3,
    timeMs: 36,
    memoryKb: 18432,
    safeError: null,
    testCaseResults: mockTestCaseResults(3, 3),
  }
}
