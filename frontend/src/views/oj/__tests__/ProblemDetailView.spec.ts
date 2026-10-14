import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import ProblemDetailView from '@/views/oj/ProblemDetailView.vue'

const mocks = vi.hoisted(() => ({
  getAssessmentProblem: vi.fn<(id: string) => Promise<unknown>>(),
  getPracticeTestCases: vi.fn<(id: string) => Promise<unknown>>(),
  listAssessments: vi.fn<() => Promise<unknown>>(),
  retireAssessmentProblem: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  getAssessmentProblem: mocks.getAssessmentProblem,
  getPracticeTestCases: mocks.getPracticeTestCases,
  listAssessments: mocks.listAssessments,
  retireAssessmentProblem: mocks.retireAssessmentProblem,
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: false, isLeader: false, isStudent: true, user: { directionId: '1' } }),
}))
const push = vi.fn<(route: unknown) => void>()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { problemId: '9101' } }),
  useRouter: () => ({ push, back: () => undefined }),
}))

describe('ProblemDetailView 题库练习入口', () => {
  it('学生可以查看练习测试点并进入练习做题', async () => {
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '练习题',
      summary: '摘要',
      description: '题面',
      inputFormat: '输入',
      outputFormat: '输出',
      examples: [],
      difficulty: 'EASY',
      tags: [],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['python'],
      status: 'PUBLISHED',
      version: 1,
      createdAt: '2026-10-14T00:00:00+08:00',
      updatedAt: '2026-10-14T00:00:00+08:00',
    })
    mocks.getPracticeTestCases.mockResolvedValue({
      practiceTestCases: [{ position: 1, input: 'practice-input', expectedOutput: 'P' }],
    })

    const wrapper = mount(ProblemDetailView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="show-practice-test-cases"]').trigger('click')
    await flushPromises()

    expect(mocks.getPracticeTestCases).toHaveBeenCalledWith('9101')
    expect(wrapper.text()).toContain('仅用于题库练习')
    expect(wrapper.text()).toContain('practice-input')

    await wrapper.get('[data-test="practice-solve"]').trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'oj-practice-code', params: { problemId: '9101' } })
  })
})
