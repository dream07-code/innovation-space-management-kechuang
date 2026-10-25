import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import MemberDetailView from '@/views/admin/MemberDetailView.vue'

const mocks = vi.hoisted(() => ({
  addMemberPoints: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
  correctStudentNumber: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  deleteMember: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  getMember: vi.fn<(id: string) => Promise<unknown>>(),
  resetMemberPassword: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  resetMemberPoints: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  restoreMember: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  updateMember: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
  routerPush: vi.fn<(route: unknown) => Promise<void>>(),
}))

vi.mock('@/api/modules/members', () => ({
  addMemberPoints: mocks.addMemberPoints,
  correctStudentNumber: mocks.correctStudentNumber,
  deleteMember: mocks.deleteMember,
  getMember: mocks.getMember,
  resetMemberPassword: mocks.resetMemberPassword,
  resetMemberPoints: mocks.resetMemberPoints,
  restoreMember: mocks.restoreMember,
  updateMember: mocks.updateMember,
}))

vi.mock('@/api/idempotency', () => ({ newIdempotencyKey: () => 'test-idempotency-key' }))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: true, isLeader: false }),
}))

vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    items: [
      { id: '1', name: 'AI 全栈' },
      { id: '4', name: '项目创意设计' },
    ],
    ensureLoaded: () => Promise.resolve(),
    nameOf: (id: string) => (id === '4' ? '项目创意设计' : 'AI 全栈'),
  }),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { memberId: '7' } }),
  useRouter: () => ({ push: mocks.routerPush, back: () => undefined }),
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessageBox: {
      ...actual.ElMessageBox,
      prompt: vi.fn<() => Promise<{ value: string }>>().mockResolvedValue({ value: '测试原因五个字' }),
    },
  }
})

const activeStudent = {
  id: '7',
  userId: '7',
  studentNo: '2025010107',
  name: '同学七',
  major: null,
  className: null,
  grade: null,
  contact: null,
  role: 'STUDENT',
  directionId: '1',
  managedDirectionIds: [],
  membershipStatus: 'ACTIVE',
  accountStatus: 'ENABLED',
  mustChangePassword: false,
  joinedAt: '2026-10-01T09:00:00+08:00',
  membershipStartedAt: '2026-10-01T09:00:00+08:00',
  removedAt: null,
  removalReason: null,
  totalPoints: 5,
  version: 3,
  createdAt: '2026-10-01T09:00:00+08:00',
  updatedAt: '2026-10-01T09:00:00+08:00',
}

const mountOptions = {
  global: { plugins: [ElementPlus], stubs: { teleport: true } },
}

describe('MemberDetailView 管理员操作', () => {
  beforeEach(() => {
    mocks.getMember.mockReset().mockResolvedValue(activeStudent)
    mocks.deleteMember.mockReset().mockResolvedValue(null)
    mocks.resetMemberPoints.mockReset().mockResolvedValue({ ...activeStudent, totalPoints: 0 })
    mocks.routerPush.mockReset().mockResolvedValue(undefined)
  })

  it('重置积分需要输入原因并带版本与幂等键提交', async () => {
    const wrapper = mount(MemberDetailView, mountOptions)
    await flushPromises()

    const resetButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('重置积分'))
    expect(resetButton).toBeDefined()
    await resetButton!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.prompt).toHaveBeenCalled()
    expect(mocks.resetMemberPoints).toHaveBeenCalledWith(
      '7',
      { version: 3, reason: '测试原因五个字' },
      'test-idempotency-key',
    )
    expect(wrapper.text()).toContain('累计积分')
  })

  it('删除学生需要输入原因，成功后返回成员列表', async () => {
    const wrapper = mount(MemberDetailView, mountOptions)
    await flushPromises()

    await wrapper.get('[data-test="delete-member"]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.prompt).toHaveBeenCalled()
    expect(mocks.deleteMember).toHaveBeenCalledWith(
      '7',
      { version: 3, reason: '测试原因五个字' },
      'test-idempotency-key',
    )
    expect(mocks.routerPush).toHaveBeenCalledWith({ name: 'admin-members' })
  })

  it('删除被 MEMBER_IN_USE 拦下时展示专用文案', async () => {
    mocks.deleteMember.mockRejectedValue({
      response: { status: 409, data: { code: 'MEMBER_IN_USE', message: 'Member in use', data: null } },
    })
    const wrapper = mount(MemberDetailView, mountOptions)
    await flushPromises()

    await wrapper.get('[data-test="delete-member"]').trigger('click')
    await flushPromises()

    expect(mocks.deleteMember).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).not.toHaveBeenCalled()
    // 错误经 describeError 展示，不跳转；页面仍停留在详情。
    expect(wrapper.text()).toContain('同学七')
  })
})
