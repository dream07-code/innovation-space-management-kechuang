import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElImage } from 'element-plus'

import ReportImageGallery from '../ReportImageGallery.vue'
import type { ReportImage } from '@/api/types'

function image(url: string, anchor: ReportImage['anchor'] = 'end'): ReportImage {
  return { url, name: url.split('/').pop() ?? 'note.jpg', size: 1024, anchor }
}

function mountWith(images: ReportImage[]) {
  return mount(ReportImageGallery, {
    props: { images },
    global: { plugins: [ElementPlus] },
  })
}

describe('ReportImageGallery', () => {
  it('没有图片时不渲染任何内容', () => {
    expect(mountWith([]).find('[data-test="report-gallery"]').exists()).toBe(false)
  })

  it('按顺序渲染缩略图，并在同一组内提供预览列表', () => {
    const images = [image('https://cdn.test/a.jpg'), image('https://cdn.test/b.jpg')]
    const thumbs = mountWith(images).findAllComponents(ElImage)

    expect(thumbs.map((thumb) => thumb.props('src'))).toEqual(images.map((item) => item.url))
    for (const thumb of thumbs) {
      expect(thumb.props('previewSrcList')).toEqual(images.map((item) => item.url))
    }
  })

  it('预览从被点击的那张开始', () => {
    const images = [image('https://cdn.test/a.jpg'), image('https://cdn.test/b.jpg')]
    const thumbs = mountWith(images).findAllComponents(ElImage)

    expect(thumbs.map((thumb) => thumb.props('initialIndex'))).toEqual([0, 1])
  })
})
