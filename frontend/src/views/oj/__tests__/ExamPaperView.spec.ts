import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ExamPaperView from '@/views/oj/ExamPaperView.vue'

const mocks = vi.hoisted(() => ({
  routeParams: { assessmentId: '9001' } as Record<string, string>,
  routerPush: vi.fn<(route: unknown) => void>(),
  getAssessmentPaper: vi.fn<(id: string) => Promise<unknown>>(),
  saveAnswerDraft: vi.fn<(id: string, answers: unknown) => Promise<unknown>>(),
  submitAnswerSheet: vi.fn<(id: string, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/quiz', () => ({
  getAssessmentPaper: mocks.getAssessmentPaper,
  saveAnswerDraft: mocks.saveAnswerDraft,
  submitAnswerSheet: mocks.submitAnswerSheet,
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn<() => Promise<string>>().mockResolvedValue('confirm') },
  }
})
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: mocks.routeParams }),
  useRouter: () => ({ push: mocks.routerPush, back: () => undefined }),
}))

const paper = {
  assessmentId: '9001',
  title: '期中考核',
  startAt: '2026-10-24T08:00:00+08:00',
  endAt: '2099-01-01T10:00:00+08:00',
  items: [
    { position: 1, type: 'QUIZ', refId: 'q1', score: 5, quizType: 'SINGLE_CHOICE', stem: '单选题干', options: ['甲', '乙', '丙'], tags: [] },
    { position: 2, type: 'QUIZ', refId: 'q2', score: 5, quizType: 'MULTI_CHOICE', stem: '多选题干', options: ['A1', 'B1', 'C1'], tags: [] },
    { position: 3, type: 'QUIZ', refId: 'q3', score: 5, quizType: 'TRUE_FALSE', stem: '判断题干', options: null, tags: [] },
    { position: 4, type: 'QUIZ', refId: 'q4', score: 5, quizType: 'FILL_BLANK', stem: '填空题干', options: null, tags: [] },
    { position: 5, type: 'PROGRAM', refId: 'p1', score: 20, problemTitle: 'A+B 问题', difficulty: 'EASY' },
  ],
  mySheet: null,
}

async function mountPaper(mySheet: unknown = null) {
  mocks.getAssessmentPaper.mockReset().mockResolvedValue({ ...paper, mySheet })
  const wrapper = mount(ExamPaperView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('ExamPaperView 学生试卷', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mocks.saveAnswerDraft.mockReset().mockResolvedValue({})
    mocks.submitAnswerSheet.mockReset().mockResolvedValue({ status: 'SUBMITTED', answeredCount: 4, totalScore: null, submittedAt: 'x', items: null })
    mocks.routerPush.mockReset()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('按顺序渲染四题型与程序题卡片，程序题可跳转作答', async () => {
    const wrapper = await mountPaper()
    expect(wrapper.text()).toContain('单选题干')
    expect(wrapper.text()).toContain('多选题干')
    expect(wrapper.text()).toContain('判断题干')
    expect(wrapper.text()).toContain('填空题干')
    expect(wrapper.text()).toContain('A+B 问题')
    expect(wrapper.find('[data-test="fill-4"]').exists()).toBe(true)

    await wrapper.get('[data-test="solve-5"]').trigger('click')
    expect(mocks.routerPush).toHaveBeenCalledWith({
      name: 'oj-submit-code',
      params: { assessmentId: '9001', problemId: 'p1' },
    })
  })

  it('作答 2 秒防抖后整体暂存草稿', async () => {
    const wrapper = await mountPaper()
    await wrapper.find('input[value="B"]').setValue(true)
    expect(mocks.saveAnswerDraft).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(2100)
    expect(mocks.saveAnswerDraft).toHaveBeenCalledWith('9001', { 1: 'B' })
  })

  it('已交卷只读：控件禁用、显示已交卷、交卷按钮不可用', async () => {
    const wrapper = await mountPaper({ status: 'SUBMITTED', answers: { 1: 'A' } })
    expect(wrapper.text()).toContain('已交卷')
    expect(wrapper.get('[data-test="fill-4"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="submit-paper"]').attributes('disabled')).toBeDefined()
  })

  it('交卷需确认并带幂等键，成功后锁定', async () => {
    const wrapper = await mountPaper()
    await wrapper.find('input[value="B"]').setValue(true)
    await vi.advanceTimersByTimeAsync(2100)
    await wrapper.get('[data-test="submit-paper"]').trigger('click')
    await flushPromises()

    expect(mocks.submitAnswerSheet).toHaveBeenCalledWith('9001', expect.any(String))
    expect(wrapper.text()).toContain('已交卷')
    expect(wrapper.get('[data-test="fill-4"]').attributes('disabled')).toBeDefined()
  })

  it('试卷不可读时提示不可用', async () => {
    mocks.getAssessmentPaper.mockReset().mockRejectedValue(new Error('404'))
    const wrapper = mount(ExamPaperView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('试卷不可用')
  })
})
