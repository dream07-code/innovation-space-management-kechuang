import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import RankingsView from '@/views/shared/RankingsView.vue'

const mocks = vi.hoisted(() => ({
  getOverallRanking: vi.fn<(params: unknown) => Promise<unknown>>(),
  getDirectionRanking: vi.fn<(id: string, params: unknown) => Promise<unknown>>(),
  getMyRanking: vi.fn<(params: { period?: string; month?: string }) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/rankings', () => ({
  getOverallRanking: mocks.getOverallRanking,
  getDirectionRanking: mocks.getDirectionRanking,
  getMyRanking: mocks.getMyRanking,
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: false, role: 'STUDENT', user: { directionId: '1' } }),
}))

vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    ensureLoaded: () => Promise.resolve(),
    nameOf: () => 'AI 全栈',
  }),
}))

const emptyPage = {
  items: [],
  page: 1,
  size: 20,
  total: 0,
  period: 'ALL_TIME',
  month: null,
}

const myRanking = {
  memberId: '1',
  directionId: '1',
  period: 'ALL_TIME',
  month: null,
  points: 3,
  overallRank: 1,
  directionRank: 1,
  overallCount: 2,
  directionCount: 1,
  eligible: true,
  asOf: '2026-10-21T16:00:00+08:00',
}

describe('排行榜', () => {
  it('月度个人排名必须携带 month，否则后端 400 会导致总排名也显示未上榜', async () => {
    mocks.getOverallRanking.mockResolvedValue(emptyPage)
    mocks.getMyRanking.mockResolvedValue(myRanking)

    mount(RankingsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const params = mocks.getMyRanking.mock.calls.map((call) => call[0])
    expect(params.some((p) => p.period === 'ALL_TIME')).toBe(true)
    const monthly = params.find((p) => p.period === 'MONTH')
    expect(monthly?.month).toMatch(/^\d{4}-\d{2}$/)
  })
})
