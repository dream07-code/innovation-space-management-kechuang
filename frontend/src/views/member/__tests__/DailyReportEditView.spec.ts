import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import DailyReportEditView from '@/views/member/DailyReportEditView.vue'

const mocks = vi.hoisted(() => ({
  createDailyReport: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  getDailyReport: vi.fn<(id: string) => Promise<unknown>>(),
  submitDailyReport: vi.fn<(id: string, version: number, key: string) => Promise<unknown>>(),
  updateDailyReport: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/reports', () => ({
  createDailyReport: mocks.createDailyReport,
  getDailyReport: mocks.getDailyReport,
  submitDailyReport: mocks.submitDailyReport,
  updateDailyReport: mocks.updateDailyReport,
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {} }),
  useRouter: () => ({ replace: () => Promise.resolve(), push: () => Promise.resolve(), back: () => undefined }),
}))

describe('DailyReportEditView', () => {
  it('只渲染三个正文输入框并发送精简创建载荷', async () => {
    mocks.createDailyReport.mockResolvedValue({
      id: '501',
      version: 1,
      status: 'DRAFT',
      scoreStatus: 'NOT_REQUESTED',
      reportDate: '2026-10-13',
      learningContent: '今天学习了 Java',
      gains: '理解集合',
      problems: null,
    })

    const wrapper = mount(DailyReportEditView, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('报告日期')
    expect(wrapper.text()).not.toContain('下一步计划')
    expect(wrapper.text()).not.toContain('相关链接')
    expect(wrapper.findAll('textarea')).toHaveLength(3)

    const textareas = wrapper.findAll('textarea')
    const learningContent = textareas[0]
    const gains = textareas[1]
    if (!learningContent || !gains) throw new Error('日报正文输入框缺失')
    await learningContent.setValue('  今天学习了 Java  ')
    await gains.setValue('  理解集合  ')
    await wrapper.get('[data-test="save-draft"]').trigger('click')
    await flushPromises()

    expect(mocks.createDailyReport).toHaveBeenCalledWith(
      { learningContent: '今天学习了 Java', gains: '理解集合' },
      expect.any(String),
    )
  })
})
