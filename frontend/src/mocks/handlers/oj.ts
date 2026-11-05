/**
 * Mock：OJ 考核、提交、AI 评分、人工批阅与独立结果。
 *
 * 隐私边界：
 *   - 请求中的 code 只用于生成 codeHash，不写响应、不写审计、不打印；
 *   - 不返回私有测试数据、完整代码、OJ 原始错误或 AI 原始响应；
 *   - 角色按方向/成员做对象级过滤，学生只能看自己的提交。
 */
import { HttpResponse, http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import {
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
  findProblemTestCases,
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
import { parseOjImportJson } from '@/utils/ojImport'
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

/**
 * 混合条目校验：与真实后端 parseItems 同口径——类型合法、引用可用、分值 1–100、
 * 同一题只能挂一次。mock 没有客观题库，QUIZ 只校验形状，PROGRAM 需命中题库。
 */
function validItems(value: unknown): value is Array<{ type: 'PROGRAM' | 'QUIZ'; refId: string; score: number }> {
  if (!Array.isArray(value) || value.length === 0) return false
  const seen = new Set<string>()
  return value.every((entry) => {
    if (typeof entry !== 'object' || entry === null || Array.isArray(entry)) return false
    const item = entry as JsonObject
    if (item.type !== 'PROGRAM' && item.type !== 'QUIZ') return false
    if (typeof item.refId !== 'string' || !item.refId) return false
    if (typeof item.score !== 'number' || !Number.isInteger(item.score)) return false
    if (item.score < 1 || item.score > 100) return false
    if (item.type === 'PROGRAM' && !findProblem(item.refId)) return false
    if (seen.has(`${item.type}:${item.refId}`)) return false
    seen.add(`${item.type}:${item.refId}`)
    return true
  })
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

function runCases(
  testCases: Array<{ input: string; expectedOutput: string }>,
  code: string,
) {
  const failure = code.includes('compile-error')
    ? 'COMPILATION_ERROR'
    : code.includes('wrong-answer')
      ? 'WRONG_ANSWER'
      : code.includes('timeout')
        ? 'TIME_LIMIT_EXCEEDED'
        : code.includes('runtime-error')
          ? 'RUNTIME_ERROR'
          : 'ACCEPTED'
  const passed = failure === 'ACCEPTED'
    ? testCases.length
    : failure === 'COMPILATION_ERROR'
      ? 0
      : Math.max(0, testCases.length - 1)
  const testCaseResults = testCases.map((testCase, index) => {
    const verdict = index < passed ? 'ACCEPTED' : failure
    return {
      position: index + 1,
      input: testCase.input,
      expectedOutput: testCase.expectedOutput,
      actualOutput:
        verdict === 'ACCEPTED'
          ? testCase.expectedOutput
          : verdict === 'WRONG_ANSWER'
            ? 'mock actual output differs'
            : null,
      verdict,
      timeMs: verdict === 'TIME_LIMIT_EXCEEDED' ? 30000 : verdict === 'ACCEPTED' ? 12 : 10,
      memoryKb: verdict === 'ACCEPTED' ? 2048 : 1024,
      safeError: verdict === 'ACCEPTED'
        ? null
        : verdict === 'WRONG_ANSWER'
          ? 'synthetic wrong answer'
          : verdict === 'TIME_LIMIT_EXCEEDED'
            ? 'synthetic time limit exceeded'
            : verdict === 'COMPILATION_ERROR'
              ? 'synthetic compilation failure'
              : 'synthetic runtime failure',
    }
  })
  return {
    verdict: failure,
    passedTestCases: passed,
    totalTestCases: testCases.length,
    timeMs: Math.max(0, ...testCaseResults.map((item) => item.timeMs)),
    memoryKb: Math.max(0, ...testCaseResults.map((item) => item.memoryKb)),
    safeError: failure === 'ACCEPTED' ? null : testCaseResults.find((item) => item.safeError)?.safeError ?? null,
    testCaseResults,
  }
}

function practiceRunResult(problemId: string, code: string) {
  return runCases(findProblemTestCases(problemId)?.practiceTestCases ?? [], code)
}

/**
 * 考核试运行：从正式测试点里“抽”一个（用代码内容做种子模拟随机），只返回该点。
 * 没有正式测试点时返回 null，调用方回 400。
 */
function trialRunResult(problemId: string, code: string) {
  const testCases = findProblemTestCases(problemId)?.assessmentTestCases ?? []
  if (testCases.length === 0) return null
  let seed = 0
  for (const char of code) seed = (seed * 31 + char.charCodeAt(0)) % 2147483647
  const picked = testCases[seed % testCases.length] ?? testCases[0]!
  const single = runCases([picked], code)
  return {
    ...single,
    testCaseResults: single.testCaseResults.map((item) => ({ ...item, position: 1 })),
  }
}

function validPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0
}

function validTestCases(value: unknown, options: { required?: boolean } = {}): boolean {
  if (value === undefined && !options.required) return true
  if (!Array.isArray(value) || value.length > 100) return false
  if (options.required && value.length === 0) return false
  return value.every((item) => {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) return false
    const testCase = item as JsonObject
    return (
      Object.keys(testCase).every((key) => key === 'input' || key === 'expectedOutput') &&
      typeof testCase.input === 'string' &&
      testCase.input.length <= 1_000_000 &&
      typeof testCase.expectedOutput === 'string' &&
      testCase.expectedOutput.length <= 1_000_000
    )
  })
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

const MOCK_SOURCE: Record<string, string> = {
  python: 'import sys\n\n\ndef solve() -> None:\n    data = sys.stdin.buffer.read().split()\n    print(len(data))\n\n\nif __name__ == "__main__":\n    solve()\n',
  java: 'import java.io.BufferedReader;\nimport java.io.InputStreamReader;\n\npublic class Main {\n    public static void main(String[] args) throws Exception {\n        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));\n        System.out.print(in.readLine().length());\n    }\n}\n',
  cpp: '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n    return 0;\n}\n',
  javascript: 'const fs = require(\'fs\')\n\nconst data = fs.readFileSync(0, \'utf8\').trim().split(/\\s+/)\nconsole.log(data.length)\n',
  go: 'package main\n\nimport (\n\t"bufio"\n\t"os"\n)\n\nfunc main() {\n\tbufio.NewReader(os.Stdin)\n}\n',
  rust: 'use std::io::{self, Read};\n\nfn main() {\n    let mut input = String::new();\n    io::stdin().read_to_string(&mut input).unwrap();\n}\n',
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
      const bad = rejectUnknownFields(body, [
        'title', 'description', 'directionId', 'startAt', 'endAt',
        'allowedLanguages', 'pointsEnabled', 'items', 'problemIds',
      ])
      if (bad) return bad
      if (
        typeof body.title !== 'string' || body.title.trim().length < 1 ||
        typeof body.description !== 'string' ||
        typeof body.directionId !== 'string' ||
        typeof body.startAt !== 'string' ||
        typeof body.endAt !== 'string' ||
        !isLanguageArray(body.allowedLanguages) ||
        !validItems(body.items) ||
        ('pointsEnabled' in body && typeof body.pointsEnabled !== 'boolean') ||
        ('problemIds' in body && !isStringArray(body.problemIds))
      ) return errors.invalidRequest()
      if (account.user.role === 'DIRECTION_LEADER' && !account.user.managedDirectionIds.includes(body.directionId)) {
        return errors.forbidden()
      }
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
      const bad = rejectUnknownFields(body, [
        'version', 'title', 'description', 'startAt', 'endAt',
        'allowedLanguages', 'pointsEnabled', 'items', 'problemIds',
      ])
      if (bad) return bad
      if (body.version !== assessment.version) return errors.versionConflict()
      if ('title' in body && (typeof body.title !== 'string' || !body.title.trim())) return errors.invalidRequest()
      if ('description' in body && typeof body.description !== 'string') return errors.invalidRequest()
      if ('startAt' in body && typeof body.startAt !== 'string') return errors.invalidRequest()
      if ('endAt' in body && typeof body.endAt !== 'string') return errors.invalidRequest()
      if ('allowedLanguages' in body && !isLanguageArray(body.allowedLanguages)) return errors.invalidRequest()
      if ('pointsEnabled' in body && typeof body.pointsEnabled !== 'boolean') return errors.invalidRequest()
      if ('items' in body && !validItems(body.items)) return errors.invalidRequest()
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
  http.post(`${PROBLEMS}/import`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role === 'STUDENT') return errors.forbidden()

    return withIdempotency(request, (bodyText) => {
      const preview = parseOjImportJson(bodyText)
      if (!preview.payload) {
        return HttpResponse.json(
          {
            code: 'INVALID_REQUEST',
            message: 'Import validation failed',
            data: { importedCount: 0, problemIds: [], errors: preview.errors },
          },
          { status: 400 },
        )
      }
      const problemIds = preview.payload.problems.map((problem) => createProblem(problem).id)
      return ok({ importedCount: problemIds.length, problemIds, errors: [] }, 201)
    })
  }),

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
      const bad = rejectUnknownFields(body, ['title', 'summary', 'description', 'inputFormat', 'outputFormat', 'examples', 'difficulty', 'tags', 'timeLimitMs', 'memoryLimitKb', 'allowedLanguages', 'assessmentTestCases', 'practiceTestCases'])
      if (bad) return bad
      if (
        typeof body.title !== 'string' || typeof body.summary !== 'string' || typeof body.description !== 'string' ||
        typeof body.inputFormat !== 'string' || typeof body.outputFormat !== 'string' ||
        !validExamples(body.examples) || !['EASY', 'MEDIUM', 'HARD'].includes(body.difficulty as string) ||
        !isStringArray(body.tags) || !isLanguageArray(body.allowedLanguages) ||
        !validPositiveInteger(body.timeLimitMs) || !validPositiveInteger(body.memoryLimitKb) ||
        !validTestCases(body.assessmentTestCases, { required: true }) ||
        !validTestCases(body.practiceTestCases)
      ) return errors.invalidRequest()
      return ok(createProblem(body as never), 201)
    })
  }),

  http.get(`${PROBLEMS}/:problemId/test-cases`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role === 'STUDENT') return errors.forbidden()
    const problem = findProblem(params.problemId as string)
    const testCases = findProblemTestCases(params.problemId as string)
    if (!problem || !testCases) return errors.notFound()
    return ok({
      assessmentTestCases: testCases.assessmentTestCases.map((item, index) => ({
        position: index + 1,
        ...item,
      })),
      practiceTestCases: testCases.practiceTestCases.map((item, index) => ({
        position: index + 1,
        ...item,
      })),
    })
  }),

  http.get(`${PROBLEMS}/:problemId/practice-test-cases`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role !== 'STUDENT') return errors.forbidden()
    const problem = findProblem(params.problemId as string)
    if (!problem || problem.status !== 'PUBLISHED') return errors.notFound()
    return ok({
      practiceTestCases: (findProblemTestCases(params.problemId as string)?.practiceTestCases ?? [])
        .map((item, index) => ({ position: index + 1, ...item })),
    })
  }),

  http.post(`${PROBLEMS}/:problemId/practice-run`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role !== 'STUDENT') return errors.forbidden()
    const problem = findProblem(params.problemId as string)
    if (!problem || problem.status !== 'PUBLISHED') return errors.notFound()

    return withIdempotency(request, (bodyText) => {
      const body = parseBody(bodyText)
      if (!body) return errors.invalidRequest()
      const bad = rejectUnknownFields(body, ['language', 'code'])
      if (bad) return bad
      if (!isLanguage(body.language) || typeof body.code !== 'string' || !body.code.trim()) {
        return errors.invalidRequest()
      }
      return ok(practiceRunResult(params.problemId as string, body.code))
    })
  }),

  http.post(`${PROBLEMS}/:problemId/trial-run`, async ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    if (!account || account.user.role !== 'STUDENT') return errors.forbidden()
    const problem = findProblem(params.problemId as string)
    if (!problem || problem.status !== 'PUBLISHED') return errors.notFound()
    const body = parseBody(await request.text())
    if (!body) return errors.invalidRequest()
    const bad = rejectUnknownFields(body, ['language', 'code'])
    if (bad) return bad
    if (!isLanguage(body.language) || typeof body.code !== 'string' || !body.code.trim()) {
      return errors.invalidRequest()
    }
    const result = trialRunResult(params.problemId as string, body.code)
    return result ? ok(result) : errors.invalidRequest()
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
      const bad = rejectUnknownFields(body, ['version', 'title', 'summary', 'description', 'inputFormat', 'outputFormat', 'examples', 'difficulty', 'tags', 'timeLimitMs', 'memoryLimitKb', 'allowedLanguages', 'assessmentTestCases', 'practiceTestCases'])
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
      if ('assessmentTestCases' in body && !validTestCases(body.assessmentTestCases, { required: true })) {
        return errors.invalidRequest()
      }
      if ('practiceTestCases' in body && !validTestCases(body.practiceTestCases)) {
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

  http.get(`${SUBMISSIONS}/:submissionId/code`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const account = caller(request)
    const submission = findSubmission(params.submissionId as string)
    if (!account || !submission || !canSeeSubmission(submission, account)) return errors.notFound()
    return ok({
      submissionId: submission.id,
      language: submission.language,
      code: MOCK_SOURCE[submission.language] ?? '',
    })
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
