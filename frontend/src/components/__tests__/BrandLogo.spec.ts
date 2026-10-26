import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import BrandLogo from '@/components/BrandLogo.vue'

describe('BrandLogo', () => {
  it('使用“科创空间”品牌名并提供可访问的自定义标志', () => {
    const wrapper = mount(BrandLogo)

    expect(wrapper.text()).toContain('科创空间')
    expect(wrapper.text()).not.toContain('学院')
    expect(wrapper.find('.brand-logo__badge').exists()).toBe(true)

    const image = wrapper.find('img')
    expect(image.attributes('alt')).toBe('科创空间标志')
    // vite 对小于 4KB 的 svg 资产内联为 data URL，渲染等价且无需额外请求。
    expect(image.attributes('src')).toContain('data:image/svg+xml')
    expect(wrapper.find('svg').exists()).toBe(false)
  })
})
