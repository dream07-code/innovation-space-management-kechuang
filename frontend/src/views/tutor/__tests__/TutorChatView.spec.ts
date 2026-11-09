import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import TutorChatView from '@/views/tutor/TutorChatView.vue'

const mocks = vi.hoisted(() => ({
  listAssessmentProblems: vi.fn<() => Promise<unknown>>(),
  listTutorConversations: vi.fn<(page?: number, size?: number) => Promise<unknown>>(),
  getTutorConversationMessages: vi.fn<(id: string) => Promise<unknown>>(),
  askTutorChat: vi.fn<(payload: unknown) => Promise<unknown>>(),
  renameTutorConversation: vi.fn<(id: string, title: string) => Promise<unknown>>(),
  deleteTutorConversation: vi.fn<(id: string, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  listAssessmentProblems: mocks.listAssessmentProblems,
}))
vi.mock('@/api/modules/tutor', () => ({
  askTutorChat: mocks.askTutorChat,
  listTutorConversations: mocks.listTutorConversations,
  getTutorConversationMessages: mocks.getTutorConversationMessages,
  renameTutorConversation: mocks.renameTutorConversation,
  deleteTutorConversation: mocks.deleteTutorConversation,
  getTutorLearningProfile: vi.fn(),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ displayName: '演示同学' }),
}))

function problem(id: string, title: string, difficulty: string) {
  return { id, title, difficulty, allowedLanguages: ['java'], status: 'PUBLISHED' }
}

function conversation(id: string, title: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    title,
    problemId: null,
    problemTitle: null,
    messageCount: 2,
    createdAt: '2026-11-09T20:00:00+08:00',
    updatedAt: '2026-11-09T21:00:00+08:00',
    ...overrides,
  }
}

function mountView() {
  return mount(TutorChatView, { global: { plugins: [ElementPlus] }, attachTo: document.body })
}

async function sendMessage(wrapper: ReturnType<typeof mountView>, text: string) {
  await wrapper.find('textarea').setValue(text)
  await wrapper.findAll('button').find((item) => item.text().includes('发送'))!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset())
  localStorage.clear()
  mocks.listAssessmentProblems.mockResolvedValue({
    items: [problem('5', '两数之和', 'EASY'), problem('6', '统计质数个数', 'MEDIUM')],
    total: 2,
  })
  mocks.listTutorConversations.mockResolvedValue({ items: [], page: 1, size: 50, total: 0 })
})

describe('TutorChatView', () => {
  it('首句不带 conversationId，拿到后端新建的会话后继续用同一个 id', async () => {
    mocks.askTutorChat.mockResolvedValue({
      conversationId: '12',
      conversationTitle: '什么是时间复杂度',
      available: true,
      reply: '时间复杂度衡量输入规模增长时的增长趋势。',
      model: 'mimo-v2.6-flash',
      promptVersion: 'v1',
      generatedAt: '2026-11-09T20:00:00+08:00',
    })
    mocks.listTutorConversations.mockResolvedValue({
      items: [conversation('12', '什么是时间复杂度')],
      page: 1,
      size: 50,
      total: 1,
    })

    const wrapper = mountView()
    await flushPromises()

    await sendMessage(wrapper, '什么是时间复杂度')

    expect(mocks.askTutorChat).toHaveBeenCalledWith({ message: '什么是时间复杂度' })
    expect(wrapper.text()).toContain('时间复杂度衡量输入规模增长时的增长趋势。')
    expect(wrapper.text()).toContain('mimo-v2.6-flash')
    // 会话出现在列表里，标题来自后端
    expect(wrapper.findAll('.conversation-list li')).toHaveLength(1)
    expect(wrapper.text()).toContain('什么是时间复杂度')

    await sendMessage(wrapper, '那空间复杂度呢')

    const second = mocks.askTutorChat.mock.calls[1]![0] as Record<string, unknown>
    expect(second).toEqual({ message: '那空间复杂度呢', conversationId: '12' })
    expect(second).not.toHaveProperty('history')
  })

  it('打开历史会话时从后端读消息，并显示会话绑定的题目', async () => {
    mocks.listTutorConversations.mockResolvedValue({
      items: [conversation('9', '滑动窗口边界', { problemId: '6', problemTitle: '统计质数个数' })],
      page: 1,
      size: 50,
      total: 1,
    })
    mocks.getTutorConversationMessages.mockResolvedValue({
      conversation: conversation('9', '滑动窗口边界', { problemId: '6', problemTitle: '统计质数个数' }),
      items: [
        { id: '1', role: 'user', content: '上一问', createdAt: '2026-11-09T20:00:00+08:00' },
        { id: '2', role: 'assistant', content: '上一答', createdAt: '2026-11-09T20:00:10+08:00' },
      ],
      total: 2,
      truncated: false,
    })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('.conversation-list li').trigger('click')
    await flushPromises()

    expect(mocks.getTutorConversationMessages).toHaveBeenCalledWith('9')
    expect(wrapper.text()).toContain('上一问')
    expect(wrapper.text()).toContain('上一答')
    expect(wrapper.text()).toContain('当前题目：统计质数个数')
  })

  it('AI 不可用时只显示原因，不渲染占位回答', async () => {
    mocks.askTutorChat.mockResolvedValue({
      conversationId: '12',
      conversationTitle: '什么是栈',
      available: false,
      reply: null,
      model: null,
      promptVersion: 'v1',
      generatedAt: '2026-11-09T20:00:00+08:00',
      error: 'AI 助教未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。',
    })

    const wrapper = mountView()
    await flushPromises()
    await sendMessage(wrapper, '什么是栈')

    expect(wrapper.text()).toContain('AI 助教未启用')
    expect(wrapper.findAll('.bubble--assistant')).toHaveLength(0)
  })

  it('不再往 localStorage 写对话历史', async () => {
    mocks.askTutorChat.mockResolvedValue({
      conversationId: '12',
      conversationTitle: '什么是栈',
      available: true,
      reply: '栈是后进先出。',
      model: 'mimo-v2.6-flash',
    })

    const wrapper = mountView()
    await flushPromises()
    await sendMessage(wrapper, '什么是栈')

    expect(localStorage.getItem('ai-tutor:v1')).toBeNull()
  })

  it('新建对话会清空当前消息并允许重新选题', async () => {
    mocks.listTutorConversations.mockResolvedValue({
      items: [conversation('9', '旧话题')],
      page: 1,
      size: 50,
      total: 1,
    })
    mocks.getTutorConversationMessages.mockResolvedValue({
      conversation: conversation('9', '旧话题'),
      items: [
        { id: '1', role: 'user', content: '旧问题', createdAt: '2026-11-09T20:00:00+08:00' },
      ],
      total: 1,
      truncated: false,
    })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('.conversation-list li').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('旧问题')

    await wrapper.findAll('button').find((item) => item.text().includes('新建'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('旧问题')
    expect(wrapper.text()).toContain('可以这样问')
  })
})
