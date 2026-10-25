import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AnnouncementsManageView from '@/views/admin/AnnouncementsManageView.vue'

const auth = vi.hoisted(() => ({
  isAdmin: true,
  isLeader: false,
  user: null as { directionId?: string; managedDirectionIds?: string[] } | null,
}))
const push = vi.hoisted(() => vi.fn<(route: unknown) => void>())
const mocks = vi.hoisted(() => ({
  listAnnouncements: vi.fn<() => Promise<unknown>>(),
  createAnnouncement: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  withdrawAnnouncement: vi.fn<(id: string, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/announcements', () => ({
  listAnnouncements: mocks.listAnnouncements,
  createAnnouncement: mocks.createAnnouncement,
  withdrawAnnouncement: mocks.withdrawAnnouncement,
}))
vi.mock('@/api/idempotency', () => ({ newIdempotencyKey: () => 'test-key' }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    items: [
      { id: '1', name: 'AI 全栈' },
      { id: '2', name: '鸿蒙' },
    ],
    ensureLoaded: () => Promise.resolve(),
  }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {}, query: {} }),
  useRouter: () => ({ push }),
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessageBox: {
      ...actual.ElMessageBox,
      confirm: vi.fn<() => Promise<string>>().mockResolvedValue('confirm'),
    },
  }
})

const collegeItem = {
  id: '1',
  title: '学院通知',
  content: '全体停课一天',
  scope: 'COLLEGE',
  directionId: null,
  directionName: null,
  createdAt: '2026-10-26T09:00:00+08:00',
}
const directedItem = {
  id: '2',
  title: '方向通知',
  content: '方向一答辩',
  scope: 'DIRECTION',
  directionId: '1',
  directionName: 'AI 全栈',
  createdAt: '2026-10-26T10:00:00+08:00',
}

const mountOptions = {
  global: {
    plugins: [ElementPlus],
    stubs: { teleport: true, ElSelect: { template: '<div class="select-stub" />' } },
  },
}

describe('AnnouncementsManageView 公告管理', () => {
  beforeEach(() => {
    auth.isAdmin = true
    auth.user = null
    mocks.listAnnouncements.mockReset().mockResolvedValue({
      items: [collegeItem, directedItem],
      total: 2,
    })
    mocks.createAnnouncement.mockReset().mockResolvedValue(collegeItem)
    mocks.withdrawAnnouncement.mockReset().mockResolvedValue(collegeItem)
  })

  afterEach(() => {
    while (document.body.firstChild) document.body.removeChild(document.body.firstChild)
  })

  it('管理员可发学院级公告（不带 directionId）', async () => {
    const wrapper = mount(AnnouncementsManageView, mountOptions)
    await flushPromises()

    await wrapper.get('[data-test="announcement-title"]').setValue('考试安排')
    await wrapper
      .get('[data-test="announcement-content"]')
      .setValue('周六上午 C++ 考核')
    await wrapper.get('[data-test="publish-announcement"]').trigger('click')
    await flushPromises()

    expect(mocks.createAnnouncement).toHaveBeenCalledWith(
      { title: '考试安排', content: '周六上午 C++ 考核' },
      'test-key',
    )
    expect(mocks.listAnnouncements).toHaveBeenCalledTimes(2)
  })

  it('负责人方向锁定：发布必然携带所辖方向 ID', async () => {
    auth.isAdmin = false
    auth.isLeader = true
    auth.user = { directionId: '1', managedDirectionIds: ['1'] }
    const wrapper = mount(AnnouncementsManageView, mountOptions)
    await flushPromises()

    await wrapper.get('[data-test="announcement-title"]').setValue('方向通知')
    await wrapper.get('[data-test="announcement-content"]').setValue('答辩')
    await wrapper.get('[data-test="publish-announcement"]').trigger('click')
    await flushPromises()

    expect(mocks.createAnnouncement).toHaveBeenCalledWith(
      { title: '方向通知', content: '答辩', directionId: '1' },
      'test-key',
    )
  })

  it('列表展示两级范围标签，撤回需确认并带幂等键', async () => {
    const wrapper = mount(AnnouncementsManageView, mountOptions)
    await flushPromises()

    expect(wrapper.text()).toContain('学院级')
    expect(wrapper.text()).toContain('AI 全栈')

    await wrapper.get('[data-test="withdraw-2"]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(mocks.withdrawAnnouncement).toHaveBeenCalledWith('2', 'test-key')
    expect(mocks.listAnnouncements).toHaveBeenCalledTimes(2)
  })
})
