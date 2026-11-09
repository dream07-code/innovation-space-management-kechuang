import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import TutorChatView from '@/views/tutor/TutorChatView.vue'

const mocks = vi.hoisted(() => ({
  listAssessmentProblems: vi.fn<() => Promise<unknown>>(),
  askTutorChat: vi.fn<(payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  listAssessmentProblems: mocks.listAssessmentProblems,
}))
vi.mock('@/api/modules/tutor', () => ({
  askTutorChat: mocks.askTutorChat,
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ displayName: '演示同学' }),
}))

function problem(id: string, title: string, difficulty: string) {
  return { id, title, difficulty, allowedLanguages: ['java'], status: 'PUBLISHED' }
}

function mountView() {
  return mount(TutorChatView, { global: { plugins: [ElementPlus] }, attachTo: document.body })
}

beforeEach(() => {
  mocks.listAssessmentProblems.mockReset()
  mocks.askTutorChat.mockReset()
  localStorage.clear()
  mocks.listAssessmentProblems.mockResolvedValue({
    items: [problem('5', '两数之和', 'EASY'), problem('6', '统计质数个数', 'MEDIUM')],
    total: 2,
  })
})

describe('TutorChatView', () => {
  it('列出真实题库，选中题目后设为当前上下文', async () => {
    const wrapper = mountView()
    await flushPromises()

    const items = wrapper.findAll('.problem-list li')
    expect(items).toHaveLength(2)
    expect(wrapper.text()).toContain('两数之和')
    expect(wrapper.text()).toContain('统计质数个数')
    // 问候语带学生姓名
    expect(wrapper.text()).toContain('你好，演示同学')
    expect(wrapper.text()).toContain('未选择题目')

    await items[1]!.trigger('click')
    expect(wrapper.text()).toContain('当前题目：统计质数个数')
    expect(wrapper.text()).toContain('现在在聊《统计质数个数》')
  })

  it('点预设问题直接发送，并带上当前题目的 problemId', async () => {
    mocks.askTutorChat.mockResolvedValue({
      available: true,
      reply: '时间复杂度衡量输入规模增长时的增长趋势。',
      model: 'mimo-v2.6-flash',
      promptVersion: 'v1',
      generatedAt: '2026-11-09T20:00:00+08:00',
    })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('.problem-list li')[0]!.trigger('click')

    const preset = wrapper
      .findAll('.presets__chip')
      .find((item) => item.text().includes('这个知识点怎么理解'))
    expect(preset).toBeTruthy()
    await preset!.trigger('click')
    await flushPromises()

    expect(mocks.askTutorChat).toHaveBeenCalledWith({
      message: '这个知识点怎么理解',
      problemId: '5',
      history: [],
    })
    expect(wrapper.text()).toContain('时间复杂度衡量输入规模增长时的增长趋势。')
    expect(wrapper.text()).toContain('mimo-v2.6-flash')
  })

  it('第二轮把上一轮问答作为 history 带回', async () => {
    mocks.askTutorChat
      .mockResolvedValueOnce({ available: true, reply: '第一轮回答', model: 'mimo-v2.6-flash' })
      .mockResolvedValueOnce({ available: true, reply: '第二轮回答', model: 'mimo-v2.6-flash' })

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('textarea').setValue('什么是时间复杂度')
    await wrapper.findAll('button').find((item) => item.text().includes('发送'))!.trigger('click')
    await flushPromises()

    await wrapper.find('textarea').setValue('那空间复杂度呢')
    await wrapper.findAll('button').find((item) => item.text().includes('发送'))!.trigger('click')
    await flushPromises()

    const second = mocks.askTutorChat.mock.calls[1]![0] as {
      message: string
      history: { role: string; content: string }[]
    }
    expect(second.message).toBe('那空间复杂度呢')
    expect(second.history).toEqual([
      { role: 'user', content: '什么是时间复杂度' },
      { role: 'assistant', content: '第一轮回答' },
    ])
    expect(wrapper.text()).toContain('第二轮回答')
  })

  it('AI 不可用时只显示原因，不渲染占位回答', async () => {
    mocks.askTutorChat.mockResolvedValue({
      available: false,
      reply: null,
      model: null,
      promptVersion: 'v1',
      generatedAt: '2026-11-09T20:00:00+08:00',
      error: 'AI 助教未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。',
    })

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('textarea').setValue('什么是栈')
    await wrapper.findAll('button').find((item) => item.text().includes('发送'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('AI 助教未启用')
    // 只有用户的提问气泡，没有助教回答气泡
    expect(wrapper.findAll('.bubble--assistant')).toHaveLength(0)
  })

  it('把对话与选中题目存进 localStorage，并在重新进入时恢复', async () => {
    mocks.askTutorChat.mockResolvedValue({
      available: true,
      reply: '恢复测试回答',
      model: 'mimo-v2.6-flash',
    })

    const first = mountView()
    await flushPromises()
    await first.findAll('.problem-list li')[1]!.trigger('click')
    await first.find('textarea').setValue('这题的边界怎么处理')
    await first.findAll('button').find((item) => item.text().includes('发送'))!.trigger('click')
    await flushPromises()

    const stored = JSON.parse(localStorage.getItem('ai-tutor:v1') ?? '{}')
    expect(stored.name).toBe('演示同学')
    expect(stored.problemId).toBe('6')
    expect(stored.messages.length).toBeGreaterThanOrEqual(2)
    first.unmount()

    const second = mountView()
    await flushPromises()

    expect(second.text()).toContain('这题的边界怎么处理')
    expect(second.text()).toContain('恢复测试回答')
    expect(second.text()).toContain('当前题目：统计质数个数')
    second.unmount()
  })
})
