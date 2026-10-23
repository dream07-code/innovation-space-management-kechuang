import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import CodeSubmitView from '@/views/oj/CodeSubmitView.vue'

const mocks = vi.hoisted(() => ({
  getAssessmentProblem: vi.fn<(id: string) => Promise<unknown>>(),
  getAssessment: vi.fn<(id: string) => Promise<unknown>>(),
  getAssessmentSubmission: vi.fn<(id: string) => Promise<unknown>>(),
  getPracticeTestCases: vi.fn<(id: string) => Promise<unknown>>(),
  runPractice: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  runTrial: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
  createAssessmentSubmission: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
}))

// CodeMirror 依赖真实浏览器排版，单元测试用等价 textarea 替身验证业务逻辑。
vi.mock('@/components/CodeEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'CodeEditorStub',
      props: {
        modelValue: { type: String, default: '' },
        language: { type: String, default: '' },
      },
      emits: ['update:modelValue', 'change'],
      setup(props, { emit }) {
        return () =>
          h('textarea', {
            'data-test': 'code-editor',
            value: props.modelValue,
            onInput: (event: Event) => {
              const value = (event.target as HTMLTextAreaElement).value
              emit('update:modelValue', value)
              emit('change', value)
            },
          })
      },
    }),
  }
})

vi.mock('@/api/modules/oj', () => ({
  getAssessmentProblem: mocks.getAssessmentProblem,
  getAssessment: mocks.getAssessment,
  getAssessmentSubmission: mocks.getAssessmentSubmission,
  getPracticeTestCases: mocks.getPracticeTestCases,
  runPractice: mocks.runPractice,
  runTrial: mocks.runTrial,
  createAssessmentSubmission: mocks.createAssessmentSubmission,
}))
let routeParams: Record<string, string> = { problemId: '9101' }
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: routeParams }),
  useRouter: () => ({ back: () => undefined, push: vi.fn<(route: unknown) => void>() }),
}))

describe('CodeSubmitView 练习模式', () => {
  it('展示练习测试点并运行安全练习结果', async () => {
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '练习题',
      summary: '摘要',
      description: '题面',
      inputFormat: '输入',
      outputFormat: '输出',
      examples: [],
      difficulty: 'EASY',
      tags: [],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['python'],
      status: 'PUBLISHED',
      version: 1,
      createdAt: '2026-10-14T00:00:00+08:00',
      updatedAt: '2026-10-14T00:00:00+08:00',
    })
    mocks.getPracticeTestCases.mockResolvedValue({
      practiceTestCases: [{ position: 1, input: 'practice-input', expectedOutput: 'P' }],
    })
    mocks.runPractice.mockResolvedValue({
      verdict: 'ACCEPTED',
      passedTestCases: 1,
      totalTestCases: 1,
      timeMs: 12,
      memoryKb: 1024,
      safeError: null,
      testCaseResults: [{
        position: 1,
        input: 'practice-input',
        expectedOutput: 'P',
        verdict: 'ACCEPTED',
        timeMs: 12,
        memoryKb: 1024,
        safeError: null,
      }],
    })

    const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('题库练习模式')
    expect(wrapper.text()).toContain('practice-input')
    await wrapper.find('textarea').setValue('print(1)')
    await wrapper.get('[data-test="practice-run"]').trigger('click')
    await flushPromises()

    expect(mocks.runPractice).toHaveBeenCalledWith('9101', { language: 'python', code: 'print(1)' }, expect.any(String))
    expect(wrapper.text()).toContain('练习运行结果')
    expect(wrapper.text()).toContain('P')
    expect(wrapper.text()).toContain('1 / 1')
  })
})

describe('CodeSubmitView 窄屏工作台', () => {
  it('窄屏显示题目、代码、结果标签', async () => {
    const original = window.matchMedia
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: query.includes('max-width: 767px'),
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      }),
    })
    try {
      mocks.getAssessmentProblem.mockResolvedValue({
        id: '9101',
        title: '窄屏题',
        summary: '摘要',
        description: '题面',
        inputFormat: '输入',
        outputFormat: '输出',
        examples: [],
        difficulty: 'EASY',
        tags: [],
        timeLimitMs: 1000,
        memoryLimitKb: 65536,
        allowedLanguages: ['python'],
        status: 'PUBLISHED',
        version: 1,
        createdAt: '2026-10-14T00:00:00+08:00',
        updatedAt: '2026-10-14T00:00:00+08:00',
      })
      mocks.getPracticeTestCases.mockResolvedValue({ practiceTestCases: [] })
      const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
      await flushPromises()

      expect(wrapper.text()).toContain('题目')
      expect(wrapper.text()).toContain('代码')
      expect(wrapper.text()).toContain('结果')
      expect(wrapper.find('.workspace-tabs').exists()).toBe(true)
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        value: original,
      })
    }
  })
})

