import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import DashboardTrendChart from '@/components/dashboard/DashboardTrendChart.vue'
import type { DashboardTrendPoint } from '@/api/types'

const points: DashboardTrendPoint[] = [
  { date: '2026-10-12', dailySubmitted: 3, dailyScored: 2, pointsAdded: 5 },
  { date: '2026-10-13', dailySubmitted: 4, dailyScored: 3, pointsAdded: 7 },
]

describe('DashboardTrendChart', () => {
  it('提供图形、文字摘要和可访问数据表', () => {
    const wrapper = mount(DashboardTrendChart, {
      props: { points, ariaLabel: '最近 2 天趋势' },
    })

    expect(wrapper.find('svg').attributes('aria-label')).toBe('最近 2 天趋势')
    expect(wrapper.text()).toContain('日报提交 7')
    expect(wrapper.text()).toContain('评分完成 5')
    expect(wrapper.text()).toContain('积分新增 12')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })

  it('没有趋势数据时展示空状态', () => {
    const wrapper = mount(DashboardTrendChart, { props: { points: [] } })
    expect(wrapper.find('.dashboard-empty').exists()).toBe(true)
  })
})
