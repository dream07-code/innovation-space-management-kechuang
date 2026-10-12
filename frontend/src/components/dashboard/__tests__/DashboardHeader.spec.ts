import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'

import DashboardHeader from '@/components/dashboard/DashboardHeader.vue'

describe('DashboardHeader', () => {
  it('时间范围切换只发出目标范围，刷新保留当前范围', async () => {
    const wrapper = mount(DashboardHeader, {
      props: {
        title: '测试看板',
        range: '7d',
        updatedAt: '2026-10-13T10:00:00+08:00',
        loading: false,
      },
      global: { plugins: [ElementPlus] },
    })

    const rangeControl = wrapper.findComponent({ name: 'ElRadioGroup' })
    await rangeControl.vm.$emit('update:model-value', '30d')
    expect(wrapper.emitted('update:range')?.[0]).toEqual(['30d'])

    await wrapper.find('button[aria-label="刷新数据"]').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })
})
