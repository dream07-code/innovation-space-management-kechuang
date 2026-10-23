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
  aiScore: null,
  humanScore: null,
  finalScore: null,
  scoreSource: 'UNAVAILABLE',
  resultStatus: 'NEEDS_REVIEW',
  aiStatus: 'FAILED',
  aiReason: 'AI 评分调用失败：AI provider HTTP 401',
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

  it('AI 评分失败时说明“暂不可用”的含义与失败原因', async () => {
    const wrapper = mount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('暂不可用')
    expect(wrapper.text()).toContain('有 1 条提交的 AI 评分没有成功')
    expect(wrapper.text()).toContain('AI provider HTTP 401')
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
    expect(wrapper.text()).toContain('请联系方向负责人或学院管理员人工评分')
  })
})
