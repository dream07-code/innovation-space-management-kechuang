import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ReportManageView from '@/views/admin/ReportManageView.vue'

const mocks = vi.hoisted(() => ({
  listDailyReports: vi.fn<(params: unknown) => Promise<unknown>>(),
  listWeeklyReports: vi.fn<(params: unknown) => Promise<unknown>>(),
  getDailyReport: vi.fn<(id: string) => Promise<unknown>>(),
  getWeeklyReport: vi.fn<(id: string) => Promise<unknown>>(),
  scoreDailyReport: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
  scoreWeeklyReport: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/reports', () => ({
  listDailyReports: mocks.listDailyReports,
  listWeeklyReports: mocks.listWeeklyReports,
  getDailyReport: mocks.getDailyReport,
  getWeeklyReport: mocks.getWeeklyReport,
  scoreDailyReport: mocks.scoreDailyReport,
  scoreWeeklyReport: mocks.scoreWeeklyReport,
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { memberId: '999' } }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { memberId: '999' } }),
}))

vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    items: [],
    ensureLoaded: () => Promise.resolve(),
    nameOf: () => 'AI 全栈',
  }),
}))

const emptyPage = { items: [], page: 1, size: 20, total: 0 }

function dailyReport(overrides: Record<string, unknown> = {}) {
  return {
    id: '301',
    memberId: '201',
    memberName: '张三',
    memberStudentNo: 'MOCK20260001',
    directionId: '1',
    status: 'SUBMITTED',
    version: 2,
    scoreStatus: 'SCORED',
    awardedPoints: 2,
    finalScore: 2,
    scoreReason: '示例评语',
    firstSubmittedAt: '2026-10-09T10:00:00+08:00',
    updatedAt: '2026-10-09T10:05:00+08:00',
    editable: false,
    editableUntil: null,
    ruleVersion: 'mock-rule-1',
    reportDate: '2026-10-09',
    learningContent: '完成接口练习。',
    gains: '理解了参数校验。',
    problems: null,
    nextPlan: null,
    links: [],
    ...overrides,
  }
}

describe('报告管理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listDailyReports.mockResolvedValue(emptyPage)
    mocks.listWeeklyReports.mockResolvedValue(emptyPage)
  })

  it('日报/周报切换时重新加载对应类型，避免残留另一类型数据', async () => {
    const wrapper = mount(ReportManageView, {
      props: { reportType: 'DAILY' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(mocks.listDailyReports).toHaveBeenCalledTimes(1)
    expect(mocks.listWeeklyReports).not.toHaveBeenCalled()

    await wrapper.setProps({ reportType: 'WEEKLY' })
    await flushPromises()

    expect(mocks.listWeeklyReports).toHaveBeenCalledTimes(1)
    expect(mocks.listDailyReports).toHaveBeenCalledTimes(1)
  })

  it('切换日报/周报时关闭详情抽屉，避免旧报告套用新类型模板', async () => {
    mocks.listDailyReports.mockResolvedValue({
      items: [dailyReport()],
      page: 1,
      size: 20,
      total: 1,
    })
    mocks.getDailyReport.mockResolvedValue(dailyReport())

    const wrapper = mount(ReportManageView, {
      props: { reportType: 'DAILY' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const detailButton = wrapper.findAll('button').find((button) => button.text().includes('查看'))
    await detailButton!.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ReportDetailDrawer' }).props('modelValue')).toBe(true)

    await wrapper.setProps({ reportType: 'WEEKLY' })
    await flushPromises()

    expect(wrapper.findComponent({ name: 'ReportDetailDrawer' }).props('modelValue')).toBe(false)
  })

  it('点刷新重新拉取列表，学生刚提交的报告不用重建页面', async () => {
    const wrapper = mount(ReportManageView, {
      props: { reportType: 'DAILY' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(mocks.listDailyReports).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test="refresh"]').trigger('click')
    await flushPromises()
    expect(mocks.listDailyReports).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('切回标签页时自动刷新，页面开着也能看到新提交的报告', async () => {
    const wrapper = mount(ReportManageView, {
      props: { reportType: 'DAILY' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(mocks.listDailyReports).toHaveBeenCalledTimes(1)

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()

    expect(mocks.listDailyReports).toHaveBeenCalledTimes(2)

    // 卸载后要摘掉监听，否则离开页面还会继续请求
    wrapper.unmount()
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(mocks.listDailyReports).toHaveBeenCalledTimes(2)
  })

  it('列表展示成员姓名和学号，而不是成员 ID', async () => {    mocks.listDailyReports.mockResolvedValue({
      items: [dailyReport()],
      page: 1,
      size: 20,
      total: 1,
    })

    const wrapper = mount(ReportManageView, {
      props: { reportType: 'DAILY' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('张三')
    expect(text).toContain('MOCK20260001')
    expect(text).not.toContain('成员 ID')
  })
})
