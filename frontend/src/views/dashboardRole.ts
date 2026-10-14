import type { Role } from '@/api/types'

export type DashboardComponentName =
  | 'AdminDashboardView'
  | 'LeaderDashboardView'
  | 'StudentDashboardView'

/** 路由适配层只负责把角色映射到独立首页组件。 */
export function resolveDashboardComponent(role: Role | null): DashboardComponentName | null {
  if (role === 'COLLEGE_ADMIN') return 'AdminDashboardView'
  if (role === 'DIRECTION_LEADER') return 'LeaderDashboardView'
  if (role === 'STUDENT') return 'StudentDashboardView'
  return null
}
