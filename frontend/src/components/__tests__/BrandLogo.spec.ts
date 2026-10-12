import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import BrandLogo from '@/components/BrandLogo.vue'

describe('BrandLogo', () => {
  it('使用“科创空间”品牌名并提供可访问的自定义标志', () => {
    const wrapper = mount(BrandLogo)

    expect(wrapper.text()).toContain('科创空间')
    expect(wrapper.text()).not.toContain('学院')
    expect(wrapper.find('svg').attributes('aria-label')).toBe('科创空间标志')
    expect(wrapper.findAll('path')).toHaveLength(3)
  })
})
