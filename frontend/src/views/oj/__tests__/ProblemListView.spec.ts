import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ProblemListView from '@/views/oj/ProblemListView.vue'

const mocks = vi.hoisted(() => ({
  listAssessmentProblems: vi.fn<(query?: unknown) => Promise<unknown>>(),
  importAssessmentProblems: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  deleteAssessmentProblem: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  readOjImportFile: vi.fn<(file: File) => Promise<unknown>>(),
  generateAiProblem: vi.fn<(payload: unknown) => Promise<unknown>>(),
  push: vi.fn<(location: unknown) => Promise<unknown>>(),
  auth: { isAdmin: true, isLeader: false, isStudent: false },
}))

vi.mock('@/api/modules/oj', () => ({
  listAssessmentProblems: mocks.listAssessmentProblems,
  importAssessmentProblems: mocks.importAssessmentProblems,
  deleteAssessmentProblem: mocks.deleteAssessmentProblem,
  generateAiProblem: mocks.generateAiProblem,
}))
vi.mock('@/utils/ojImport', () => ({ readOjImportFile: mocks.readOjImportFile }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => mocks.auth,
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn<() => Promise<string>>().mockResolvedValue('confirm') },
  }
})
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push, back: () => undefined }),
}))

const draftProblem = {
  id: '31',
  title: '滑动窗口最大值',
  summary: '单调队列',
  description: '略',
  inputFormat: '无',
  outputFormat: '数组',
  examples: [],
  difficulty: 'MEDIUM',
  tags: ['队列'],
  timeLimitMs: 1000,
  memoryLimitKb: 262144,
  allowedLanguages: ['cpp', 'java', 'python'],
  judgeMode: 'FUNCTION',
  functionName: 'maxSlidingWindow',
  parameterTypes: ['int[]', 'int'],
  returnType: 'int[]',
  status: 'DRAFT',
  version: 2,
  createdAt: '2026-10-24T00:00:00+08:00',
  updatedAt: '2026-10-24T00:00:00+08:00',
}

describe('ProblemListView', () => {
  beforeEach(() => {
    mocks.listAssessmentProblems.mockReset()
    mocks.importAssessmentProblems.mockReset()
    mocks.deleteAssessmentProblem.mockReset().mockResolvedValue(null)
    mocks.readOjImportFile.mockReset()
    mocks.generateAiProblem.mockReset()
    mocks.push.mockReset().mockResolvedValue(undefined)
    mocks.auth.isAdmin = true
    mocks.auth.isLeader = false
    mocks.auth.isStudent = false
  })

  it('工作人员选择 JSON 后预览并在确认时带幂等键提交', async () => {
    mocks.listAssessmentProblems.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    const payload = { problems: [{ title: '导入题' }] }
    mocks.readOjImportFile.mockResolvedValue({ payload, errors: [] })
    mocks.importAssessmentProblems.mockResolvedValue({ code: 'OK', message: 'Success', data: { importedCount: 1, problemIds: ['1'], errors: [] } })

    const wrapper = mount(ProblemListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('批量导入')
    const input = wrapper.get('input[type="file"]')
    const file = new File(['{}'], 'problems.json', { type: 'application/json' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()

    expect(mocks.readOjImportFile).toHaveBeenCalledWith(file)
    expect(wrapper.text()).toContain('导入预览')
    expect(wrapper.text()).toContain('导入题')
    await wrapper.get('[data-test="confirm-import"]').trigger('click')
    await flushPromises()

    expect(mocks.importAssessmentProblems).toHaveBeenCalledWith(payload, expect.any(String))
    expect(mocks.listAssessmentProblems).toHaveBeenCalledTimes(2)
  })

  it('工作人员看不到 AI 出题入口', async () => {
    mocks.listAssessmentProblems.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    const wrapper = mount(ProblemListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="ai-generate"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mine-toggle"]').exists()).toBe(false)
  })

  it('学生生成 AI 题目后跳转到详情页，列表标出 AI 生成', async () => {
    mocks.auth.isAdmin = false
    mocks.auth.isStudent = true
    mocks.listAssessmentProblems.mockResolvedValue({
      items: [{ ...draftProblem, source: 'AI', status: 'PUBLISHED' }],
      page: 1,
      size: 20,
      total: 1,
    })
    mocks.generateAiProblem.mockResolvedValue({
      problem: { id: '88', title: 'AI 新题' },
      referenceCode: 'class Solution {}',
      testCases: [],
    })

    const wrapper = mount(ProblemListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('AI 生成')
    await wrapper.get('[data-test="ai-generate"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="ai-topic"]').setValue('二分查找')
    await wrapper.get('[data-test="ai-submit"]').trigger('click')
    await flushPromises()

    expect(mocks.generateAiProblem).toHaveBeenCalledWith({
      topic: '二分查找',
      difficulty: 'MEDIUM',
      category: null,
    })
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'oj-problem-detail',
      params: { problemId: '88' },
    })
  })

  it('切到「我的题库」按 mine=true 重新拉取', async () => {
    mocks.auth.isAdmin = false
    mocks.auth.isStudent = true
    mocks.listAssessmentProblems.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    const wrapper = mount(ProblemListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(mocks.listAssessmentProblems).toHaveBeenLastCalledWith({})
    const radios = wrapper.findAll('[data-test="mine-toggle"] input[type="radio"]')
    const mineRadio = radios[1]
    expect(mineRadio).toBeDefined()
    await mineRadio!.setValue(true)
    await flushPromises()

    expect(mocks.listAssessmentProblems).toHaveBeenLastCalledWith({ mine: true })
  })

  it('页头展示函数传裁判题提示', async () => {
    mocks.listAssessmentProblems.mockResolvedValue({ items: [draftProblem], page: 1, size: 20, total: 1 })
    const wrapper = mount(ProblemListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('函数传参（力扣风格）判题')
    expect(wrapper.text()).toContain('历史命令行读写题目仍可正常练习与判题')
  })

  it('工作人员删除题目需确认，确认后带版本与幂等键提交并刷新', async () => {
    mocks.listAssessmentProblems.mockResolvedValue({ items: [draftProblem], page: 1, size: 20, total: 1 })
    const wrapper = mount(ProblemListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="delete-31"]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(mocks.deleteAssessmentProblem).toHaveBeenCalledWith(
      '31',
      { version: 2 },
      expect.any(String),
    )
    expect(mocks.listAssessmentProblems).toHaveBeenCalledTimes(2)
  })
})
