import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AiProblemDialog from '@/views/oj/AiProblemDialog.vue'
import { ApiError } from '@/api/errors'

const mocks = vi.hoisted(() => ({
  generateAiProblem: vi.fn<(payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  generateAiProblem: mocks.generateAiProblem,
}))

const generatedResult = {
  problem: { id: '77', title: '环形链表入口' },
  referenceCode: 'class Solution {}',
  testCases: [
    { position: 1, input: '[]', expectedOutput: '0', boundary: false },
    { position: 2, input: '[1]', expectedOutput: '0', boundary: true },
  ],
}

function mountDialog(props: Record<string, unknown> = {}) {
  return mount(AiProblemDialog, {
    props: { modelValue: true, ...props },
    global: { plugins: [ElementPlus] },
  })
}

describe('AiProblemDialog', () => {
  beforeEach(() => {
    mocks.generateAiProblem.mockReset()
  })

  it('预填入口带来的知识点与难度', async () => {
    const wrapper = mountDialog({ prefillTopic: '滑动窗口', prefillDifficulty: 'HARD' })
    await flushPromises()

    const input = wrapper.get('[data-test="ai-topic"]')
    expect((input.element as HTMLInputElement).value).toBe('滑动窗口')
  })

  it('生成中按钮置灰并 loading，避免重复提交', async () => {
    let resolveGeneration: (value: unknown) => void = () => undefined
    mocks.generateAiProblem.mockReturnValue(
      new Promise((resolve) => {
        resolveGeneration = resolve
      }),
    )

    const wrapper = mountDialog({ prefillTopic: '链表' })
    await flushPromises()

    await wrapper.get('[data-test="ai-submit"]').trigger('click')
    await flushPromises()

    expect(mocks.generateAiProblem).toHaveBeenCalledTimes(1)
    const button = wrapper.get('[data-test="ai-submit"]')
    expect(button.attributes('disabled')).toBeDefined()
    // 生成中再点也不会重复发请求。
    await button.trigger('click')
    expect(mocks.generateAiProblem).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('正在生成并自测')

    resolveGeneration(generatedResult)
    await flushPromises()

    expect(wrapper.emitted('generated')?.[0]).toEqual(['77'])
    const visibilityEvents = wrapper.emitted('update:modelValue') ?? []
    expect(visibilityEvents[visibilityEvents.length - 1]).toEqual([false])
  })

  it('知识点为空时不发请求，题型留空传 null', async () => {
    mocks.generateAiProblem.mockResolvedValue(generatedResult)
    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.get('[data-test="ai-submit"]').trigger('click')
    await flushPromises()
    expect(mocks.generateAiProblem).not.toHaveBeenCalled()

    await wrapper.get('[data-test="ai-topic"]').setValue(' 贪心 ')
    await wrapper.get('[data-test="ai-submit"]').trigger('click')
    await flushPromises()

    expect(mocks.generateAiProblem).toHaveBeenCalledWith({
      topic: '贪心',
      difficulty: 'MEDIUM',
      category: null,
    })
  })

  it('生成失败时展示服务端给出的具体原因，并恢复可点状态', async () => {
    mocks.generateAiProblem.mockRejectedValue(
      new ApiError({
        code: 'SERVICE_UNAVAILABLE',
        httpStatus: 503,
        message: 'AI 生成的题目未通过自测，请稍后换个知识点重试',
      }),
    )
    const wrapper = mountDialog({ prefillTopic: '背包' })
    await flushPromises()

    await wrapper.get('[data-test="ai-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('generated')).toBeUndefined()
    expect(wrapper.get('[data-test="ai-submit"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('正在生成并自测')
  })
})
