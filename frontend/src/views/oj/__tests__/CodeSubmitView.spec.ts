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
