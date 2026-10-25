import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AssessmentEditView from '@/views/oj/AssessmentEditView.vue'

const mocks = vi.hoisted(() => ({
  routeParams: {} as Record<string, string | undefined>,
  listAssessmentProblems: vi.fn<() => Promise<unknown>>(),
  listQuizQuestions: vi.fn<(query?: unknown) => Promise<unknown>>(),
  getAssessment: vi.fn<(id: string) => Promise<unknown>>(),
  createAssessment: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  updateAssessment: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
  replace: vi.fn<(route: unknown) => void>(),
}))

vi.mock('@/api/modules/oj', () => ({
  listAssessmentProblems: mocks.listAssessmentProblems,
  getAssessment: mocks.getAssessment,
  createAssessment: mocks.createAssessment,
  updateAssessment: mocks.updateAssessment,
}))
vi.mock('@/api/modules/quiz', () => ({ listQuizQuestions: mocks.listQuizQuestions }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { directionId: 'd1' } }),
}))
vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    items: [{ id: 'd1', name: 'AI 全栈', code: 'AI' }],
    ensureLoaded: () => Promise.resolve(),
  }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: mocks.routeParams }),
  useRouter: () => ({ replace: mocks.replace, push: vi.fn<(route: unknown) => void>(), back: () => undefined }),
}))

/** 动态 stub：每次点击吐出 emitState 里的当前值（仓库先例见 LeaderListView.spec）。 */
const state = vi.hoisted(() => ({ selectValue: '' as unknown, dateValue: '2026-11-01T09:00:00+08:00' }))
const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(_, { emit }) {
    return () => h('div', { class: 'select-stub', onClick: () => emit('update:modelValue', state.selectValue) })
  },
})
const ElDatePickerStub = defineComponent({
  name: 'ElDatePicker',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(_, { emit }) {
    return () => h('div', { class: 'date-stub', onClick: () => emit('update:modelValue', state.dateValue) })
  },
})

const mountOptions = {
  global: {
    plugins: [ElementPlus],
    stubs: { teleport: true, ElSelect: ElSelectStub, ElDatePicker: ElDatePickerStub },
  },
}

const problems = {
  items: [
    { id: 'p1', title: 'A+B 问题', difficulty: 'EASY', status: 'PUBLISHED' },
    { id: 'p2', title: '最长公共子序列', difficulty: 'HARD', status: 'PUBLISHED' },
  ],
}
const quizzes = {
  items: [
    { id: 'q1', type: 'SINGLE_CHOICE', stem: '单选题干', status: 'PUBLISHED' },
    { id: 'q2', type: 'FILL_BLANK', stem: '填空题干', status: 'PUBLISHED' },
  ],
}

async function mountView() {
  const wrapper = mount(AssessmentEditView, mountOptions)
  await flushPromises()
  return wrapper
}

async function addItem(wrapper: Awaited<ReturnType<typeof mountView>>, source: 'program' | 'quiz', value: string) {
  state.selectValue = value
  await wrapper.get(`[data-test="source-${source}"]`).trigger('click')
  await wrapper.get(`[data-test="add-${source}"]`).trigger('click')
}

async function fillBasics(wrapper: Awaited<ReturnType<typeof mountView>>) {
  await wrapper.get('[data-test="title"]').setValue('期中考核')
  await wrapper.get('[data-test="description"]').setValue('说明')
  state.selectValue = 'd1'
  await wrapper.findAll('.select-stub').slice(0, 1)[0]!.trigger('click')
  state.selectValue = ['java']
  await wrapper.findAll('.select-stub').slice(1, 2)[0]!.trigger('click')
  for (const picker of wrapper.findAll('.date-stub')) await picker.trigger('click')
}

