import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import LeetcodeTop100View from '@/views/oj/LeetcodeTop100View.vue'

const mocks = vi.hoisted(() => ({
  push: vi.fn<(location: unknown) => Promise<unknown>>(),
  generateAiProblem: vi.fn<(payload: unknown) => Promise<unknown>>(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push, back: () => undefined }),
}))
vi.mock('@/api/modules/oj', () => ({
  generateAiProblem: mocks.generateAiProblem,
}))

function mountView() {
  return mount(LeetcodeTop100View, { global: { plugins: [ElementPlus] } })
}

describe('LeetcodeTop100View', () => {
  beforeEach(() => {
    mocks.push.mockReset().mockResolvedValue(undefined)
    mocks.generateAiProblem.mockReset()
  })

  it('默认展示 100 道题，含已收录与 AI 生成两种状态', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('力扣 Top 100 题单')
    expect(wrapper.get('[data-test="row-count"]').text()).toContain('100')
    expect(wrapper.text()).toContain('已收录 26 道')
    expect(wrapper.text()).toContain('AI 生成 74 道')
    expect(wrapper.text()).toContain('两数之和')
    expect(wrapper.text()).toContain('N 皇后')
  })

  it('筛到「已收录」只剩 26 道，且能进题库详情', async () => {
    const wrapper = mountView()
    await flushPromises()

    const radios = wrapper.findAll('[data-test="state-filter"] input[type="radio"]')
    const collectedRadio = radios[1]
    expect(collectedRadio).toBeDefined()
    await collectedRadio!.setValue(true)
    await flushPromises()

    expect(wrapper.get('[data-test="row-count"]').text()).toContain('26')
    await wrapper.get('[data-test="open-1"]').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'oj-problem-detail',
      params: { problemId: '1' },
    })
  })

  it('未收录的题按该题知识点与难度预填 AI 出题弹窗', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 第 4 题「移动零」：简单 / 双指针 / 数组。
    await wrapper.get('[data-test="ai-4"]').trigger('click')
    await flushPromises()

    const topic = wrapper.get('[data-test="ai-topic"]')
    expect((topic.element as HTMLInputElement).value).toBe('双指针')
    const category = wrapper.get('[data-test="ai-category"]')
    expect((category.element as HTMLInputElement).value).toBe('数组')
    const checked = wrapper
      .findAll('[data-test="ai-difficulty"] .el-radio')
      .filter((radio) => radio.classes().includes('is-checked'))
    expect(checked).toHaveLength(1)
    expect(checked[0]!.text()).toContain('简单')
  })

  it('生成成功后跳到新题目详情页', async () => {
    mocks.generateAiProblem.mockResolvedValue({
      problem: { id: '99', title: '移动零（AI）' },
      referenceCode: 'class Solution {}',
      testCases: [],
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="ai-4"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="ai-submit"]').trigger('click')
    await flushPromises()

    expect(mocks.generateAiProblem).toHaveBeenCalledWith({
      topic: '双指针',
      difficulty: 'EASY',
      category: '数组',
    })
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'oj-problem-detail',
      params: { problemId: '99' },
    })
  })
})
