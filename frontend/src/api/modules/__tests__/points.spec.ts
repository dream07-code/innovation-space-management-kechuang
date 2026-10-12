import { describe, expect, it, vi } from 'vitest'

import * as points from '@/api/modules/points'
import type { PointEntryPageFetcher } from '@/api/modules/points'
import type { PageData, PointEntry } from '@/api/types'

function entry(id: string, memberId: string, recordedAt: string): PointEntry {
  return {
    id,
    memberId,
    reportType: 'DAILY',
    reportId: `report-${id}`,
    reportVersion: 1,
    delta: 1,
    source: 'SUBMISSION_BASE',
    reason: `entry-${id}`,
    effectiveAt: recordedAt,
    recordedAt,
    ruleVersion: 'mock-rule-1',
  }
}

describe('collectPointEntries', () => {
  it('collects every member page, sorts newest first, and paginates after merging', async () => {
    const fetchPage = vi.fn<PointEntryPageFetcher>(async (request) => {
      if (request.memberId === '201' && request.page === 1) {
        return {
          items: [entry('3', '201', '2026-10-03T10:00:00+08:00'), entry('1', '201', '2026-10-01T10:00:00+08:00')],
          page: 1,
          size: request.size,
          total: 3,
        }
      }
      if (request.memberId === '201' && request.page === 2) {
        return {
          items: [entry('2', '201', '2026-10-02T10:00:00+08:00')],
          page: 2,
          size: request.size,
          total: 3,
        }
      }
      return {
        items: [entry('4', '202', '2026-10-04T10:00:00+08:00')],
        page: 1,
        size: request.size,
        total: 1,
      }
    })

    const result = await (points as unknown as {
      collectPointEntries: (
        memberIds: string[],
        filters: { month?: string },
        page: number,
        size: number,
        fetchPage: PointEntryPageFetcher,
      ) => Promise<PageData<PointEntry>>
    }).collectPointEntries(['201', '202', '201'], { month: '2026-10' }, 2, 2, fetchPage)

    expect(result).toEqual({
      items: [entry('2', '201', '2026-10-02T10:00:00+08:00'), entry('1', '201', '2026-10-01T10:00:00+08:00')],
      page: 2,
      size: 2,
      total: 4,
    })
    expect(fetchPage).toHaveBeenCalledTimes(3)
    expect(fetchPage.mock.calls.map(([request]) => request.memberId)).toEqual(['201', '201', '202'])
  })
})
