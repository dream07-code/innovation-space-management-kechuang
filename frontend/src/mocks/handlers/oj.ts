/**
 * Mock：OJ 考核、提交、AI 评分、人工批阅与独立结果。
 *
 * 隐私边界：
 *   - 请求中的 code 只用于生成 codeHash，不写响应、不写审计、不打印；
 *   - 不返回私有测试数据、完整代码、OJ 原始错误或 AI 原始响应；
 *   - 角色按方向/成员做对象级过滤，学生只能看自己的提交。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import {
  OJ_AI_EVALUATIONS,
  OJ_ASSESSMENTS,
  OJ_PROBLEMS,
  OJ_REVIEWS,
  OJ_SUBMISSIONS,
  activeReviewOf,
  buildResult,
  createAssessment,
  createProblem,
  createReview,
  createSubmission,
  findAssessment,
  findProblem,
  findSubmission,
  ojNowIso,
  updateAssessment,
  updateProblem,
} from '../ojData'
import { MEMBERS, paginate, parsePageParams } from '../data'
import {
  bearerFrom,
  errors,
  findAccountByUserId,
  findSessionByAccess,
  guardSession,
  ok,
} from '../state'
import { withIdempotency } from './idempotency'

const ASSESSMENTS = `${API_PREFIX}/assessments`
const PROBLEMS = `${API_PREFIX}/assessment-problems`
const SUBMISSIONS = `${API_PREFIX}/assessment-submissions`

type JsonObject = Record<string, unknown>

function caller(request: Request) {
  const session = findSessionByAccess(bearerFrom(request))
  return session ? findAccountByUserId(session.userId) : null
}

function parseBody(bodyText: string): JsonObject | null {
  try {
    const body: unknown = JSON.parse(bodyText || 'null')
    return typeof body === 'object' && body !== null && !Array.isArray(body)
      ? (body as JsonObject)
      : null
  } catch {
    return null
  }
}

function rejectUnknownFields(body: JsonObject, allowed: readonly string[]): Response | null {
  for (const key of Object.keys(body)) {
    if (!allowed.includes(key)) return errors.invalidRequest()
  }
  return null
}

function isLanguage(value: unknown): value is 'java' | 'python' | 'cpp' | 'javascript' | 'go' | 'rust' {
  return value === 'java' || value === 'python' || value === 'cpp' || value === 'javascript' || value === 'go' || value === 'rust'
}

function isLanguageArray(value: unknown): value is ('java' | 'python' | 'cpp' | 'javascript' | 'go' | 'rust')[] {
  return Array.isArray(value) && value.length > 0 && value.every(isLanguage)
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function validExamples(value: unknown): boolean {
  return Array.isArray(value) && value.every((item) => {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) return false
    const example = item as JsonObject
    return (
      Object.keys(example).every((key) => key === 'input' || key === 'output' || key === 'explanation') &&
      typeof example.input === 'string' &&
      typeof example.output === 'string' &&
      (example.explanation === undefined || example.explanation === null || typeof example.explanation === 'string')
    )
  })
}

function validPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0
}

function memberDirection(memberId: string): string | null {
  return MEMBERS.find((member) => member.id === memberId)?.directionId ?? null
}

function canManageAssessment(assessment: { directionId: string }, account: NonNullable<ReturnType<typeof caller>>): boolean {
  if (account.user.role === 'COLLEGE_ADMIN') return true
  return account.user.role === 'DIRECTION_LEADER' && account.user.managedDirectionIds.includes(assessment.directionId)
}

function canSeeAssessment(assessment: { directionId: string; status: string }, account: NonNullable<ReturnType<typeof caller>>): boolean {
  if (account.user.role === 'COLLEGE_ADMIN') return true
  if (account.user.role === 'DIRECTION_LEADER') return account.user.managedDirectionIds.includes(assessment.directionId)
  return account.user.directionId === assessment.directionId && (assessment.status === 'PUBLISHED' || assessment.status === 'CLOSED')
}

function canSeeSubmission(submission: { memberId: string }, account: NonNullable<ReturnType<typeof caller>>): boolean {
  if (account.user.role === 'COLLEGE_ADMIN') return true
  const directionId = memberDirection(submission.memberId)
  if (account.user.role === 'DIRECTION_LEADER') return directionId !== null && account.user.managedDirectionIds.includes(directionId)
  return account.user.memberId === submission.memberId
}

function pageOf<T>(items: T[], url: URL): Response {
  const paging = parsePageParams(url)
  if (!paging) return errors.invalidRequest()
  return ok(paginate(items, paging.page, paging.size))
}

function validScore(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100
}

export const ojHandlers = [
  // -- assessments -----------------------------------------------------------
  http.get(`${ASSESSMENTS}`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account) return errors.unauthorized()
    const items = OJ_ASSESSMENTS.filter((assessment) => canSeeAssessment(assessment, account))
    return pageOf(items, new URL(request.url))
  }),

  http.post(`${ASSESSMENTS}`, async ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role === 'STUDENT') return errors.forbidden()

    return withIdempotency(request, (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['title', 'description', 'directionId', 'startAt', 'endAt', 'allowedLanguages', 'problemIds'])
      if (bad) return bad
      if (
        typeof body.title !== 'string' || body.title.trim().length < 1 ||
        typeof body.description !== 'string' ||
        typeof body.directionId !== 'string' ||
        typeof body.startAt !== 'string' ||
        typeof body.endAt !== 'string' ||
        !isLanguageArray(body.allowedLanguages) ||
        !isStringArray(body.problemIds)
      ) return errors.invalidRequest()
      if (account.user.role === 'DIRECTION_LEADER' && !account.user.managedDirectionIds.includes(body.directionId)) {
        return errors.forbidden()
      }
      if (body.problemIds.some((id) => !findProblem(id))) return errors.invalidRequest()
      if (!Number.isFinite(Date.parse(body.startAt)) || !Number.isFinite(Date.parse(body.endAt)) || Date.parse(body.startAt) >= Date.parse(body.endAt)) {
        return errors.invalidRequest()
      }
      return ok(createAssessment(body as never), 201)
    })
  }),

  http.get(`${ASSESSMENTS}/:assessmentId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const assessment = findAssessment(params.assessmentId as string)
    if (!account || !assessment || !canSeeAssessment(assessment, account)) return errors.notFound()
    return ok(assessment)
  }),

  http.patch(`${ASSESSMENTS}/:assessmentId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const assessment = findAssessment(params.assessmentId as string)
    if (!account || !assessment || !canManageAssessment(assessment, account)) return errors.notFound()
    if (assessment.status !== 'DRAFT') return errors.invalidState()

    return (async () => {
      const body = parseBody(await request.text())
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version', 'title', 'description', 'startAt', 'endAt', 'allowedLanguages', 'problemIds'])
      if (bad) return bad
      if (body.version !== assessment.version) return errors.versionConflict()
      if ('title' in body && (typeof body.title !== 'string' || !body.title.trim())) return errors.invalidRequest()
      if ('description' in body && typeof body.description !== 'string') return errors.invalidRequest()
      if ('startAt' in body && typeof body.startAt !== 'string') return errors.invalidRequest()
      if ('endAt' in body && typeof body.endAt !== 'string') return errors.invalidRequest()
      if ('allowedLanguages' in body && !isLanguageArray(body.allowedLanguages)) return errors.invalidRequest()
      if ('problemIds' in body && (!isStringArray(body.problemIds) || body.problemIds.some((id) => !findProblem(id)))) {
        return errors.invalidRequest()
      }
      const startAt = 'startAt' in body ? body.startAt : assessment.startAt
      const endAt = 'endAt' in body ? body.endAt : assessment.endAt
      if (
        typeof startAt !== 'string' || typeof endAt !== 'string' ||
        !Number.isFinite(Date.parse(startAt)) || !Number.isFinite(Date.parse(endAt)) ||
        Date.parse(startAt) >= Date.parse(endAt)
      ) return errors.invalidRequest()
      return ok(updateAssessment(assessment, body as never))
    })()
  }),

  http.post(`${ASSESSMENTS}/:assessmentId/publish`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const assessment = findAssessment(params.assessmentId as string)
    if (!account || !assessment || !canManageAssessment(assessment, account)) return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version'])
      if (bad) return bad
      if (body.version !== assessment.version) return errors.versionConflict()
      if (assessment.status !== 'DRAFT' || assessment.problemIds.length === 0) return errors.invalidState()
      assessment.status = 'PUBLISHED'
      assessment.version += 1
      assessment.updatedAt = ojNowIso()
      return ok(assessment)
    })
  }),

  http.post(`${ASSESSMENTS}/:assessmentId/close`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const assessment = findAssessment(params.assessmentId as string)
    if (!account || !assessment || !canManageAssessment(assessment, account)) return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version'])
      if (bad) return bad
      if (body.version !== assessment.version) return errors.versionConflict()
      if (assessment.status !== 'PUBLISHED') return errors.invalidState()
      assessment.status = 'CLOSED'
      assessment.version += 1
      assessment.updatedAt = ojNowIso()
      return ok(assessment)
    })
  }),

  // -- problems --------------------------------------------------------------
  http.get(`${PROBLEMS}`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account) return errors.unauthorized()
    const items = OJ_PROBLEMS.filter((problem) => account.user.role !== 'STUDENT' || problem.status === 'PUBLISHED')
    return pageOf(items, new URL(request.url))
  }),

  http.post(`${PROBLEMS}`, async ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role === 'STUDENT') return errors.forbidden()

    return withIdempotency(request, (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['title', 'summary', 'description', 'inputFormat', 'outputFormat', 'examples', 'difficulty', 'tags', 'timeLimitMs', 'memoryLimitKb', 'allowedLanguages'])
      if (bad) return bad
      if (
        typeof body.title !== 'string' || typeof body.summary !== 'string' || typeof body.description !== 'string' ||
        typeof body.inputFormat !== 'string' || typeof body.outputFormat !== 'string' ||
        !validExamples(body.examples) || !['EASY', 'MEDIUM', 'HARD'].includes(body.difficulty as string) ||
        !isStringArray(body.tags) || !isLanguageArray(body.allowedLanguages) ||
        !validPositiveInteger(body.timeLimitMs) || !validPositiveInteger(body.memoryLimitKb)
      ) return errors.invalidRequest()
      return ok(createProblem(body as never), 201)
    })
  }),

  http.get(`${PROBLEMS}/:problemId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const problem = findProblem(params.problemId as string)
    if (!account || !problem || (account.user.role === 'STUDENT' && problem.status !== 'PUBLISHED')) {
      return errors.notFound()
    }
    return ok(problem)
  }),

  http.patch(`${PROBLEMS}/:problemId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const problem = findProblem(params.problemId as string)
    if (!account || !problem || account.user.role === 'STUDENT') return errors.notFound()

    return (async () => {
      const body = parseBody(await request.text())
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version', 'title', 'summary', 'description', 'inputFormat', 'outputFormat', 'examples', 'difficulty', 'tags', 'timeLimitMs', 'memoryLimitKb', 'allowedLanguages'])
      if (bad) return bad
      if (body.version !== problem.version) return errors.versionConflict()
      if ('allowedLanguages' in body && !isLanguageArray(body.allowedLanguages)) return errors.invalidRequest()
      if ('examples' in body && !validExamples(body.examples)) return errors.invalidRequest()
      if ('tags' in body && !isStringArray(body.tags)) return errors.invalidRequest()
      if ('timeLimitMs' in body && !validPositiveInteger(body.timeLimitMs)) return errors.invalidRequest()
      if ('memoryLimitKb' in body && !validPositiveInteger(body.memoryLimitKb)) return errors.invalidRequest()
      if ('difficulty' in body && !['EASY', 'MEDIUM', 'HARD'].includes(body.difficulty as string)) {
        return errors.invalidRequest()
      }
      for (const key of ['title', 'summary', 'description', 'inputFormat', 'outputFormat'] as const) {
        if (key in body && typeof body[key] !== 'string') return errors.invalidRequest()
      }
      return ok(updateProblem(problem, body as never))
    })()
  }),

  http.post(`${PROBLEMS}/:problemId/retire`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const problem = findProblem(params.problemId as string)
    if (!account || !problem || account.user.role === 'STUDENT') return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['version'])
      if (bad) return bad
      if (body.version !== problem.version) return errors.versionConflict()
      if (problem.status === 'RETIRED') return errors.invalidState()
      problem.status = 'RETIRED'
      problem.version += 1
      problem.updatedAt = ojNowIso()
      return ok(problem)
    })
  }),

  // -- submissions ------------------------------------------------------------
  http.post(`${ASSESSMENTS}/:assessmentId/submissions`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const assessment = findAssessment(params.assessmentId as string)
    if (!account || account.user.role !== 'STUDENT' || !assessment || !canSeeAssessment(assessment, account)) {
      return errors.notFound()
    }

    return withIdempotency(request, async (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['problemId', 'language', 'code'])
      if (bad) return bad
      if (
        typeof body.problemId !== 'string' || !isLanguage(body.language) ||
        typeof body.code !== 'string' || body.code.trim().length === 0 || body.code.length > 200000
      ) return errors.invalidRequest()
      const problem = findProblem(body.problemId)
      if (!problem || problem.status !== 'PUBLISHED' || !assessment.problemIds.includes(problem.id)) {
        return errors.invalidRequest()
      }
      if (!assessment.allowedLanguages.includes(body.language) || !problem.allowedLanguages.includes(body.language)) {
        return errors.invalidRequest()
      }
      const now = Date.now()
      if (now < Date.parse(assessment.startAt) || now > Date.parse(assessment.endAt) || assessment.status !== 'PUBLISHED') {
        return errors.invalidState()
      }
      const submission = await createSubmission(assessment, body as never, account.user.memberId!)
      return ok(submission, 201)
    })
  }),

  http.get(`${SUBMISSIONS}`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account) return errors.unauthorized()
    const items = OJ_SUBMISSIONS.filter((submission) => canSeeSubmission(submission, account))
    return pageOf(items, new URL(request.url))
  }),

  http.get(`${SUBMISSIONS}/:submissionId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const submission = findSubmission(params.submissionId as string)
    if (!account || !submission || !canSeeSubmission(submission, account)) return errors.notFound()
    return ok(submission)
  }),

  http.get(`${SUBMISSIONS}/:submissionId/ai-evaluation`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const submission = findSubmission(params.submissionId as string)
    if (!account || !submission || !canSeeSubmission(submission, account)) return errors.notFound()
    const evaluation = OJ_AI_EVALUATIONS.find((item) => item.submissionId === submission.id)
    return evaluation ? ok(evaluation) : errors.notFound()
  }),

  // -- reviews and results ----------------------------------------------------
  http.get(`${SUBMISSIONS}/:submissionId/reviews`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const submission = findSubmission(params.submissionId as string)
    if (!account || account.user.role === 'STUDENT' || !submission || !canSeeSubmission(submission, account)) {
      return errors.notFound()
    }
    return pageOf(OJ_REVIEWS.filter((review) => review.submissionId === submission.id), new URL(request.url))
  }),

  http.post(`${SUBMISSIONS}/:submissionId/reviews`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const submission = findSubmission(params.submissionId as string)
    if (!account || account.user.role === 'STUDENT' || !submission || !canSeeSubmission(submission, account)) {
      return errors.notFound()
    }

    return withIdempotency(request, (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['submissionVersion', 'reviewVersion', 'score', 'comment'])
      if (bad) return bad
      if (
        body.submissionVersion !== submission.version ||
        typeof body.reviewVersion !== 'number' || body.reviewVersion < 1 ||
        !validScore(body.score) || typeof body.comment !== 'string' || body.comment.trim().length === 0
      ) return errors.invalidRequest()
      const active = activeReviewOf(submission.id)
      const expectedReviewVersion = (active?.version ?? 0) + 1
      if (body.reviewVersion !== expectedReviewVersion) return errors.versionConflict()
      return ok(createReview(submission, body as never, account.user.userId), 201)
    })
  }),

  http.get(`${ASSESSMENTS}/:assessmentId/results`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const assessment = findAssessment(params.assessmentId as string)
    if (!account || !assessment || !canSeeAssessment(assessment, account)) return errors.notFound()
    const items = OJ_SUBMISSIONS
      .filter((submission) => submission.assessmentId === assessment.id && canSeeSubmission(submission, account))
      .map(buildResult)
    return pageOf(items, new URL(request.url))
  }),
]
