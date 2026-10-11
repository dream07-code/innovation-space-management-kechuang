/**
 * 系统规则接口。
 *
 * 契约：GET /api/v1/system/rules，所有角色可读。
 *
 * 该接口返回前端所需的业务规则及其确认状态。规则状态是**数据驱动**的：
 *   - PENDING_CONFIRMATION：规则尚未经学院确认，界面只能展示骨架，
 *     不得启用真实执行，也不得用隐藏默认值替代（如「每个方向最后 3 人」）；
 *   - READY：规则已确认，相关功能才可放开。
 *
 * 需求文档 §8.1、API 契约 §7.2、验收项 AC-09 三处都强调：
 * 前端不得硬编码淘汰人数、不得按本机日期执行移出、不得根据排名自行删除人员。
 * 因此本模块只读规则，绝不内置任何业务默认值。
 */
import { API_PREFIX, get } from '../client'
import type { SystemRules } from '../types'

/** 读取当前生效的业务规则与各策略的确认状态。 */
export async function fetchSystemRules(): Promise<SystemRules> {
  return get<SystemRules>(`${API_PREFIX}/system/rules`)
}
