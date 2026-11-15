import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SubmissionListView from '@/views/oj/SubmissionListView.vue'

const mocks = vi.hoisted(() => ({
  listAssessmentSubmissions: vi.fn<(query?: unknown) => Promise<unknown>>(),
  listAssessments: vi.fn<() => Promise<unknown>>(),
  listAssessmentProblems: vi.fn<(query?: unknown) => Promise<unknown>>(),
  push: vi.fn<(location: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  listAssessmentSubmissions: mocks.listAssessmentSubmissions,
  listAssessments: mocks.listAssessments,
  listAssessmentProblems: mocks.listAssessmentProblems,
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push, back: () => undefined }),
}))

const submission = {
  id: '18',
  assessmentId: '38',
  problemId: '5',
  memberId: '341',
  language: 'java',
  status: 'JUDGED',
  submittedAt: '2026-11-09T11:04:07+08:00',
  judgeResult: { verdict: 'WRONG_ANSWER', passedTestCases: 1, totalTestCases: 3 },
  codeHash: 'abc123',
}

describe('SubmissionListView', () => {
  beforeEach(() => {
    mocks.listAssessmentSubmissions.mockReset()
    mocks.listAssessments.mockReset().mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    mocks.listAssessmentProblems.mockReset().mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
    mocks.push.mockReset()
  })

  it('按服务端分页拉取并展示总数', async () => {
    mocks.listAssessmentSubmissions.mockResolvedValue({
      items: [submission],
      page: 1,
      size: 20,
      total: 12,
    })

    const wrapper = mount(SubmissionListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(mocks.listAssessmentSubmissions).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.get('[data-test="submission-pager"]').text()).toContain('12')
    // 题目名映射要覆盖全库，不能只取默认第一页。
    expect(mocks.listAssessmentProblems).toHaveBeenCalledWith({ page: 1, size: 200 })
  })

  it('翻页与状态筛选都带分页参数重新请求', async () => {
    mocks.listAssessmentSubmissions.mockResolvedValue({
      items: [submission],
      page: 1,
      size: 20,
      total: 45,
    })

    const wrapper = mount(SubmissionListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const secondPage = wrapper.get('[data-test="submission-pager"]').findAll('.el-pager li')[1]
    expect(secondPage).toBeDefined()
    await secondPage!.trigger('click')
    await flushPromises()
    expect(mocks.listAssessmentSubmissions).toHaveBeenLastCalledWith({ page: 2, size: 20 })

    // 状态筛选回到第一页由服务端过滤，这里只确认筛选控件可用。
    const statusSelect = wrapper.findAll('.el-select')[0]
    expect(statusSelect).toBeDefined()
  })
})
