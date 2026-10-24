import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ProblemEditView from '@/views/oj/ProblemEditView.vue'

const mocks = vi.hoisted(() => ({
  createAssessmentProblem: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  deleteAssessmentProblem: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  getAssessmentProblem: vi.fn<(id: string) => Promise<unknown>>(),
  getAssessmentProblemTestCases: vi.fn<(id: string) => Promise<unknown>>(),
  retireAssessmentProblem: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  updateAssessmentProblem: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
  routerReplace: vi.fn<(route: unknown) => Promise<void>>(),
  routeParams: { params: {} as Record<string, string> },
}))

vi.mock('@/api/modules/oj', () => ({
  createAssessmentProblem: mocks.createAssessmentProblem,
  deleteAssessmentProblem: mocks.deleteAssessmentProblem,
  getAssessmentProblem: mocks.getAssessmentProblem,
  getAssessmentProblemTestCases: mocks.getAssessmentProblemTestCases,
  retireAssessmentProblem: mocks.retireAssessmentProblem,
  updateAssessmentProblem: mocks.updateAssessmentProblem,
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn<() => Promise<string>>().mockResolvedValue('confirm') },
  }
})

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: mocks.routeParams.params }),
  useRouter: () => ({
    replace: mocks.routerReplace,
    push: () => Promise.resolve(),
    back: () => undefined,
  }),
}))

const functionProblem = {
  id: '9101',
  title: '两数之和',
  summary: '基础题',
  description: '给定数组',
  inputFormat: '无',
  outputFormat: '下标',
  examples: [],
  difficulty: 'EASY',
  tags: ['数组'],
  timeLimitMs: 1000,
  memoryLimitKb: 65536,
  allowedLanguages: ['java'],
  judgeMode: 'FUNCTION',
  functionName: 'twoSum',
  parameterTypes: ['int[]', 'int'],
  returnType: 'int[]',
  status: 'DRAFT',
  version: 1,
  createdAt: '2026-10-14T00:00:00+08:00',
  updatedAt: '2026-10-14T00:00:00+08:00',
}

describe('ProblemEditView', () => {
  beforeEach(() => {
    mocks.routeParams.params = { problemId: '9101' }
    mocks.getAssessmentProblem.mockReset()
    mocks.getAssessmentProblemTestCases.mockReset()
    mocks.deleteAssessmentProblem.mockReset().mockResolvedValue(null)
    mocks.routerReplace.mockReset().mockResolvedValue(undefined)
  })

  it('编辑时加载两套测试点并展示独立编辑区', async () => {
    mocks.getAssessmentProblem.mockResolvedValue(functionProblem)
    mocks.getAssessmentProblemTestCases.mockResolvedValue({
      assessmentTestCases: [{ position: 1, input: '[[2,7,11,15],9]', expectedOutput: '[0,1]' }],
      practiceTestCases: [],
    })

    const wrapper = mount(ProblemEditView, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(mocks.getAssessmentProblemTestCases).toHaveBeenCalledWith('9101')
    expect(wrapper.text()).toContain('正式考核测试点')
    expect(wrapper.text()).toContain('练习测试点')
    expect(
      wrapper.findAll('textarea').some((input) => input.element.value === '[[2,7,11,15],9]'),
    ).toBe(true)
    expect(wrapper.text()).toContain('此测试点在正式考核中对学员隐藏')
  })

  it('新建题目默认函数传参，页面不提供命令行读写入口', async () => {
    mocks.routeParams.params = {}
    const wrapper = mount(ProblemEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('函数传参（力扣风格）')
    expect(wrapper.text()).toContain('新建题目统一为函数传参模式')
    expect(wrapper.text()).not.toContain('命令行读写')
  })

  it('编辑存量 STDIO 题目时展示旧模式标签且仍可编辑', async () => {
    mocks.getAssessmentProblem.mockResolvedValue({ ...functionProblem, judgeMode: 'STDIO', functionName: null, parameterTypes: null, returnType: null })
    mocks.getAssessmentProblemTestCases.mockResolvedValue({
      assessmentTestCases: [{ position: 1, input: '4 9\n2 7 11 15', expectedOutput: '0 1' }],
      practiceTestCases: [],
    })

    const wrapper = mount(ProblemEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('命令行读写（存量旧模式）')
    expect(wrapper.text()).toContain('历史存量题目')
  })

  it('删除题目需要确认，确认后带幂等键提交并返回列表', async () => {
    mocks.getAssessmentProblem.mockResolvedValue(functionProblem)
    mocks.getAssessmentProblemTestCases.mockResolvedValue({
      assessmentTestCases: [{ position: 1, input: '[[2,7,11,15],9]', expectedOutput: '[0,1]' }],
      practiceTestCases: [],
    })
    const wrapper = mount(ProblemEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const deleteButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('删除题目'))
    expect(deleteButton).toBeDefined()
    await deleteButton!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(mocks.deleteAssessmentProblem).toHaveBeenCalledWith(
      '9101',
      { version: 1 },
      expect.any(String),
    )
    expect(mocks.routerReplace).toHaveBeenCalledWith({ name: 'oj-problems' })
  })
})
