/**
 * 客观题库与答题卡接口。
 *
 * 契约：docs/superpowers/specs/2026-10-24-mixed-exam-quiz-design.md §6。
 * 客观题库仅负责人/管理员可见（学生一律 404）；试卷与本人答题卡面向学生，
 * paper 响应绝不含 answer/analysis。POST 写操作必须由调用方生成并复用
 * Idempotency-Key；草稿暂存为 PUT 整体覆盖，契约未声明幂等键。
 */
import { API_PREFIX, get, patch, post, put } from '../client'
import type {
  AnswerDraft,
  AnswerSheetDetail,
  AnswerSheetSummary,
  AssessmentPaper,
  CreateQuizQuestionRequest,
  PageData,
  QuizQuestion,
  QuizQuestionType,
} from '../types'

const QUIZ_QUESTIONS = `${API_PREFIX}/quiz-questions`
const ASSESSMENTS = `${API_PREFIX}/assessments`

// ---------------------------------------------------------------------------
// 客观题库（负责人/管理员；学生一律 404）
// ---------------------------------------------------------------------------

export interface QuizQuestionListQuery {
  page?: number
  size?: number
  type?: QuizQuestionType
  status?: QuizQuestion['status']
  /** 题干关键字过滤。 */
  keyword?: string
}

/** 分页列出客观题；后端默认排除 RETIRED。 */
export async function listQuizQuestions(
  query: QuizQuestionListQuery = {},
): Promise<PageData<QuizQuestion>> {
  return get<PageData<QuizQuestion>>(QUIZ_QUESTIONS, { params: query })
}

export async function createQuizQuestion(
  payload: CreateQuizQuestionRequest,
  idempotencyKey: string,
): Promise<QuizQuestion> {
  return post<QuizQuestion>(QUIZ_QUESTIONS, payload, { idempotencyKey })
}

export async function getQuizQuestion(questionId: string): Promise<QuizQuestion> {
  return get<QuizQuestion>(`${QUIZ_QUESTIONS}/${questionId}`)
}

/** 仅 DRAFT 可编辑，且禁止改 type。 */
export async function updateQuizQuestion(
  questionId: string,
  payload: Partial<CreateQuizQuestionRequest>,
): Promise<QuizQuestion> {
  return patch<QuizQuestion>(`${QUIZ_QUESTIONS}/${questionId}`, payload)
}

/** 停用客观题：退出题库选择，已被考核引用的条目判分不受影响。 */
export async function retireQuizQuestion(
  questionId: string,
  idempotencyKey: string,
): Promise<QuizQuestion> {
  return post<QuizQuestion>(`${QUIZ_QUESTIONS}/${questionId}/retire`, {}, { idempotencyKey })
}

// ---------------------------------------------------------------------------
// 学生试卷与答题卡
// ---------------------------------------------------------------------------

/** 学生试卷：PUBLISHED 且本方向才可读；客观题条目不含 answer/analysis。 */
export async function getAssessmentPaper(assessmentId: string): Promise<AssessmentPaper> {
  return get<AssessmentPaper>(`${ASSESSMENTS}/${assessmentId}/paper`)
}

/** 草稿暂存：整体覆盖 answers，仅 DRAFT 状态、窗口开放内可写。 */
export async function saveAnswerDraft(
  assessmentId: string,
  answers: AnswerDraft,
): Promise<AnswerSheetDetail> {
  return put<AnswerSheetDetail>(`${ASSESSMENTS}/${assessmentId}/answer-sheet`, { answers })
}

/** 交卷并同步判分；窗口结束或已交卷时服务端拒绝。 */
export async function submitAnswerSheet(
  assessmentId: string,
  idempotencyKey: string,
): Promise<AnswerSheetDetail> {
  return post<AnswerSheetDetail>(`${ASSESSMENTS}/${assessmentId}/answer-sheet/submit`, {}, {
    idempotencyKey,
  })
}

/** 本人答题卡：考核结束前只有 status/answeredCount，结束后含逐题对错与解析。 */
export async function getMyAnswerSheet(assessmentId: string): Promise<AnswerSheetDetail> {
  return get<AnswerSheetDetail>(`${ASSESSMENTS}/${assessmentId}/my-answer-sheet`)
}

export interface AnswerSheetListQuery {
  page?: number
  size?: number
  /** 下钻某成员的答题卡；仅负责人/管理员。 */
  memberId?: string
}

/** 逐生答题卡列表（负责人/管理员），交卷后立即含对错与得分。 */
export async function listAnswerSheets(
  assessmentId: string,
  query: AnswerSheetListQuery = {},
): Promise<PageData<AnswerSheetSummary>> {
  return get<PageData<AnswerSheetSummary>>(`${ASSESSMENTS}/${assessmentId}/answer-sheets`, {
    params: query,
  })
}
