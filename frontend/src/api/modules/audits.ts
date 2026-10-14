/**
 * 审计接口。仅学院管理员可读，只返回脱敏摘要
 * （无密码、token、联系方式、报告全文）。
 */
import { API_PREFIX, get } from '../client'
import type { AuditEntry, PageData } from '../types'

export interface ListAuditsParams {
  page?: number
  size?: number
  resourceId?: string
  action?: string
}

export async function listAudits(params: ListAuditsParams = {}): Promise<PageData<AuditEntry>> {
  return get<PageData<AuditEntry>>(`${API_PREFIX}/audits`, { params })
}
