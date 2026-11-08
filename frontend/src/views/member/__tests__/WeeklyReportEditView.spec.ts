import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WeeklyReportEditView from '@/views/member/WeeklyReportEditView.vue'
import { isWeekStart } from '@/views/member/weeklyReportForm'

const mocks = vi.hoisted(() => ({
  createWeeklyReport: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
  getWeeklyReport: vi.fn<(id: string) => Promise<unknown>>(),
  submitWeeklyReport: vi.fn<(id: string, version: number, key: string) => Promise<unknown>>(),
  updateWeeklyReport: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/reports', () => ({
  createWeeklyReport: mocks.createWeeklyReport,
  getWeeklyReport: mocks.getWeeklyReport,
  submitWeeklyReport: mocks.submitWeeklyReport,
  updateWeeklyReport: mocks.updateWeeklyReport,
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {} }),
  useRouter: () => ({ replace: () => Promise.resolve(), push: () => Promise.resolve(), back: () => undefined }),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

function mountView() {
  return mount(WeeklyReportEditView, { global: { plugins: [ElementPlus] } })
}

async function fillRequired(wrapper: ReturnType<typeof mountView>) {
  const textareas = wrapper.findAll('textarea')
  await textareas[0]!.setValue('本周完成了接口联调')
  await textareas[1]!.setValue('交付了两个接口')
}

describe('WeeklyReportEditView', () => {
  it('默认周开始是本周一，不是周日', async () => {
    const wrapper = mountView()
    await flushPromises()

    const value = (wrapper.get('[data-test="week-start"] input').element as HTMLInputElement).value
    expect(value).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(isWeekStart(value)).toBe(true)
  })

  it('相关链接不是 http(s) 时拦在本地，不发请求', async () => {
    const wrapper = mountView()
    await flushPromises()
    await fillRequired(wrapper)
    await wrapper.get('[data-test="links"] textarea').setValue('https://docs.qq.com/a\n请问')

    await wrapper.get('[data-test="save-draft"]').trigger('click')
    await flushPromises()

    expect(mocks.createWeeklyReport).not.toHaveBeenCalled()
    // 提示走 ElMessage（渲染到 body），不在表单 DOM 里
    expect(document.body.textContent).toContain('第 2 行不是有效链接')
  })

  it('链接合法时按行拆分提交，正文去掉首尾空格', async () => {
    mocks.createWeeklyReport.mockResolvedValue({
      id: '601',
      version: 1,
      status: 'DRAFT',
      scoreStatus: 'NOT_REQUESTED',
      weekStart: '2026-11-02',
      weekEnd: '2026-11-08',
      summary: '本周完成了接口联调',
      achievements: '交付了两个接口',
    })

    const wrapper = mountView()
    await flushPromises()
    await fillRequired(wrapper)
    await wrapper.get('[data-test="links"] textarea').setValue('https://docs.qq.com/a\n\n  http://example.com/b  ')

    await wrapper.get('[data-test="save-draft"]').trigger('click')
    await flushPromises()

    expect(mocks.createWeeklyReport).toHaveBeenCalledTimes(1)
    const payload = mocks.createWeeklyReport.mock.calls[0]![0] as Record<string, unknown>
    expect(payload.links).toEqual(['https://docs.qq.com/a', 'http://example.com/b'])
    expect(isWeekStart(String(payload.weekStart))).toBe(true)
  })
})
