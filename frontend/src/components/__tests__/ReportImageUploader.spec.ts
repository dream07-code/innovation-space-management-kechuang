import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import ReportImageUploader from '@/components/ReportImageUploader.vue'
import type { ReportImage } from '@/api/types'

const mocks = vi.hoisted(() => ({
  uploadReportImage: vi.fn<(file: File) => Promise<unknown>>(),
  compressImage: vi.fn<(file: File) => Promise<File>>(),
}))

vi.mock('@/api/modules/reports', () => ({ uploadReportImage: mocks.uploadReportImage }))
vi.mock('@/utils/imageCompress', () => ({ compressImage: mocks.compressImage }))

const ANCHORS = [
  { value: 'learningContent' as const, label: '放在「学习内容」后' },
  { value: 'end' as const, label: '放在文末' },
]

function image(index: number, anchor: ReportImage['anchor'] = 'end'): ReportImage {
  return {
    url: `https://cdn.example.com/reports/a${index}.jpg`,
    name: `a${index}.jpg`,
    size: 1024,
    anchor,
  }
}

function mountUploader(modelValue: ReportImage[], disabled = false) {
  return mount(ReportImageUploader, {
    props: { modelValue, anchors: ANCHORS, disabled },
    global: { plugins: [ElementPlus] },
  })
}

describe('ReportImageUploader', () => {
  it('渲染已有图片，展示缩略图与锚点', () => {
    const wrapper = mountUploader([image(1), image(2, 'learningContent')])
    expect(wrapper.findAll('.report-images__item')).toHaveLength(2)
    expect(wrapper.text()).toContain('放在文末')
  })

  it('删除图片时提交剩余列表', async () => {
    const wrapper = mountUploader([image(1), image(2)])
    await wrapper.get('[data-test="image-remove-0"]').trigger('click')
    const emitted = wrapper.emitted('update:modelValue')?.[0]?.[0] as ReportImage[]
    expect(emitted).toHaveLength(1)
    expect(emitted[0]?.url).toContain('a2.jpg')
  })

  it('只读时不给删除按钮，也没有上传入口', () => {
    const wrapper = mountUploader([image(1)], true)
    expect(wrapper.find('[data-test="image-remove-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="image-input"]').exists()).toBe(false)
  })

  it('选图后上传并在末尾追加', async () => {
    mocks.compressImage.mockImplementation(async (file: File) => file)
    mocks.uploadReportImage.mockResolvedValue({
      url: 'https://cdn.example.com/reports/new.jpg',
      name: 'new.jpg',
      size: 4096,
      contentType: 'image/jpeg',
    })
    const wrapper = mountUploader([image(1)])

    const input = wrapper.get('[data-test="image-input"]')
    const file = new File([new Uint8Array([1, 2, 3])], 'new.jpg', { type: 'image/jpeg' })
    Object.defineProperty(input.element, 'files', { value: [file], writable: true })
    await input.trigger('change')
    await flushPromises()

    expect(mocks.compressImage).toHaveBeenCalledTimes(1)
    const emitted = wrapper.emitted('update:modelValue')?.[0]?.[0] as ReportImage[]
    expect(emitted).toHaveLength(2)
    expect(emitted[1]).toMatchObject({
      url: 'https://cdn.example.com/reports/new.jpg',
      name: 'new.jpg',
      anchor: 'end',
    })
  })

  it('满 10 张后不再提供上传入口', () => {
    const full = Array.from({ length: 10 }, (_, i) => image(i))
    const wrapper = mountUploader(full)
    expect(wrapper.find('[data-test="image-input"]').exists()).toBe(false)
  })
})
