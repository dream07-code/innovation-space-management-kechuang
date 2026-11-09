import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import TutorProfilePanel from '@/components/tutor/TutorProfilePanel.vue'

const push = vi.hoisted(() => vi.fn<(route: unknown) => void>())
const mocks = vi.hoisted(() => ({
  getTutorLearningProfile: vi.fn<() => Promise<unknown>>(),
}))

vi.mock('@/api/modules/tutor', () => ({
  getTutorLearningProfile: mocks.getTutorLearningProfile,
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))

function mountPanel() {
  return mount(TutorProfilePanel, { global: { plugins: [ElementPlus] }, attachTo: document.body })
}

function emptyProfile() {
  return {
    hasData: false,
    snapshotAt: null,
    overview: {
      attemptedProblems: 0,
      passedProblems: 0,
      passRate: 0,
      submissionCount: 0,
      averageScore: null,
      lastSubmittedAt: null,
    },
    weakPoints: [],
    recommendations: [],
  }
}

function profile() {
  return {
    hasData: true,
    snapshotAt: '2026-11-09T21:00:00+08:00',
    overview: {
      attemptedProblems: 4,
      passedProblems: 2,
      passRate: 50,
      submissionCount: 7,
      averageScore: 82,
      lastSubmittedAt: '2026-11-09T20:40:00+08:00',
    },
    weakPoints: [{ tag: '滑动窗口', attempted: 2, passed: 0, passRate: 0 }],
    recommendations: [
      {
        problemId: '7',
        title: '最长无重复子串',
        difficulty: 'MEDIUM',
        tags: ['滑动窗口'],
        reason: '「滑动窗口」目前 0/2 通过，先补这个知识点。',
      },
    ],
  }
}

beforeEach(() => {
  push.mockReset()
  mocks.getTutorLearningProfile.mockReset()
})

describe('TutorProfilePanel', () => {
  it('没有做题记录时显示空状态，并引导去题库', async () => {
    mocks.getTutorLearningProfile.mockResolvedValue(emptyProfile())

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('还没有判题完成的提交')
    expect(wrapper.text()).not.toContain('通过率')
    await wrapper.findAll('button').find((item) => item.text().includes('去题库挑一道'))!.trigger('click')
    expect(push).toHaveBeenCalledWith('/oj/problems')
  })

  it('展示后端聚合出的数字、薄弱点与推荐练习', async () => {
    mocks.getTutorLearningProfile.mockResolvedValue(profile())

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('4')
    expect(wrapper.text()).toContain('50%')
    expect(wrapper.text()).toContain('82 分')
    expect(wrapper.text()).toContain('滑动窗口')
    expect(wrapper.text()).toContain('0 / 2 通过')
    expect(wrapper.text()).toContain('最长无重复子串')
    expect(wrapper.text()).toContain('「滑动窗口」目前 0/2 通过，先补这个知识点。')
    // 画像数据来源要写清楚，避免被当成模型推断
    expect(wrapper.text()).toContain('不含 AI 推断')

    await wrapper.findAll('button').find((item) => item.text().includes('去做这道题'))!.trigger('click')
    expect(push).toHaveBeenCalledWith('/oj/problems/7/practice')
  })

  it('接口失败时给出可重试的提示', async () => {
    mocks.getTutorLearningProfile.mockRejectedValue(new Error('boom'))

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('无法连接服务器')
    expect(wrapper.findAll('button').some((item) => item.text().includes('重新加载'))).toBe(true)
  })
})
