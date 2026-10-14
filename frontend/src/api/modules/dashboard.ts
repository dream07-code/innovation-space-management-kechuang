/**
 * 角色数据看板接口。
 *
 * 三个角色分别调用独立聚合接口；前端不得调用多个成员、报告、积分接口
 * 自行拼装首页数据。range 只允许 7d 或 30d。
 */
import { API_PREFIX, get } from '../client'
import type {
  AdminDashboard,
  DashboardRange,
  LeaderDashboard,
  StudentDashboard,
} from '../types'

const BASE = `${API_PREFIX}/dashboard`

export async function getAdminDashboard(
  range: DashboardRange = '30d',
): Promise<AdminDashboard> {
  return get<AdminDashboard>(`${BASE}/admin`, { params: { range } })
}

export async function getLeaderDashboard(
  range: DashboardRange = '7d',
): Promise<LeaderDashboard> {
  return get<LeaderDashboard>(`${BASE}/leader`, { params: { range } })
}

export async function getStudentDashboard(
  range: DashboardRange = '7d',
): Promise<StudentDashboard> {
  return get<StudentDashboard>(`${BASE}/student`, { params: { range } })
}
