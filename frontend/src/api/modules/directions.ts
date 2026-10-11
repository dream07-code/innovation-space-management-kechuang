/**
 * 方向目录接口。
 *
 * 契约：GET /api/v1/directions，所有角色可读。
 * 七个方向由后端预置（backend 分支 V2__seed_directions.sql），首期不支持新增或更名。
 * 契约中不存在方向的写接口。
 *
 * 该接口是几乎所有筛选条件的取值来源，因此由 directions store 缓存。
 */
import { API_PREFIX, get } from '../client'
import type { Direction } from '../types'

/** 方向列表。契约中 data 直接是数组，不是分页对象。 */
export async function listDirections(): Promise<Direction[]> {
  return get<Direction[]>(`${API_PREFIX}/directions`)
}
