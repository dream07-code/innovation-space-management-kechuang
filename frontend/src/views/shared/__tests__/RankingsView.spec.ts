import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import RankingsView from '@/views/shared/RankingsView.vue'

const auth = vi.hoisted(() => ({
  isAdmin: false,
  isLeader: false,
  role: 'STUDENT' as string,
  user: { directionId: '1' } as { directionId?: string } | null,
}))

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
  useAuthStore: () => auth,
}))

vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    ensureLoaded: () => Promise.resolve(),
    nameOf: () => 'AI 全栈',
    items: [
      { id: '1', name: 'AI 全栈' },
      { id: '2', name: '鸿蒙' },
    ],
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

async function clickTab(wrapper: Awaited<ReturnType<typeof mount>>, name: string): Promise<void> {
  await wrapper.get(`.el-tabs__item#tab-${name}`).trigger('click')
  await flushPromises()
}

describe('排行榜', () => {
  beforeEach(() => {
    auth.isAdmin = false
    auth.user = { directionId: '1' }
    mocks.getOverallRanking.mockReset().mockResolvedValue(emptyPage)
    mocks.getDirectionRanking.mockReset().mockResolvedValue(emptyPage)
    mocks.getMyRanking.mockReset().mockResolvedValue(myRanking)
  })

  it('月度个人排名必须携带 month，否则后端 400 会导致总排名也显示未上榜', async () => {
    mount(RankingsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const params = mocks.getMyRanking.mock.calls.map((call) => call[0])
    expect(params.some((p) => p.period === 'ALL_TIME')).toBe(true)
    const monthly = params.find((p) => p.period === 'MONTH')
    expect(monthly?.month).toMatch(/^\d{4}-\d{2}$/)
  })

  it('学生打开方向榜默认查看本人方向，且提供方向选择器', async () => {
    const wrapper = mount(RankingsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await clickTab(wrapper, 'direction')

    expect(mocks.getDirectionRanking).toHaveBeenCalledWith('1', { page: 1, size: 20 })
    expect(wrapper.find('[data-test="direction-select"]').exists()).toBe(true)
  })

  it('管理员也可见方向榜，默认查看方向列表第一个', async () => {
    auth.isAdmin = true
    auth.user = null
    const wrapper = mount(RankingsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await clickTab(wrapper, 'direction')

    expect(mocks.getDirectionRanking).toHaveBeenCalledWith('1', { page: 1, size: 20 })
    expect(wrapper.find('[data-test="direction-select"]').exists()).toBe(true)
  })
})
