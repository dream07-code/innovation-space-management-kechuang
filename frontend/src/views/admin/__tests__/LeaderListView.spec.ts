import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import LeaderListView from '@/views/admin/LeaderListView.vue'

const mocks = vi.hoisted(() => ({
  listLeaders: vi.fn<(params: unknown) => Promise<unknown>>(),
  appointLeader: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  listMembers: vi.fn<(params: unknown) => Promise<unknown>>(),
  revokeLeader: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
  updateLeader: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/leaders', () => ({
  listLeaders: mocks.listLeaders,
  appointLeader: mocks.appointLeader,
  revokeLeader: mocks.revokeLeader,
  updateLeader: mocks.updateLeader,
}))

vi.mock('@/api/modules/members', () => ({
  listMembers: mocks.listMembers,
}))

vi.mock('@/api/idempotency', () => ({
  newIdempotencyKey: () => 'test-idempotency-key',
}))

vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({
    items: [
      { id: '1', name: 'AI 全栈' },
      { id: '2', name: '鸿蒙' },
    ],
    ensureLoaded: () => Promise.resolve(),
    nameOf: (id: string) => (id === '2' ? '鸿蒙' : 'AI 全栈'),
  }),
}))

/** 用轻量 stub 代替真实 ElSelect，避免 jsdom 下多选组件的递归更新。 */
const ElSelectStub = {
  name: 'ElSelect',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<div class="select-stub" @click="$emit(\'update:modelValue\', [\'2\'])"></div>',
}

const mountOptions = {
  global: { plugins: [ElementPlus], stubs: { teleport: true, ElSelect: ElSelectStub } },
}

const emptyLeaders = { items: [], page: 1, size: 20, total: 0 }

const wangwu = {
  id: '3',
  studentNo: '20260003',
  name: '王五',
  directionId: '2',
  membershipStatus: 'ACTIVE',
  version: 1,
  managedDirectionIds: [],
}

describe('负责人管理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listLeaders.mockResolvedValue(emptyLeaders)
    mocks.listMembers.mockResolvedValue({ items: [wangwu], page: 1, size: 20, total: 1 })
    mocks.appointLeader.mockResolvedValue({})
  })

  it('按学号查找成员并提交任命，不再要求输入成员 ID', async () => {
    const wrapper = mount(LeaderListView, mountOptions)
    await flushPromises()

    const appointButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('任命负责人'))
    expect(appointButton).toBeTruthy()
    await appointButton!.trigger('click')
    await flushPromises()

    const studentNoInput = wrapper.find('.el-dialog input')
    await studentNoInput.setValue('20260003')
    await studentNoInput.trigger('blur')
    await flushPromises()

    expect(mocks.listMembers).toHaveBeenCalledWith({ keyword: '20260003', size: 20 })
    expect(wrapper.text()).toContain('已找到：王五')

    await wrapper.find('.select-stub').trigger('click')
    await flushPromises()

    await wrapper.find('.el-dialog textarea').setValue('联调验证：按学号任命负责人')
    const submit = wrapper
      .findAll('button')
      .find((button) => button.text().includes('确认任命'))
    await submit!.trigger('click')
    await flushPromises()

    expect(mocks.appointLeader).toHaveBeenCalledWith(
      {
        memberId: '3',
        version: 1,
        managedDirectionIds: ['2'],
        reason: '联调验证：按学号任命负责人',
      },
      'test-idempotency-key',
    )
  })

  it('学号不存在时给出提示且不提交', async () => {
    mocks.listMembers.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })

    const wrapper = mount(LeaderListView, mountOptions)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('任命负责人'))!
      .trigger('click')
    await flushPromises()

    const studentNoInput = wrapper.find('.el-dialog input')
    await studentNoInput.setValue('99999999')
    await studentNoInput.trigger('blur')
    await flushPromises()

    expect(wrapper.text()).toContain('未找到学号为 99999999 的成员')
    expect(mocks.appointLeader).not.toHaveBeenCalled()
  })
})
