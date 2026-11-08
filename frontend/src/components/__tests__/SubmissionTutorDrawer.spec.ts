import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SubmissionTutorDrawer from '@/components/SubmissionTutorDrawer.vue'
import { ApiError } from '@/api/errors'

const mocks = vi.hoisted(() => ({
  askSubmissionTutor: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  askSubmissionTutor: mocks.askSubmissionTutor,
}))

function mountDrawer(props: Record<string, unknown> = {}) {
  return mount(SubmissionTutorDrawer, {
    props: {
      modelValue: true,
      submissionId: '9301',
      problemTitle: '两数之和',
      ...props,
    },
    // el-drawer 默认 teleport 到 body，测试里就地渲染才能断言内容
    global: { plugins: [ElementPlus], stubs: { teleport: true } },
  })
}

beforeEach(() => {
  mocks.askSubmissionTutor.mockReset()
})

describe('SubmissionTutorDrawer', () => {
  it('打开时给出快捷问题，覆盖「为什么错」', async () => {
    const wrapper = mountDrawer()
    await flushPromises()

    expect(wrapper.text()).toContain('AI 助教')
    expect(wrapper.text()).toContain('两数之和')
    expect(wrapper.text()).toContain('这道题为什么错？')
  })

  it('点击快捷问题后发送问题并渲染回答', async () => {
    mocks.askSubmissionTutor.mockResolvedValue({
      available: true,
      reply: '第 3 行写成了 a - b，题目要求求和。',
      model: 'mimo-v2.6-flash',
      promptVersion: 'v1',
      generatedAt: '2026-11-09T01:00:00+08:00',
    })

    const wrapper = mountDrawer()
    await flushPromises()

    const chip = wrapper.findAll('button').find((item) => item.text().includes('这道题为什么错'))
    expect(chip).toBeTruthy()
    await chip!.trigger('click')
    await flushPromises()

    // 首轮没有历史，history 必须是空数组而不是 undefined
    expect(mocks.askSubmissionTutor).toHaveBeenCalledWith('9301', {
      message: '这道题为什么错？',
      history: [],
    })
    expect(wrapper.text()).toContain('第 3 行写成了 a - b，题目要求求和。')
    expect(wrapper.text()).toContain('由 mimo-v2.6-flash 生成')
  })

  it('第二轮把上一轮问答作为 history 带回', async () => {
    mocks.askSubmissionTutor
      .mockResolvedValueOnce({
        available: true,
        reply: '第 3 行用了减号。',
        model: 'mimo-v2.6-flash',
        promptVersion: 'v1',
        generatedAt: '2026-11-09T01:00:00+08:00',
      })
      .mockResolvedValueOnce({
        available: true,
        reply: '改成加号即可。',
        model: 'mimo-v2.6-flash',
        promptVersion: 'v1',
        generatedAt: '2026-11-09T01:01:00+08:00',
      })

    const wrapper = mountDrawer()
    await flushPromises()

    const chip = wrapper.findAll('button').find((item) => item.text().includes('这道题为什么错'))
    await chip!.trigger('click')
    await flushPromises()

    const textarea = wrapper.find('textarea')
    await textarea.setValue('那怎么改？')
    const send = wrapper.findAll('button').find((item) => item.text().includes('发送'))
    await send!.trigger('click')
    await flushPromises()

    expect(mocks.askSubmissionTutor).toHaveBeenCalledTimes(2)
    expect(mocks.askSubmissionTutor).toHaveBeenLastCalledWith('9301', {
      message: '那怎么改？',
      history: [
        { role: 'user', content: '这道题为什么错？' },
        { role: 'assistant', content: '第 3 行用了减号。' },
      ],
    })
    expect(wrapper.text()).toContain('改成加号即可。')
  })

  it('AI 不可用时只显示原因，不渲染占位回答', async () => {
    mocks.askSubmissionTutor.mockResolvedValue({
      available: false,
      reply: null,
      model: null,
      promptVersion: 'v1',
      generatedAt: '2026-11-09T01:00:00+08:00',
      error: 'AI 助教暂不可用，请稍后重试。',
    })

    const wrapper = mountDrawer()
    await flushPromises()

    const chip = wrapper.findAll('button').find((item) => item.text().includes('这道题为什么错'))
    await chip!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('AI 助教暂不可用，请稍后重试。')
    expect(wrapper.findAll('.bubble--assistant').length).toBe(0)
  })

  it('展示推荐练习并可跳转到题目详情', async () => {
    const wrapper = mountDrawer({
      recommendation: {
        rule: 'NEXT_DIFFICULTY',
        targetDifficulty: 'MEDIUM',
        reason: '本次通过，难度升到中等。',
        problem: { id: '9103', title: '统计质数个数', difficulty: 'MEDIUM' },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('本次推荐练习')
    expect(wrapper.text()).toContain('统计质数个数')

    const go = wrapper.findAll('button').find((item) => item.text().includes('去做这题'))
    await go!.trigger('click')

    expect(wrapper.emitted('open-problem')?.[0]).toEqual(['9103'])
  })

  it('判题未完成时说明原因而不是报网络错误', async () => {
    mocks.askSubmissionTutor.mockRejectedValue(
      new ApiError({
        code: 'INVALID_STATE',
        httpStatus: 409,
        message: 'submission is not judged yet',
      }),
    )

    const wrapper = mountDrawer()
    await flushPromises()

    const chip = wrapper.findAll('button').find((item) => item.text().includes('这道题为什么错'))
    await chip!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('判题结束后再问')
  })
})
