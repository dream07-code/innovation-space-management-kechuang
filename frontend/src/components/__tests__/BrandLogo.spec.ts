import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import BrandLogo from '@/components/BrandLogo.vue'

describe('BrandLogo', () => {
  it('使用“科创空间”品牌名并提供可访问的自定义标志', () => {
    const wrapper = mount(BrandLogo)

    expect(wrapper.find('.brand-logo__badge').exists()).toBe(true)

    const image = wrapper.find('img')
    expect(image.attributes('alt')).toBe('科创空间标志')
    // 矢量描摹的品牌图超过 4KB，vite 保持 URL 引用。
    expect(image.attributes('src')).toContain('brand-logo.svg')
  })
})
