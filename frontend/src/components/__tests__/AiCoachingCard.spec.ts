import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AiCoachingCard from '@/components/dashboard/AiCoachingCard.vue'

const push = vi.hoisted(() => vi.fn<(route: unknown) => void>())
const mocks = vi.hoisted(() => ({
  listAssessmentSubmissions: vi.fn<(query: unknown) => Promise<unknown>>(),
  getSubmissionReport: vi.fn<(id: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  listAssessmentSubmissions: mocks.listAssessmentSubmissions,
  getSubmissionReport: mocks.getSubmissionReport,
  // 抽屉内部会用到，但不点击发送时不会调用。
  askSubmissionTutor: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))

function submission(overrides: Record<string, unknown> = {}) {
  return {
    id: '501',
    assessmentId: '38',
    problemId: '5',
    memberId: '341',
    language: 'java',
    status: 'JUDGED',
    version: 1,
    submittedAt: '2026-11-09T00:40:30+08:00',
    judgeResult: { verdict: 'WRONG_ANSWER', passedTestCases: 0, totalTestCases: 1 },
    ...overrides,
  }
}

function report(overrides: Record<string, unknown> = {}) {
  return {
    submission: { id: '501', status: 'JUDGED', language: 'java' },
    problem: { id: '5', title: '两数之和（练习 5）', difficulty: 'EASY' },
    judge: {
      verdict: 'WRONG_ANSWER',
      passedTestCases: 0,
      totalTestCases: 1,
      failedCases: [],
    },
    ai: {
      available: true,
      model: 'mimo-v2.6-flash',
      rubricVersion: 'v1',
      cause: '函数体写成了 return a - b，做的是减法，输入 [1,2] 返回 -1。',
      advice: ['把减号改成加号'],
      cached: false,
      generatedAt: '2026-11-09T00:41:00+08:00',
    },
    recommendation: {
      rule: 'SAME_DIFFICULTY',
      targetDifficulty: 'EASY',
      reason: '本次未通过，先在同难度补练一道。',
      problem: { id: '1', title: '两数之和', difficulty: 'EASY' },
    },
    paperQuestions: [],
    ...overrides,
  }
}

function mountCard() {
  return mount(AiCoachingCard, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  mocks.listAssessmentSubmissions.mockReset()
  mocks.getSubmissionReport.mockReset()
  push.mockReset()
})

describe('AiCoachingCard', () => {
  it('展示最近一次批改的错因与推荐练习，点击卡片进入报告页', async () => {
    mocks.listAssessmentSubmissions.mockResolvedValue({ items: [submission()], total: 1 })
    mocks.getSubmissionReport.mockResolvedValue(report())

    const wrapper = mountCard()
    await flushPromises()

    // 只看本人已判定的提交，且取最近一条
    expect(mocks.listAssessmentSubmissions).toHaveBeenCalledWith({ status: 'JUDGED' })
    expect(mocks.getSubmissionReport).toHaveBeenCalledWith('501')
    expect(wrapper.text()).toContain('AI 批改与辅导')
    expect(wrapper.text()).toContain('两数之和（练习 5）')
    expect(wrapper.text()).toContain('函数体写成了 return a - b')
    expect(wrapper.text()).toContain('推荐练习')
    expect(wrapper.text()).toContain('答案错误')

    await wrapper.find('.ai-coaching__main').trigger('click')
    expect(push).toHaveBeenCalledWith({
      name: 'oj-submission-report',
      params: { submissionId: '501' },
    })
  })

  it('没有已判定提交时显示空状态，不编造批改内容', async () => {
    mocks.listAssessmentSubmissions.mockResolvedValue({ items: [], total: 0 })

    const wrapper = mountCard()
    await flushPromises()

    expect(mocks.getSubmissionReport).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('还没有 AI 批改记录')
    // 没有任何编造出来的错因/推荐区块
    expect(wrapper.find('.ai-coaching__cause').exists()).toBe(false)
    expect(wrapper.find('.ai-coaching__next').exists()).toBe(false)

    const button = wrapper.findAll('button').find((item) => item.text().includes('去做题'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    expect(push).toHaveBeenCalledWith('/oj/assessments')
  })

  it('AI 不可用时只说明原因，不展示假错因', async () => {
    mocks.listAssessmentSubmissions.mockResolvedValue({ items: [submission()], total: 1 })
    mocks.getSubmissionReport.mockResolvedValue(
      report({
        ai: {
          available: false,
          model: null,
          rubricVersion: 'v1',
          cause: null,
          advice: [],
          cached: false,
          error: 'AI 批改未启用：需要配置 AI_PROVIDER',
        },
      }),
    )

    const wrapper = mountCard()
    await flushPromises()

    expect(wrapper.text()).toContain('AI 批改暂不可用')
    expect(wrapper.text()).toContain('AI 批改未启用')
    expect(wrapper.find('.ai-coaching__cause').text()).not.toContain('return a - b')
    // 推荐来自规则计算，不依赖 AI，仍应展示
    expect(wrapper.text()).toContain('推荐练习')
  })
})