describe('CodeSubmitView 正式考核轮询', () => {
  it('提交后轮询到终态并展示逐测试点安全结果', async () => {
    routeParams = { assessmentId: '9001', problemId: '9101' }
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      description: 'x',
      directionId: '1',
      directionName: 'AI 全栈',
      status: 'PUBLISHED',
      startAt: '2026-10-01T00:00:00+08:00',
      endAt: '2026-12-01T00:00:00+08:00',
      allowedLanguages: ['python'],
      problemIds: ['9101'],
      version: 1,
      createdAt: '2026-10-01T00:00:00+08:00',
      updatedAt: '2026-10-01T00:00:00+08:00',
    })
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '正式题',
      summary: '摘要',
      description: '题面',
      inputFormat: '输入',
      outputFormat: '输出',
      examples: [],
      difficulty: 'EASY',
      tags: [],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['python'],
      status: 'PUBLISHED',
      version: 1,
      createdAt: '2026-10-01T00:00:00+08:00',
      updatedAt: '2026-10-01T00:00:00+08:00',
    })
    mocks.createAssessmentSubmission.mockResolvedValue({
      id: '9201',
      assessmentId: '9001',
      problemId: '9101',
      memberId: '201',
      language: 'python',
      status: 'RECEIVED',
      version: 1,
      submittedAt: '2026-10-14T00:00:00+08:00',
    })
    mocks.getAssessmentSubmission.mockResolvedValue({
      id: '9201',
      assessmentId: '9001',
      problemId: '9101',
      memberId: '201',
      language: 'python',
      status: 'JUDGED',
      codeHash: 'hash',
      version: 2,
      submittedAt: '2026-10-14T00:00:00+08:00',
      judgedAt: '2026-10-14T00:00:01+08:00',
      judgeResult: {
        verdict: 'ACCEPTED',
        passedTestCases: 1,
        totalTestCases: 1,
        timeMs: 12,
        memoryKb: 1024,
        safeError: null,
        testCaseResults: [{
          position: 1,
          verdict: 'ACCEPTED',
          timeMs: 12,
          memoryKb: 1024,
          safeError: null,
        }],
      },
    })

    const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('textarea').setValue('print(1)')
    await wrapper.get('[data-test="formal-submit"]').trigger('click')
    await flushPromises()

    expect(mocks.createAssessmentSubmission).toHaveBeenCalled()
    expect(mocks.getAssessmentSubmission).toHaveBeenCalledWith('9201')
    expect(wrapper.text()).toContain('正式考核判定')
    expect(wrapper.text()).toContain('1 / 1')
    expect(wrapper.text()).toContain('查看提交详情')
  })
})

describe('CodeSubmitView 起始代码', () => {
  it('按语言载入可编译框架，并在切换语言时保留已写代码', async () => {
    routeParams = { problemId: '9101' }
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '多语言题',
      summary: '摘要',
      description: '题面',
      inputFormat: '输入',
      outputFormat: '输出',
      examples: [],
      difficulty: 'EASY',
      tags: [],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['cpp', 'python'],
      status: 'PUBLISHED',
      version: 1,
      createdAt: '2026-10-14T00:00:00+08:00',
      updatedAt: '2026-10-14T00:00:00+08:00',
    })
    mocks.getPracticeTestCases.mockResolvedValue({ practiceTestCases: [] })

    const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const editor = wrapper.get('textarea')
    expect((editor.element as HTMLTextAreaElement).value).toContain('#include <bits/stdc++.h>')

    await editor.setValue('int main() { return 0; }')
    const select = wrapper.findComponent({ name: 'ElSelect' })
    select.vm.$emit('change', 'python')
    await nextTick()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('sys.stdin')

    select.vm.$emit('change', 'cpp')
    await nextTick()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('int main() { return 0; }')
  })
})


describe('CodeSubmitView 函数式判题', () => {
  it('按函数签名生成骨架，并在提交后展示参考测试点', async () => {
    routeParams = { assessmentId: '9001', problemId: '9101' }
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '函数考核',
      description: 'x',
      directionId: '1',
      directionName: 'AI 全栈',
      status: 'PUBLISHED',
      startAt: '2026-10-01T00:00:00+08:00',
      endAt: '2026-12-01T00:00:00+08:00',
      allowedLanguages: ['cpp'],
      problemIds: ['9101'],
      version: 1,
      createdAt: '2026-10-01T00:00:00+08:00',
      updatedAt: '2026-10-01T00:00:00+08:00',
    })
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '斐波那契',
      summary: '摘要',
      description: '题面',
      inputFormat: 'n',
      outputFormat: 'fib(n)',
      examples: [],
      difficulty: 'EASY',
      tags: [],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['cpp'],
      judgeMode: 'FUNCTION',
      functionName: 'fib',
      parameterTypes: ['long'],
      returnType: 'long',
      status: 'PUBLISHED',
      version: 1,
      createdAt: '2026-10-01T00:00:00+08:00',
      updatedAt: '2026-10-01T00:00:00+08:00',
    })
    mocks.createAssessmentSubmission.mockResolvedValue({
      id: '9201',
      assessmentId: '9001',
      problemId: '9101',
      memberId: '201',
      language: 'cpp',
      status: 'RECEIVED',
      version: 1,
      submittedAt: '2026-10-14T00:00:00+08:00',
    })
    mocks.getAssessmentSubmission.mockResolvedValue({
      id: '9201',
      assessmentId: '9001',
      problemId: '9101',
      memberId: '201',
      language: 'cpp',
      status: 'JUDGED',
      codeHash: 'hash',
      version: 2,
      submittedAt: '2026-10-14T00:00:00+08:00',
      judgedAt: '2026-10-14T00:00:01+08:00',
      judgeResult: {
        verdict: 'WRONG_ANSWER',
        passedTestCases: 5,
        totalTestCases: 6,
        timeMs: 12,
        memoryKb: 1024,
        safeError: 'synthetic wrong answer',
        testCaseResults: [{ position: 1, verdict: 'ACCEPTED', timeMs: 12, memoryKb: 1024, safeError: null }],
        sampleCase: {
          position: 2,
          verdict: 'WRONG_ANSWER',
          input: '[10]',
          actualOutput: '54',
          expectedOutput: '55',
        },
      },
    })

    const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const editor = wrapper.get('textarea')
    expect((editor.element as HTMLTextAreaElement).value).toContain('class Solution')
    expect((editor.element as HTMLTextAreaElement).value).toContain('fib(long long arg0)')

    await wrapper.get('[data-test="formal-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('参考测试点')
    expect(wrapper.text()).toContain('[10]')
    expect(wrapper.text()).toContain('54')
    expect(wrapper.text()).toContain('55')
  })
})

