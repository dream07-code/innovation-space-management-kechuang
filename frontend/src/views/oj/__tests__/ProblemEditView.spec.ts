import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import ProblemEditView from '@/views/oj/ProblemEditView.vue'

const mocks = vi.hoisted(() => ({
  createAssessmentProblem: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  getAssessmentProblem: vi.fn<(id: string) => Promise<unknown>>(),
  getAssessmentProblemTestCases: vi.fn<(id: string) => Promise<unknown>>(),
  retireAssessmentProblem: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  updateAssessmentProblem: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  createAssessmentProblem: mocks.createAssessmentProblem,
  getAssessmentProblem: mocks.getAssessmentProblem,
  getAssessmentProblemTestCases: mocks.getAssessmentProblemTestCases,
  retireAssessmentProblem: mocks.retireAssessmentProblem,
  updateAssessmentProblem: mocks.updateAssessmentProblem,
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { problemId: '9101' } }),
  useRouter: () => ({ replace: () => Promise.resolve(), push: () => Promise.resolve(), back: () => undefined }),
}))

describe('ProblemEditView', () => {
  it('编辑时加载两套测试点并展示独立编辑区', async () => {
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '两数之和',
      summary: '基础题',
      description: '给定数组',
      inputFormat: '一行',
      outputFormat: '下标',
      examples: [],
      difficulty: 'EASY',
      tags: ['数组'],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['java'],
      status: 'DRAFT',
      version: 1,
      createdAt: '2026-10-14T00:00:00+08:00',
      updatedAt: '2026-10-14T00:00:00+08:00',
    })
    mocks.getAssessmentProblemTestCases.mockResolvedValue({
      assessmentTestCases: [{ position: 1, input: '隐藏输入', expectedOutput: '1' }],
      practiceTestCases: [],
    })

    const wrapper = mount(ProblemEditView, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(mocks.getAssessmentProblemTestCases).toHaveBeenCalledWith('9101')
    expect(wrapper.text()).toContain('正式考核测试点')
    expect(wrapper.text()).toContain('练习测试点')
    expect(wrapper.findAll('textarea').some((input) => input.element.value === '隐藏输入')).toBe(true)
    expect(wrapper.text()).toContain('此测试点在正式考核中对学员隐藏')
  })
})
