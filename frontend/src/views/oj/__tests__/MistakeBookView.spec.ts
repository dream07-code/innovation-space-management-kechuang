import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import MistakeBookView from '@/views/oj/MistakeBookView.vue'

const mocks = vi.hoisted(() => ({
  listMistakeBook: vi.fn<() => Promise<unknown>>(),
  getMistakeCause: vi.fn<(problemId: string) => Promise<unknown>>(),
  push: vi.fn<(location: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  listMistakeBook: mocks.listMistakeBook,
  getMistakeCause: mocks.getMistakeCause,
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push, back: () => undefined }),
}))

const practiceRow = {
  problemId: '6',
  title: '统计质数个数（中等）',
  difficulty: 'MEDIUM',
  tags: ['数学', '筛法'],
  source: 'PRACTICE',
  submissionId: null,
  assessmentId: null,
  attemptId: '72',
  language: 'python',
  verdict: 'WRONG_ANSWER',
  passedTestCases: 0,
  totalTestCases: 1,
  safeError: null,
  lastSubmittedAt: '2026-11-15T10:20:00+08:00',
}

const submissionRow = {
  problemId: '5',
  title: '两数之和（练习 5）',
  difficulty: 'EASY',
  tags: ['函数'],
  source: 'SUBMISSION',
  submissionId: '18',
  assessmentId: '37',
  attemptId: null,
  language: 'java',
  verdict: 'WRONG_ANSWER',
  passedTestCases: 1,
  totalTestCases: 3,
  safeError: null,
  lastSubmittedAt: '2026-11-09T11:04:07+08:00',
}

function mountView() {
  return mount(MistakeBookView, { global: { plugins: [ElementPlus] } })
}

describe('MistakeBookView', () => {
  beforeEach(() => {
    mocks.listMistakeBook.mockReset()
    mocks.getMistakeCause.mockReset()
    mocks.push.mockReset().mockResolvedValue(undefined)
  })

  it('按后端顺序展示错题，逐行加载 AI 错因', async () => {
    mocks.listMistakeBook.mockResolvedValue({ total: 2, items: [practiceRow, submissionRow] })
    mocks.getMistakeCause.mockImplementation(async (problemId: string) =>
      problemId === '6'
        ? { available: true, cause: '筛法边界写错：把 n 也当成了质数。', model: 'mimo', cached: false }
        : { available: false, error: '这次尝试没有保存代码（历史记录），无法生成 AI 错因。' },
    )

    const wrapper = mountView()
    await flushPromises()

    expect(mocks.listMistakeBook).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('统计质数个数（中等）')
    expect(wrapper.text()).toContain('题库练习')
    expect(wrapper.text()).toContain('考核提交')
    expect(wrapper.text()).toContain('2026-11-15 10:20:00')
    expect(wrapper.text()).toContain('筛法边界写错')
    // 拿不到 AI 错因时退回判题结论，不编造文案。
    expect(wrapper.text()).toContain('没有保存代码')
    expect(wrapper.text()).toContain('通过 1/3 个用例')
  })

  it('没有错题时显示空状态而不是空白页', async () => {
    mocks.listMistakeBook.mockResolvedValue({ total: 0, items: [] })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="mistake-empty"]').text()).toContain('还没有错题，继续加油')
    expect(mocks.getMistakeCause).not.toHaveBeenCalled()
  })

  it('重做：练习回练习页，考核提交回提交页并带 retry', async () => {
    mocks.listMistakeBook.mockResolvedValue({ total: 2, items: [practiceRow, submissionRow] })
    mocks.getMistakeCause.mockResolvedValue({ available: false, error: '暂无' })

    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="redo-6"]').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'oj-practice-code',
      params: { problemId: '6' },
      query: { retry: '1' },
    })

    await wrapper.get('[data-test="redo-5"]').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'oj-submit-code',
      params: { assessmentId: '37', problemId: '5' },
      query: { retry: '1' },
    })
  })
})
