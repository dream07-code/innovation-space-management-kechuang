import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import QuizQuestionEditView from '@/views/oj/QuizQuestionEditView.vue'

const mocks = vi.hoisted(() => ({
  routeParams: {} as Record<string, string | undefined>,
  createQuizQuestion: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  updateQuizQuestion: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
  getQuizQuestion: vi.fn<(id: string) => Promise<unknown>>(),
  routerPush: vi.fn<(route: unknown) => void>(),
}))

vi.mock('@/api/modules/quiz', () => ({
  createQuizQuestion: mocks.createQuizQuestion,
  updateQuizQuestion: mocks.updateQuizQuestion,
  getQuizQuestion: mocks.getQuizQuestion,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: mocks.routeParams }),
  useRouter: () => ({ push: mocks.routerPush, back: () => undefined }),
}))

function fill(wrapper: ReturnType<typeof mount>, selector: string, value: string) {
  return wrapper.find(selector).setValue(value)
}

describe('QuizQuestionEditView 四题型表单', () => {
  beforeEach(() => {
    mocks.routeParams.questionId = undefined
    mocks.createQuizQuestion.mockReset().mockResolvedValue({})
    mocks.updateQuizQuestion.mockReset().mockResolvedValue({})
    mocks.getQuizQuestion.mockReset()
    mocks.routerPush.mockReset()
  })

  it('单选题提交选项数组与字母答案，标签按顿号拆分', async () => {
    const wrapper = mount(QuizQuestionEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('选项 A')
    await fill(wrapper, '[data-test="stem"]', '二叉树第 k 层最多有多少个结点？')
    await fill(wrapper, '[data-test="option-0"]', '2k-1')
    await fill(wrapper, '[data-test="option-1"]', '2^(k-1)')
    await wrapper.find('input[value="B"]').setValue(true)
    await fill(wrapper, '[data-test="tags"]', '树、二叉树')
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.createQuizQuestion).toHaveBeenCalledWith(
      {
        type: 'SINGLE_CHOICE',
        stem: '二叉树第 k 层最多有多少个结点？',
        options: ['2k-1', '2^(k-1)'],
        answer: 'B',
        analysis: null,
        tags: ['树', '二叉树'],
      },
      expect.any(String),
    )
  })

  it('多选题答案按字母升序提交', async () => {
    const wrapper = mount(QuizQuestionEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('input[value="MULTI_CHOICE"]').setValue(true)
    await fill(wrapper, '[data-test="stem"]', '下列属于广义表的是？')
    await fill(wrapper, '[data-test="option-0"]', '(a, b)')
    await fill(wrapper, '[data-test="option-1"]', 'a')
    await fill(wrapper, '[data-test="option-2"]', '((a))')
    await wrapper.find('input[value="B"]').setValue(true)
    await wrapper.find('input[value="A"]').setValue(true)
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.createQuizQuestion).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'MULTI_CHOICE', answer: ['A', 'B'] }),
      expect.any(String),
    )
  })

  it('判断题不显示选项区并以布尔提交', async () => {
    const wrapper = mount(QuizQuestionEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('input[value="TRUE_FALSE"]').setValue(true)
    expect(wrapper.find('[data-test="option-0"]').exists()).toBe(false)
    await fill(wrapper, '[data-test="stem"]', '空串即是空白串。')
    await wrapper.find('input[value="false"]').setValue(true)
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.createQuizQuestion).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'TRUE_FALSE', answer: false, options: null }),
      expect.any(String),
    )
  })

  it('填空题按行拆分备选答案并去空行', async () => {
    const wrapper = mount(QuizQuestionEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('input[value="FILL_BLANK"]').setValue(true)
    await fill(wrapper, '[data-test="stem"]', 'AVL 树是最早的____树。')
    await fill(wrapper, '[data-test="fill-answers"]', '自平衡二叉搜索\n 自平衡 \n\n')
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.createQuizQuestion).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'FILL_BLANK', answer: ['自平衡二叉搜索', '自平衡'], options: null }),
      expect.any(String),
    )
  })

  it('编辑态回填多选题且非 DRAFT 禁用保存', async () => {
    mocks.routeParams.questionId = '201'
    mocks.getQuizQuestion.mockResolvedValue({
      id: '201',
      type: 'MULTI_CHOICE',
      stem: '下列属于无损编码的是？',
      options: ['哈夫曼编码', 'PCM', '行程编码', 'DPCM'],
      answer: ['A', 'C'],
      analysis: '哈夫曼与行程编码可逆。',
      tags: ['编码'],
      status: 'DRAFT',
      createdAt: '',
      updatedAt: '',
    })
    const wrapper = mount(QuizQuestionEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="stem"]').element as HTMLTextAreaElement).toBeTruthy()
    expect((wrapper.find('input[value="A"]').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.find('input[value="C"]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.get('[data-test="save"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('input[value="SINGLE_CHOICE"]').attributes('disabled')).toBeDefined()

    await fill(wrapper, '[data-test="stem"]', '下列属于无损编码的是（多选）？')
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()
    expect(mocks.updateQuizQuestion).toHaveBeenCalledWith('201', expect.objectContaining({ stem: '下列属于无损编码的是（多选）？' }))

    mocks.routeParams.questionId = '202'
    mocks.getQuizQuestion.mockResolvedValue({
      id: '202',
      type: 'SINGLE_CHOICE',
      stem: '已发布题',
      options: ['a', 'b'],
      answer: 'A',
      analysis: null,
      tags: [],
      status: 'PUBLISHED',
      createdAt: '',
      updatedAt: '',
    })
    const locked = mount(QuizQuestionEditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(locked.get('[data-test="save"]').attributes('disabled')).toBeDefined()
  })
})
