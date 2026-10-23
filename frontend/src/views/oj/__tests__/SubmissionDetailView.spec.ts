import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import SubmissionDetailView from '@/views/oj/SubmissionDetailView.vue'

const mocks = vi.hoisted(() => ({
  getAssessmentSubmission: vi.fn<(id: string) => Promise<unknown>>(),
  getAssessment: vi.fn<(id: string) => Promise<unknown>>(),
  getAssessmentProblem: vi.fn<(id: string) => Promise<unknown>>(),
  listAssessmentReviews: vi.fn<(id: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  getAssessmentSubmission: mocks.getAssessmentSubmission,
  getAssessment: mocks.getAssessment,
  getAssessmentProblem: mocks.getAssessmentProblem,
  listAssessmentReviews: mocks.listAssessmentReviews,
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: false, isLeader: false }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { submissionId: '9201' } }),
  useRouter: () => ({ push: vi.fn<(route: unknown) => void>() }),
}))

describe('SubmissionDetailView 安全逐测试点结果', () => {
  it('展示总体结果和逐测试点摘要但不展示隐藏输入输出', async () => {
    mocks.getAssessmentSubmission.mockResolvedValue({
      id: '9201',
      assessmentId: '9001',
      problemId: '9101',
      memberId: '201',
      language: 'python',
      status: 'JUDGED',
      codeHash: 'hash',
      version: 2,
      submittedAt: '2026-10-14T00:00:00+08:00',
      judgedAt: '2026-10-14T00:00:01+08:00',
      judgeResult: {
        verdict: 'ACCEPTED',
        passedTestCases: 1,
        totalTestCases: 1,
        timeMs: 12,
        memoryKb: 1024,
        safeError: null,
        testCaseResults: [{
          position: 1,
          verdict: 'ACCEPTED',
          timeMs: 12,
          memoryKb: 1024,
          safeError: null,
        }],
      },
    })
    mocks.getAssessment.mockResolvedValue({ id: '9001', title: '正式考核' })
    mocks.getAssessmentProblem.mockResolvedValue({ id: '9101', title: '正式题' })

    const wrapper = mount(SubmissionDetailView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('逐测试点结果')
    expect(wrapper.text()).toContain('1 / 1')
    expect(wrapper.text()).toContain('12 ms')
    expect(wrapper.text()).not.toContain('input')
    expect(wrapper.text()).not.toContain('expectedOutput')
  })
})
