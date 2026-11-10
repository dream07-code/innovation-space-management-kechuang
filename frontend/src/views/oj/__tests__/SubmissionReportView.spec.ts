import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SubmissionReportView from '@/views/oj/SubmissionReportView.vue'

const push = vi.hoisted(() => vi.fn<(route: unknown) => void>())
const back = vi.hoisted(() => vi.fn<() => void>())
const mocks = vi.hoisted(() => ({
  getSubmissionReport: vi.fn<(id: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  getSubmissionReport: mocks.getSubmissionReport,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { submissionId: '9301' } }),
  useRouter: () => ({ push, back }),
}))

function report(overrides: Record<string, unknown> = {}) {
  return {
    submission: {
      id: '9301',
      status: 'JUDGED',
      language: 'python',
      memberName: '郑永康',
      submittedAt: '2026-11-08T20:10:00+08:00',
      judgedAt: '2026-11-08T20:10:02+08:00',
      assessmentTitle: '第一次阶段考核',
    },
    problem: {
      id: '9101',
      title: '两数之和',
      difficulty: 'EASY',
      summary: '读入两个整数求和',
      description: '读入两个整数，输出它们的和。',
      inputFormat: '两个整数',
      outputFormat: '一个整数',
      tags: ['基础'],
      timeLimitMs: 2000,
    },
    judge: {
      verdict: 'WRONG_ANSWER',
      accepted: false,
      passedTestCases: 3,
      totalTestCases: 5,
      timeMs: 120,
      memoryKb: 32768,
      safeError: null,
      failedCases: [
        {
          position: 4,
          verdict: 'WRONG_ANSWER',
          input: '2 3',
          actualOutput: '-1',
          expectedOutput: '5',
        },
      ],
    },
    ai: {
      available: true,
      model: 'mimo-v2.6-flash',
      rubricVersion: 'v1',
      cause: '第 2 行把加号写成了减号，样例 2 3 输出 -1。',
      advice: ['把 a-b 改成 a+b', '提交前用样例自测一次'],
      cached: false,
      generatedAt: '2026-11-08T20:11:00+08:00',
    },
    recommendation: {
      rule: 'SAME_DIFFICULTY',
      targetDifficulty: 'EASY',
      reason: '本次未通过，先在同难度补练一道，把同类问题练稳。',
      problem: { id: '9102', title: '三数最大值', difficulty: 'EASY' },
    },
    paperQuestions: [],
    ...overrides,
  }
}

function mountView() {
  return mount(SubmissionReportView, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  mocks.getSubmissionReport.mockReset()
  push.mockReset()
})

describe('SubmissionReportView', () => {
  it('渲染错因、建议与推荐下一题', async () => {
    mocks.getSubmissionReport.mockResolvedValue(report())

    const wrapper = mountView()
    await flushPromises()

    expect(mocks.getSubmissionReport).toHaveBeenCalledWith('9301')
    expect(wrapper.text()).toContain('第一次阶段考核')
    expect(wrapper.text()).toContain('两数之和')
    expect(wrapper.text()).toContain('第 2 行把加号写成了减号')
    // 错因区块带 AI 徽标，并明确署名是哪个模型生成的
    expect(wrapper.text()).toContain('AI 批改')
    expect(wrapper.text()).toContain('mimo-v2.6-flash')
    // 未通过时标题是「错因解析」，不是「AI 点评」
    expect(wrapper.text()).toContain('错因解析')
    expect(wrapper.text()).not.toContain('AI 点评')
    expect(wrapper.text()).toContain('把 a-b 改成 a+b')
    expect(wrapper.text()).toContain('本次未通过 · 同难度补练')
    expect(wrapper.text()).toContain('三数最大值')
    // 失败用例的输入/期望/实际都要露出来，学生才知道错在哪
    expect(wrapper.text()).toContain('2 3')
    expect(wrapper.text()).toContain('-1')
    expect(wrapper.text()).toContain('5')
  })

  it('通过时标题显示 AI 点评', async () => {
    mocks.getSubmissionReport.mockResolvedValue(
      report({
        judge: {
          verdict: 'ACCEPTED',
          accepted: true,
          passedTestCases: 5,
          totalTestCases: 5,
          timeMs: 88,
          memoryKb: 31000,
          safeError: null,
          failedCases: [],
        },
        ai: {
          available: true,
          model: 'mimo-v2.6-flash',
          rubricVersion: 'v1',
          cause: '思路清晰，哈希表选型正确，边界情况都覆盖到了。',
          advice: ['可以补一句复杂度注释'],
          cached: false,
          generatedAt: '2026-11-08T20:11:00+08:00',
        },
        recommendation: {
          rule: 'NEXT_DIFFICULTY',
          targetDifficulty: 'MEDIUM',
          reason: '本次通过，沿难度升一档继续练。',
          problem: { id: '9103', title: '区间求和', difficulty: 'MEDIUM' },
        },
      }),
    )

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('AI 点评')
    expect(wrapper.text()).not.toContain('错因解析')
    expect(wrapper.text()).toContain('思路清晰，哈希表选型正确')
    expect(wrapper.text()).toContain('本次通过 · 升一档')
  })

  it('点击推荐题目跳转到该题详情', async () => {
    mocks.getSubmissionReport.mockResolvedValue(report())

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text().includes('去做这道题'))
    expect(button).toBeTruthy()
    await button!.trigger('click')

    expect(push).toHaveBeenCalledWith({
      name: 'oj-problem-detail',
      params: { problemId: '9102' },
    })
  })

  it('AI 不可用时说明原因，仍展示判题与推荐', async () => {
    mocks.getSubmissionReport.mockResolvedValue(
      report({
        ai: {
          available: false,
          rubricVersion: 'v1',
          advice: [],
          cached: false,
          error: 'AI 批改未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。',
        },
      }),
    )

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('AI 批改暂不可用')
    expect(wrapper.text()).toContain('AI_SCORING_ENABLED')
    expect(wrapper.text()).toContain('三数最大值')
    expect(wrapper.text()).not.toContain('把 a-b 改成 a+b')
  })

  it('判题未完成时提示稍后重试，不渲染空报告', async () => {
    mocks.getSubmissionReport.mockRejectedValue(
      Object.assign(new Error('请求内容不合法'), { httpStatus: 409, code: 'INVALID_STATE' }),
    )

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('判题尚未完成')
    expect(wrapper.text()).not.toContain('推荐下一题')
  })
})
