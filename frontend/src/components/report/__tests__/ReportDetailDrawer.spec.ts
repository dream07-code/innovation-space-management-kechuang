import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'

import ReportDetailDrawer from '@/components/report/ReportDetailDrawer.vue'

const mocks = vi.hoisted(() => ({
  getDailyReport: vi.fn<(id: string) => Promise<unknown>>(),
  scoreDailyReport: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/reports', () => ({
  getDailyReport: mocks.getDailyReport,
  getWeeklyReport: vi.fn<(id: string) => Promise<unknown>>(),
  scoreDailyReport: mocks.scoreDailyReport,
  scoreWeeklyReport: vi.fn<(id: string, payload: unknown) => Promise<unknown>>(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { memberId: '999' } }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { memberId: '999' } }),
}))

vi.mock('@/stores/directions', () => ({
  useDirectionsStore: () => ({ nameOf: () => 'AI 全栈' }),
}))

const report = {
  id: '301',
  memberId: '201',
  memberName: '张三',
  memberStudentNo: 'MOCK20260001',
  directionId: '1',
  status: 'SUBMITTED',
  version: 2,
  scoreStatus: 'SCORED',
  awardedPoints: 2,
  finalScore: 2,
  scoreReason: '示例评语',
  firstSubmittedAt: '2026-10-09T10:00:00+08:00',
  updatedAt: '2026-10-09T10:05:00+08:00',
  editable: false,
  editableUntil: null,
  ruleVersion: 'mock-rule-1',
  reportDate: '2026-10-09',
  learningContent: '完成接口练习。',
  gains: '理解了参数校验。',
  problems: null,
  nextPlan: null,
  links: [],
}

describe('报告详情抽屉', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('展示成员姓名、报告内容与人工评分入口', async () => {
    mocks.getDailyReport.mockResolvedValue(report)

    mount(ReportDetailDrawer, {
      props: { modelValue: true, reportType: 'DAILY', reportId: '301' },
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(mocks.getDailyReport).toHaveBeenCalledWith('301')
    const text = document.body.textContent ?? ''
    expect(text).toContain('张三')
    expect(text).toContain('MOCK20260001')
    expect(text).toContain('完成接口练习。')
    expect(text).toContain('保存人工评分')
    expect(text).toContain('评分标准（v2）')
  })
})
