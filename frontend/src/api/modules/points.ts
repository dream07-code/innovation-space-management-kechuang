/**
 * 积分接口。
 *
 * 契约：docs/api/openapi.yaml 的 积分 tag。
 * 积分只由流水净和得到，前端没有任何写入积分的接口
 * （管理员加分走 members.addMemberPoints，那是成员接口）。
 */
import { API_PREFIX, get } from '../client'
import type { PageData, PointEntry, PointsSummary } from '../types'

const BASE = `${API_PREFIX}/points`

/**
 * 本人累计和本月积分。month 不传为业务时区当前月。
 * 月度积分只用于展示，不触发任何移出 —— 月度淘汰已取消。
 */
export async function getMyPoints(month?: string): Promise<PointsSummary> {
  return get<PointsSummary>(`${BASE}/me`, { params: month ? { month } : {} })
}

export interface ListPointEntriesParams {
  page?: number
  size?: number
  /**
   * 学生/负责人不传默认本人；管理员必须传（缺失 400）。
   * 负责人指定他人时只能是当前所辖成员。
   */
  memberId?: string
  /** 业务生效月份（YYYY-MM），不传不限月份。 */
  month?: string
}

export async function listPointEntries(
  params: ListPointEntriesParams = {},
): Promise<PageData<PointEntry>> {
  return get<PageData<PointEntry>>(`${BASE}/ledger`, { params })
}


export type PointEntryPageFetcher = (
  params: Required<Pick<ListPointEntriesParams, 'memberId' | 'page' | 'size'>> &
    Pick<ListPointEntriesParams, 'month'>,
) => Promise<PageData<PointEntry>>

/**
 * 汇总多个成员的积分流水并做稳定分页。
 *
 * 契约的 /points/ledger 对管理员要求 memberId，因此管理端“全量流水”由前端
 * 按权限范围逐成员收集后合并。只读展示不改变任何积分，也不绕过服务端权限。
 */
export async function collectPointEntries(
  memberIds: string[],
  filters: Pick<ListPointEntriesParams, 'month'>,
  page: number,
  size: number,
  fetchPage: PointEntryPageFetcher = listPointEntries,
): Promise<PageData<PointEntry>> {
  const uniqueMemberIds = [...new Set(memberIds.filter(Boolean))]
  const allItems: PointEntry[] = []

  for (const memberId of uniqueMemberIds) {
    let currentPage = 1
    let received = 0
    let total = 0
    do {
      const result = await fetchPage({
        memberId,
        page: currentPage,
        size: 200,
        month: filters.month,
      })
      allItems.push(...result.items)
      received += result.items.length
      total = result.total
      currentPage += 1
    } while (received < total && received > 0)
  }

  allItems.sort((a, b) => {
    const byTime = b.recordedAt.localeCompare(a.recordedAt)
    if (byTime !== 0) return byTime
    const aId = BigInt(a.id)
    const bId = BigInt(b.id)
    return aId === bId ? 0 : aId > bId ? -1 : 1
  })

  const start = Math.max(0, (page - 1) * size)
  return {
    items: allItems.slice(start, start + size),
    page,
    size,
    total: allItems.length,
  }
}
