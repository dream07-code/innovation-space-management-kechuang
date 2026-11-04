import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import QuizReviewView from '@/views/oj/QuizReviewView.vue'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn<(route: unknown) => void>(),
  getAssessment: vi.fn<() => Promise<unknown>>(),
  getQuizQuestion: vi.fn<(id: string) => Promise<unknown>>(),
  listQuizReview: vi.fn<() => Promise<unknown>>(),
  getQuizReviewSummary: vi.fn<() => Promise<unknown>>(),
  saveQuizReviewScores: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({ getAssessment: mocks.getAssessment }))
vi.mock('@/api/modules/quiz', () => ({
  getQuizQuestion: mocks.getQuizQuestion,
  listQuizReview: mocks.listQuizReview,
  getQuizReviewSummary: mocks.getQuizReviewSummary,
  saveQuizReviewScores: mocks.saveQuizReviewScores,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { assessmentId: '9001' }, query: {} }),
  useRouter: () => ({ push: mocks.routerPush }),
}))

const assessment = {
  id: '9001',
  title: 'AI 全栈月考',
  version: 3,
  status: 'CLOSED',
  items: [
    { position: 3, type: 'QUIZ', refId: 'q1', score: 10 },
    { position: 4, type: 'QUIZ', refId: 'q2', score: 5 },
  ],
}

const question = { id: 'q1', type: 'FILL_BLANK', stem: '写一个求和方法', status: 'PUBLISHED' }

const reviewPage = {
  assessment: {
    id: '9001',
    title: 'AI 全栈月考',
    status: 'CLOSED',
    directionId: '1',
    resultsPublished: false,
  },
  question: {
    position: 3,
    questionId: 'q1',
    stem: '写一个求和方法',
    score: 10,
    referenceAnswers: ['int sum(int a, int b)'],
    analysis: null,
  },
  progress: { graded: 0, total: 2 },
  items: [
    {
      sheetId: '88',
      memberId: '10',
      memberName: '张三',
      studentNo: 'S10',
      answer: 'class Solution {\n  int sum(int a, int b) { return a + b; }\n}',
      answered: true,
      autoAwarded: 0,
      awarded: 0,
      manualScore: null,
      reviewed: false,
      comment: null,
      version: 2,
      reviewedAt: null,
    },
    {
      sheetId: '90',
      memberId: '14',
      memberName: '李四',
      studentNo: 'S14',
      answer: null,
      answered: false,
      autoAwarded: 0,
      awarded: 0,
      manualScore: null,
      reviewed: false,
      comment: null,
      version: null,
      reviewedAt: null,
    },
  ],
  page: 1,
  size: 50,
  total: 2,
}

const summary = {
  totalItems: 4,
  gradedItems: 0,
  pendingItems: 2,
  submittedSheets: 2,
  resultsPublished: false,
  canPublish: false,
  pointsEnabled: true,
}

async function mountReview(): Promise<ReturnType<typeof mount>> {
  mocks.getAssessment.mockResolvedValue(assessment)
  mocks.getQuizQuestion.mockResolvedValue(question)
  mocks.listQuizReview.mockResolvedValue(reviewPage)
  mocks.getQuizReviewSummary.mockResolvedValue(summary)
  mocks.saveQuizReviewScores.mockResolvedValue({ updated: 1, sheets: [{ sheetId: '88', totalScore: 8 }] })
  const wrapper = mount(QuizReviewView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('QuizReviewView 填空阅卷', () => {
  it('未作答的行不可编辑', async () => {
    const wrapper = await mountReview()
    const input = wrapper.get('[data-test="score-90"] input').element as HTMLInputElement
    expect(input.disabled).toBe(true)
    const answered = wrapper.get('[data-test="score-88"] input').element as HTMLInputElement
    expect(answered.disabled).toBe(false)
  })

  it('只提交改动过的行，并带当前版本', async () => {
    const wrapper = await mountReview()
    await wrapper.get('[data-test="score-88"] input').setValue('8')
    await wrapper.get('[data-test="comment-88"]').setValue('边界没处理')
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.saveQuizReviewScores).toHaveBeenCalledTimes(1)
    const [assessmentId, payload, key] = mocks.saveQuizReviewScores.mock.calls[0] ?? []
    expect(assessmentId).toBe('9001')
    expect(payload).toEqual({
      items: [
        { sheetId: '88', position: 3, score: 8, comment: '边界没处理', version: 2 },
      ],
    })
    expect(typeof key).toBe('string')
  })

  it('还有未批条目时提示发布按钮不可用', async () => {
    const wrapper = await mountReview()
    const publish = wrapper.get('[data-test="go-results"]')
    expect(publish.attributes('disabled')).toBeDefined()
    expect(publish.text()).toContain('还有 2 条未批')
    expect(wrapper.text()).toContain('已批 0 / 2，剩余 2 条')
  })
})
