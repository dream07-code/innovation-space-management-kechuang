import type { Role } from '@/api/types'

/** 个人排名仅面向学生；负责人只保留积分和公开榜单查看权限。 */
export function shouldShowPersonalRankings(role: Role | null): boolean {
  return role === 'STUDENT'
}