describe('AssessmentEditView 混合条目', () => {
  beforeEach(() => {
    mocks.routeParams.assessmentId = undefined
    mocks.listAssessmentProblems.mockReset().mockResolvedValue(problems)
    mocks.listQuizQuestions.mockReset().mockResolvedValue(quizzes)
    mocks.getAssessment.mockReset()
    mocks.createAssessment.mockReset().mockResolvedValue({ id: '9002' })
    mocks.updateAssessment.mockReset().mockResolvedValue({ id: '9001' })
    mocks.replace.mockReset()
  })

  it('添加两类条目、防重复、调分值、排序与删除，合计实时更新', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('混合条目')

    await addItem(wrapper, 'program', 'p1')
    await addItem(wrapper, 'quiz', 'q1')
    expect(wrapper.text()).toContain('A+B 问题')
    expect(wrapper.text()).toContain('单选题干')
    expect(wrapper.text()).toContain('合计 105 分')

    // 重复添加同题被拒绝
    await addItem(wrapper, 'program', 'p1')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)

    // 调整程序题分值 100 → 80
    await wrapper.get('[data-test="score-0"]').find('input').setValue('80')
    await wrapper.get('[data-test="score-0"]').find('input').trigger('change')
    expect(wrapper.text()).toContain('合计 85 分')

    // 下移第一条后，客观题排第一
    await wrapper.get('[data-test="move-down-0"]').trigger('click')
    expect(wrapper.findAll('tbody tr')[0]!.text()).toContain('单选题干')

    // 删除后仅剩一条
    await wrapper.get('[data-test="remove-1"]').trigger('click')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
  })

  it('创建提交按顺序组装 items 载荷', async () => {
    const wrapper = await mountView()
    await fillBasics(wrapper)
    await addItem(wrapper, 'program', 'p1')
    await addItem(wrapper, 'quiz', 'q2')
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.createAssessment).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '期中考核',
        items: [
          { position: 1, type: 'PROGRAM', refId: 'p1', score: 100 },
          { position: 2, type: 'QUIZ', refId: 'q2', score: 5 },
        ],
      }),
      expect.any(String),
    )
    // 开关缺省关闭。
    expect(mocks.createAssessment).toHaveBeenCalledWith(
      expect.objectContaining({ pointsEnabled: false }),
      expect.any(String),
    )
  })

  it('积分开关开启后创建载荷携带 pointsEnabled', async () => {
    const wrapper = await mountView()
    await fillBasics(wrapper)
    await addItem(wrapper, 'program', 'p1')
    await wrapper.get('[data-test="points-switch"] input').setValue(true)
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.createAssessment).toHaveBeenCalledWith(
      expect.objectContaining({ pointsEnabled: true }),
      expect.any(String),
    )
    expect(wrapper.text()).toContain('第一名 20 分')
  })

  it('编辑态按 assessment.items 与积分开关回填并原样提交', async () => {
    mocks.routeParams.assessmentId = '9001'
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      version: 3,
      title: '旧标题',
      description: '旧说明',
      directionId: 'd1',
      startAt: '2026-11-01T08:00:00+08:00',
      endAt: '2026-11-01T10:00:00+08:00',
      allowedLanguages: ['java'],
      problemIds: ['p1'],
      pointsEnabled: true,
      items: [
        { position: 1, type: 'PROGRAM', refId: 'p1', score: 80 },
        { position: 2, type: 'QUIZ', refId: 'q1', score: 5 },
      ],
    })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('合计 85 分')
    // pointsEnabled: true 回填到开关。
    expect(wrapper.get('[data-test="points-switch"] input').element.checked).toBe(true)

    await fillBasics(wrapper)
    await wrapper.get('[data-test="save"]').trigger('click')
    await flushPromises()

    expect(mocks.updateAssessment).toHaveBeenCalledWith(
      '9001',
      expect.objectContaining({
        version: 3,
        items: [
          { position: 1, type: 'PROGRAM', refId: 'p1', score: 80 },
          { position: 2, type: 'QUIZ', refId: 'q1', score: 5 },
        ],
      }),
    )
  })
})
