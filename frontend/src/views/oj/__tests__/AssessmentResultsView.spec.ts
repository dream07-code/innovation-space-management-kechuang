import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AssessmentResultsView from '@/views/oj/AssessmentResultsView.vue'

const auth = vi.hoisted(() => ({ isAdmin: true, isLeader: false }))
const push = vi.hoisted(() => vi.fn<(route: unknown) => void>())

const mocks = vi.hoisted(() => ({
  getAssessment: vi.fn<(id: string) => Promise<unknown>>(),
  listAssessmentProblem: vi.fn<() => Promise<unknown>>(),
  listAssessmentResults: vi.fn<(id: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  getAssessment: mocks.getAssessment,
  listAssessmentProblems: mocks.listAssessmentProblem,
  listAssessmentResults: mocks.listAssessmentResults,
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => auth,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { assessmentId: '9001' }, fullPath: '/oj/assessments/9001/results' }),
  useRouter: () => ({ push }),
}))

const failedResult = {
  assessmentId: '9001',
  submissionId: '9201',
  problemId: '9101',
  memberId: '201',
  memberName: '郑永康',
  memberStudentNo: '202502110229',
  technicalVerdict: 'ACCEPTED',
  passedTestCases: 7,
  totalTestCases: 7,
  timeMs: 146,
  memoryKb: 32768,
  autoScore: 100,
  humanScore: null,
  finalScore: 100,
  scoreSource: 'AUTO',
  resultStatus: 'COMPLETE',
  publishedAt: '2026-10-23T15:46:38+08:00',
}

describe('AssessmentResultsView 评分来源与人工评分入口', () => {
  beforeEach(() => {
    auth.isAdmin = true
    auth.isLeader = false
    push.mockReset()
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'PUBLISHED',
    })
    mocks.listAssessmentProblem.mockResolvedValue({ items: [{ id: '9101', title: '矩阵快速幂求斐波那契' }] })
    mocks.listAssessmentResults.mockResolvedValue({
      items: [failedResult],
      page: 1,
      size: 20,
      total: 1,
    })
  })

  it('按通过的测试点显示自动分与计分规则，不再出现 AI 评分列', async () => {
    const wrapper = mount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('自动分')
    expect(wrapper.text()).toContain('100 分')
    expect(wrapper.text()).toContain('按测试点')
    expect(wrapper.text()).toContain('自动分按通过的测试点比例计算')
    expect(wrapper.text()).not.toContain('AI 评分')
  })

  it('没有自动分时提示需要人工评分', async () => {
    mocks.listAssessmentResults.mockResolvedValue({
      items: [
        {
          ...failedResult,
          autoScore: null,
          finalScore: null,
          scoreSource: 'PENDING',
          resultStatus: 'NEEDS_REVIEW',
          technicalVerdict: 'INTERNAL_ERROR',
        },
      ],
      page: 1,
      size: 20,
      total: 1,
    })
    const wrapper = mount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('有 1 条提交没有自动分')
    expect(wrapper.text()).toContain('需要人工评分')
  })

  it('管理员可以从结果页直接进入人工评分并带回跳地址', async () => {
    const wrapper = mount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const reviewButton = wrapper.findAll('button').find((item) => item.text().includes('评分'))
    expect(reviewButton).toBeTruthy()
    await reviewButton!.trigger('click')

    expect(push).toHaveBeenCalledWith({
      name: 'oj-submission-review',
      params: { submissionId: '9201' },
      query: { redirect: '/oj/assessments/9001/results' },
    })
  })

  it('学生看不到人工评分入口', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    const wrapper = mount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const reviewButton = wrapper.findAll('button').find((item) => item.text().includes('评分'))
    expect(reviewButton).toBeUndefined()
  })
})
