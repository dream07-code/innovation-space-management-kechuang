import { describe, expect, it } from 'vitest'

import { resolveDashboardComponent } from '@/views/dashboardRole'

describe('角色首页组件解析', () => {
  it('三个角色使用不同的首页组件', () => {
    expect(resolveDashboardComponent('COLLEGE_ADMIN')).toBe('AdminDashboardView')
    expect(resolveDashboardComponent('DIRECTION_LEADER')).toBe('LeaderDashboardView')
    expect(resolveDashboardComponent('STUDENT')).toBe('StudentDashboardView')
    expect(resolveDashboardComponent(null)).toBeNull()
  })
})
