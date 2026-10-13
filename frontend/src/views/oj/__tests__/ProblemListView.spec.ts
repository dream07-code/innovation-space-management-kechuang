import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import ProblemListView from '@/views/oj/ProblemListView.vue'

const mocks = vi.hoisted(() => ({
  listAssessmentProblems: vi.fn<() => Promise<unknown>>(),
  importAssessmentProblems: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  readOjImportFile: vi.fn<(file: File) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  listAssessmentProblems: mocks.listAssessmentProblems,
  importAssessmentProblems: mocks.importAssessmentProblems,
}))
vi.mock('@/utils/ojImport', () => ({ readOjImportFile: mocks.readOjImportFile }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: true, isLeader: false }),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: () => Promise.resolve(), back: () => undefined }),
}))

describe('ProblemListView 批量导入', () => {
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
})
