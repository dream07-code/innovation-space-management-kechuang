import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ReportReviewView from '@/views/admin/ReportReviewView.vue'

const routerReplace = vi.hoisted(() => vi.fn<(to: unknown) => Promise<void>>(() => Promise.resolve()))
const routerPush = vi.hoisted(() => vi.fn<(to: unknown) => Promise<void>>(() => Promise.resolve()))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: routerReplace, push: routerPush }),
}))

const mocks = vi.hoisted(() => ({
  listDailyReports: vi.fn<(params: Record<string, unknown>) => Promise<unknown>>(),
  listWeeklyReports: vi.fn<(params: Record<string, unknown>) => Promise<unknown>>(),
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

vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    ensureLoaded: () => Promise.resolve(),
    nameOf: () => 'AI 全栈',
  }),
}))

function daily(overrides: Record<string, unknown> = {}) {
  return {
    id: '301',
    memberId: '201',
    memberName: '张三',
    memberStudentNo: 'MOCK20260001',
    directionId: '1',
    status: 'SUBMITTED',
    version: 2,
    scoreStatus: 'SCORED',
    finalScore: 2,
    scoreReason: null,
    awardedPoints: 2,
    editable: false,
    updatedAt: '2026-11-08T10:00:00+08:00',
    reportDate: '2026-11-08',
    learningContent: '今天做了接口练习',
    gains: '理解了参数校验',
    problems: null,
    nextPlan: null,
    links: [],
    images: [],
    ...overrides,
  }
}

function page(items: unknown[]) {
  return { items, page: 1, size: 20, total: items.length }
}

function mountReview(reportType: 'DAILY' | 'WEEKLY' = 'DAILY') {
  return mount(ReportReviewView, {
    props: { reportType },
    global: { plugins: [ElementPlus] },
  })
}

describe('日报/周报批阅页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getDailyReport.mockImplementation((id: string) => {
      const all = [
        daily({ id: '301', scoreStatus: 'SCORED', finalScore: 2, memberId: '201' }),
        daily({ id: '302', memberId: '201', reportDate: '2026-11-07', scoreStatus: 'MANUAL', finalScore: 3 }),
      ]
      const found = all.find((item) => item.id === id) ?? all[0]!
      return Promise.resolve(found)
    })
    mocks.listDailyReports.mockImplementation((params: Record<string, unknown>) => {
      if (params.memberId) {
        return Promise.resolve(page([daily({ id: '301', memberId: '201' })]))
      }
      // 队列：302 已人工评分，301 待评 -> 301 应排在前面
      return Promise.resolve(
        page([
          daily({ id: '302', memberId: '201', reportDate: '2026-11-07', scoreStatus: 'MANUAL', finalScore: 3 }),
          daily({ id: '301', memberId: '201', scoreStatus: 'SCORED', finalScore: 2 }),
        ]),
      )
    })
  })

  it('待批阅的排在最前，默认打开第一条并展示学生信息', async () => {
    const wrapper = mountReview()
    await flushPromises()

    expect(mocks.listDailyReports).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'SUBMITTED', page: 1, size: 200 }),
    )
    // 301 未人工评分，优先打开
    expect(mocks.getDailyReport).toHaveBeenCalledWith('301')
    expect(wrapper.get('[data-test="student-info"]').text()).toContain('张三')
    expect(wrapper.get('[data-test="student-info"]').text()).toContain('MOCK20260001')
    expect(wrapper.get('[data-test="review-detail"]').text()).toContain('今天做了接口练习')
    expect(wrapper.get('[data-test="student-reports-total"]').text()).toContain('共 1 条')
    wrapper.unmount()
  })

  it('点左侧记录可以切换右侧详情', async () => {
    mocks.listDailyReports.mockImplementation((params: Record<string, unknown>) => {
      if (params.memberId) {
        return Promise.resolve(
          page([
            daily({ id: '301' }),
            daily({ id: '302', reportDate: '2026-11-07', learningContent: '昨天的内容' }),
          ]),
        )
      }
      return Promise.resolve(page([daily({ id: '301' })]))
    })
    mocks.getDailyReport.mockImplementation((id: string) =>
      Promise.resolve(
        id === '302'
          ? daily({ id: '302', reportDate: '2026-11-07', learningContent: '昨天的内容' })
          : daily({ id: '301' }),
      ),
    )

    const wrapper = mountReview()
    await flushPromises()
    expect(mocks.getDailyReport).toHaveBeenLastCalledWith('301')

    const rows = wrapper.findAll('[data-test="student-reports"] tbody tr')
    expect(rows.length).toBe(2)
    await rows[1]!.trigger('click')
    await flushPromises()

    expect(mocks.getDailyReport).toHaveBeenLastCalledWith('302')
    expect(wrapper.get('[data-test="review-detail"]').text()).toContain('昨天的内容')
    wrapper.unmount()
  })

  it('打分后保存评分，并自动进入下一条待批阅', async () => {
    mocks.listDailyReports.mockImplementation((params: Record<string, unknown>) => {
      if (params.memberId) return Promise.resolve(page([daily({ id: '301' })]))
      return Promise.resolve(
        page([
          daily({ id: '301', scoreStatus: 'QUEUED', finalScore: null }),
          daily({ id: '302', memberId: '202', scoreStatus: 'QUEUED', finalScore: null, memberName: '李四' }),
        ]),
      )
    })
    mocks.scoreDailyReport.mockResolvedValue(
      daily({ id: '301', scoreStatus: 'MANUAL', finalScore: 3, scoreReason: null }),
    )

    const wrapper = mountReview()
    await flushPromises()
    expect(mocks.getDailyReport).toHaveBeenLastCalledWith('301')

    // 选 3 分后保存
    const radios = wrapper.findAll('[data-test="score-group"] input[type="radio"]')
    expect(radios.length).toBe(3)
    await radios[2]!.setValue(true)
    await flushPromises()
    await wrapper.get('[data-test="save-score"]').trigger('click')
    await flushPromises()

    expect(mocks.scoreDailyReport).toHaveBeenCalledWith('301', { score: 3, reason: null })
    // 保存后自动跳到下一条待批阅
    expect(mocks.getDailyReport).toHaveBeenLastCalledWith('302')
    wrapper.unmount()
  })

  it('周报批阅只调用周报接口，不与日报混排', async () => {
    mocks.listWeeklyReports.mockResolvedValue(page([]))

    const wrapper = mountReview('WEEKLY')
    await flushPromises()

    expect(mocks.listWeeklyReports).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'SUBMITTED' }),
    )
    expect(mocks.listDailyReports).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="empty-queue"]').text()).toContain('周报')
    wrapper.unmount()
  })
})