describe('CodeSubmitView 考核试运行', () => {
  it('随机抽到 1 个正式测试点，展示输入、实际输出与期望输出', async () => {
    routeParams = { assessmentId: '9001', problemId: '9101' }
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      description: 'x',
      directionId: '1',
      directionName: 'AI 全栈',
      status: 'PUBLISHED',
      startAt: '2026-10-01T00:00:00+08:00',
      endAt: '2026-12-01T00:00:00+08:00',
      allowedLanguages: ['python'],
      problemIds: ['9101'],
      version: 1,
      createdAt: '2026-10-01T00:00:00+08:00',
      updatedAt: '2026-10-01T00:00:00+08:00',
    })
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '两数之和',
      summary: '摘要',
      description: '题面',
      inputFormat: '两个整数',
      outputFormat: '和',
      examples: [],
      difficulty: 'EASY',
      tags: [],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['python'],
      judgeMode: 'STDIO',
      functionName: null,
      parameterTypes: null,
      returnType: null,
      status: 'PUBLISHED',
      version: 1,
      createdAt: '2026-10-01T00:00:00+08:00',
      updatedAt: '2026-10-01T00:00:00+08:00',
    })
    mocks.getPracticeTestCases.mockResolvedValue({ practiceTestCases: [] })
    mocks.runTrial.mockResolvedValue({
      verdict: 'WRONG_ANSWER',
      passedTestCases: 0,
      totalTestCases: 1,
      timeMs: 15,
      memoryKb: 2048,
      safeError: 'synthetic wrong answer',
      testCaseResults: [
        {
          position: 1,
          input: '3 4',
          expectedOutput: '7',
          actualOutput: '8',
          verdict: 'WRONG_ANSWER',
          timeMs: 15,
          memoryKb: 2048,
          safeError: 'synthetic wrong answer',
        },
      ],
    })

    const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="trial-run"]').trigger('click')
    await flushPromises()

    expect(mocks.runTrial).toHaveBeenCalledWith('9101', { language: 'python', code: expect.any(String) })
    expect(wrapper.text()).toContain('试运行结果（随机 1 个正式测试点）')
    expect(wrapper.text()).toContain('3 4')
    expect(wrapper.text()).toContain('7')
    expect(wrapper.text()).toContain('8')
    expect(wrapper.text()).toContain('只用于自查')
  })
})

describe('CodeSubmitView 无测试点提示', () => {
  it('练习测试点为空时提示未配置，而不是显示内部错误', async () => {
    routeParams = { problemId: '9101' }
    mocks.getAssessmentProblem.mockResolvedValue({
      id: '9101',
      title: '空题',
      summary: '摘要',
      description: '题面',
      inputFormat: '输入',
      outputFormat: '输出',
      examples: [],
      difficulty: 'EASY',
      tags: [],
      timeLimitMs: 1000,
      memoryLimitKb: 65536,
      allowedLanguages: ['python'],
      judgeMode: 'STDIO',
      functionName: null,
      parameterTypes: null,
      returnType: null,
      status: 'PUBLISHED',
      version: 1,
      createdAt: '2026-10-01T00:00:00+08:00',
      updatedAt: '2026-10-01T00:00:00+08:00',
    })
    mocks.getPracticeTestCases.mockResolvedValue({ practiceTestCases: [] })
    mocks.runPractice.mockResolvedValue({
      verdict: 'INTERNAL_ERROR',
      passedTestCases: 0,
      totalTestCases: 0,
      timeMs: 0,
      memoryKb: 0,
      safeError: 'synthetic judge0 response unavailable',
      testCaseResults: [],
    })

    const wrapper = mount(CodeSubmitView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="practice-run"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('本题暂未配置可运行的测试点')
    expect(wrapper.text()).not.toContain('synthetic judge0 response unavailable')
  })
})
