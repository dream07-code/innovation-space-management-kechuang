import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import CodeSubmitView from '@/views/oj/CodeSubmitView.vue'

const mocks = vi.hoisted(() => ({
  getAssessmentProblem: vi.fn<(id: string) => Promise<unknown>>(),
  getAssessment: vi.fn<(id: string) => Promise<unknown>>(),
  getPracticeTestCases: vi.fn<(id: string) => Promise<unknown>>(),
  runPractice: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  createAssessmentSubmission: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  getAssessmentProblem: mocks.getAssessmentProblem,
  getAssessment: mocks.getAssessment,
  getPracticeTestCases: mocks.getPracticeTestCases,
  runPractice: mocks.runPractice,
  createAssessmentSubmission: mocks.createAssessmentSubmission,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { problemId: '9101' } }),
  useRouter: () => ({ back: () => undefined, push: vi.fn<(route: unknown) => void>() }),
}))

describe('CodeSubmitView 练习模式', () => {
  it('展示练习测试点并运行安全练习结果', async () => {
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
    mocks.runPractice.mockResolvedValue({
      verdict: 'ACCEPTED',
      passedTestCases: 1,
      totalTestCases: 1,
      timeMs: 12,
      memoryKb: 1024,
      safeError: null,
      testCaseResults: [{
        position: 1,
        input: 'practice-input',
        expectedOutput: 'P',
        verdict: 'ACCEPTED',
        timeMs: 12,
        memoryKb: 1024,
        safeError: null,
      }],
    })

    const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('题库练习模式')
    expect(wrapper.text()).toContain('practice-input')
    await wrapper.find('textarea').setValue('print(1)')
    await wrapper.get('[data-test="practice-run"]').trigger('click')
    await flushPromises()

    expect(mocks.runPractice).toHaveBeenCalledWith('9101', { language: 'python', code: 'print(1)' }, expect.any(String))
    expect(wrapper.text()).toContain('练习运行结果')
    expect(wrapper.text()).toContain('P')
    expect(wrapper.text()).toContain('1 / 1')
  })
})
