import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import QuizReviewView from '@/views/oj/QuizReviewView.vue'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn<(route: unknown) => void>(),
  listQuizReview: vi.fn<(id: string, query?: unknown) => Promise<unknown>>(),
  getQuizReviewSummary: vi.fn<() => Promise<unknown>>(),
  saveQuizReviewScores: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/quiz', () => ({
  listQuizReview: mocks.listQuizReview,
  getQuizReviewSummary: mocks.getQuizReviewSummary,
  saveQuizReviewScores: mocks.saveQuizReviewScores,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { assessmentId: '9001' }, query: {} }),
  useRouter: () => ({ push: mocks.routerPush }),
}))

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
  size: 200,
  total: 2,
}

/** 填空题清单随进度一次下发，页面不再逐题回查题库。 */
const summary = {
  assessment: { id: '9001', title: 'AI 全栈月考', status: 'CLOSED', resultsPublished: false },
  questions: [
    {
      position: 3,
      questionId: 'q1',
      stem: '补全下面这段代码：\npublic class Solution {\n  // 实现求和方法\n}',
      score: 10,
      referenceAnswers: ['implements', 'public', 'new'],
      analysis: '考察接口实现',
    },
    {
      position: 4,
      questionId: 'q2',
      stem: '第二题题干',
      score: 5,
      referenceAnswers: [],
      analysis: null,
    },
  ],
  totalItems: 4,
  gradedItems: 0,
  pendingItems: 2,
  submittedSheets: 2,
  resultsPublished: false,
  canPublish: false,
  pointsEnabled: true,
}

async function mountReview(): Promise<ReturnType<typeof mount>> {
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

  it('0 分也能保存：未批与 0 分不是一回事', async () => {
    const wrapper = await mountReview()
    await wrapper.get('[data-test="score-88"] input').setValue('0')
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()
    expect(mocks.saveQuizReviewScores).toHaveBeenCalledWith(
      '9001',
      { items: [{ sheetId: '88', position: 3, score: 0, comment: '', version: 2 }] },
      expect.any(String),
    )
  })

  it('还有未批条目时提示发布按钮不可用', async () => {
    const wrapper = await mountReview()
    const publish = wrapper.get('[data-test="go-results"]')
    expect(publish.attributes('disabled')).toBeDefined()
    expect(publish.text()).toContain('还有 2 条未批')
    expect(wrapper.text()).toContain('本题已批 0 / 2 人')
    expect(wrapper.text()).toContain('全场待批 2 条')
  })

  it('一次取全所有记录，不分页', async () => {
    await mountReview()
    const [, query] = mocks.listQuizReview.mock.calls[0] ?? []
    expect(query).toMatchObject({ questionId: 'q1', filter: 'all', page: 1, size: 200 })
  })

  it('展示完整题干与评分参考，供阅卷人对照', async () => {
    const wrapper = await mountReview()
    const stem = wrapper.get('.review-stem-text')
    expect(stem.text()).toContain('public class Solution')
    expect(stem.text()).toContain('\n')
    expect(wrapper.text()).toContain('第 3 题')
    expect(wrapper.text()).toContain('10 分')
    expect(wrapper.text()).toContain('implements')
    expect(wrapper.text()).toContain('考察接口实现')
  })

  it('元数据加载中不误报「没有填空题」', async () => {
    let resolveSummary: (value: unknown) => void = () => {}
    mocks.getQuizReviewSummary.mockReturnValue(
      new Promise((resolve) => {
        resolveSummary = resolve
      }),
    )
    const wrapper = mount(QuizReviewView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('没有填空题')

    resolveSummary(summary)
    await flushPromises()
    expect(wrapper.text()).not.toContain('没有填空题')
    expect(wrapper.text()).toContain('第 3 题')
  })
})
