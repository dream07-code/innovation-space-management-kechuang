import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import DailyReportListView from '@/views/member/DailyReportListView.vue'
import WeeklyReportListView from '@/views/member/WeeklyReportListView.vue'

const mocks = vi.hoisted(() => ({
  listDailyReports: vi.fn<(params: unknown) => Promise<unknown>>(),
  listWeeklyReports: vi.fn<(params: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/reports', () => ({
  listDailyReports: mocks.listDailyReports,
  listWeeklyReports: mocks.listWeeklyReports,
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { memberId: '42' } }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: () => Promise.resolve() }),
}))

const emptyPage = { items: [], page: 1, size: 20, total: 0 }

describe('个人报告列表', () => {
  it('日报只查询当前成员的报告', async () => {
    mocks.listDailyReports.mockResolvedValue(emptyPage)

    mount(DailyReportListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(mocks.listDailyReports).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      memberId: '42',
    })
  })

  it('周报只查询当前成员的报告', async () => {
    mocks.listWeeklyReports.mockResolvedValue(emptyPage)

    mount(WeeklyReportListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(mocks.listWeeklyReports).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      memberId: '42',
    })
  })
})
