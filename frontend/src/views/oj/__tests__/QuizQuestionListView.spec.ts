import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import QuizQuestionListView from '@/views/oj/QuizQuestionListView.vue'

const mocks = vi.hoisted(() => ({
  listQuizQuestions: vi.fn<(query?: unknown) => Promise<unknown>>(),
  retireQuizQuestion: vi.fn<(id: string, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/quiz', () => ({
  listQuizQuestions: mocks.listQuizQuestions,
  retireQuizQuestion: mocks.retireQuizQuestion,
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn<() => Promise<string>>().mockResolvedValue('confirm') },
  }
})
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn<(route: unknown) => void>() }),
}))

const singleChoice = {
  id: '101',
  type: 'SINGLE_CHOICE',
  stem: '二叉树第 k 层最多有多少个结点？',
  options: ['2k-1', '2k', '2^(k-1)', 'k^2'],
  answer: 'C',
  analysis: null,
  tags: ['树', '二叉树'],
  status: 'DRAFT',
  createdAt: '2026-10-24T08:00:00+08:00',
  updatedAt: '2026-10-24T08:00:00+08:00',
}

describe('QuizQuestionListView 客观题库', () => {
  beforeEach(() => {
    mocks.listQuizQuestions.mockReset().mockResolvedValue({ items: [singleChoice], page: 1, size: 100, total: 1 })
    mocks.retireQuizQuestion.mockReset().mockResolvedValue({})
  })

  it('渲染题型/题干/状态，题型与关键字过滤触发请求', async () => {
    const wrapper = mount(QuizQuestionListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('单选')
    expect(wrapper.text()).toContain('二叉树第 k 层最多有多少个结点？')
    expect(wrapper.text()).toContain('草稿')

    await wrapper.find('[data-test="keyword"]').setValue('二叉树')
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()
    expect(mocks.listQuizQuestions).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '二叉树' }))

    await wrapper.find('input[value="MULTI_CHOICE"]').setValue(true)
    await flushPromises()
    expect(mocks.listQuizQuestions).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: 'MULTI_CHOICE', keyword: '二叉树' }),
    )
  })

  it('停用客观题需确认并带幂等键，成功后刷新列表', async () => {
    const wrapper = mount(QuizQuestionListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="retire-101"]').trigger('click')
    await flushPromises()

    expect(mocks.retireQuizQuestion).toHaveBeenCalledWith('101', expect.any(String))
    expect(mocks.listQuizQuestions).toHaveBeenCalledTimes(2)
  })
})
