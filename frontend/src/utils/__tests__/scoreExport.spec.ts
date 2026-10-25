import { describe, expect, it } from 'vitest'

import { assignRanks, buildScoreRows } from '@/utils/scoreExport'
import type { AssessmentMemberScore } from '@/api/types'

function score(partial: Partial<AssessmentMemberScore> & { memberId: string }): AssessmentMemberScore {
  return {
    memberName: '成员',
    memberStudentNo: '20260001',
    itemCount: 5,
    answeredCount: 5,
    completedCount: 5,
    pendingCount: 0,
    totalScore: 100,
    maxScore: 200,
    scoreRate: 50,
    sheetStatus: 'SUBMITTED',
    sheetScore: 10,
    lastSubmittedAt: null,
    ...partial,
  } as AssessmentMemberScore
}

describe('scoreExport 成绩导出', () => {
  it('同分并列竞赛排名（1,1,3）', () => {
    const scores = [
      score({ memberId: '3', totalScore: 80 }),
      score({ memberId: '1', totalScore: 120 }),
      score({ memberId: '2', totalScore: 120 }),
    ]
    expect(assignRanks(scores)).toEqual([3, 1, 1])
  })

  it('行覆盖全部成员且列口径与结果页一致', () => {
    const rows = buildScoreRows([
      score({
        memberId: '1',
        memberName: '冀玉成',
        memberStudentNo: '202602150114',
        completedCount: 4,
        itemCount: 5,
        totalScore: 110,
        scoreRate: 51,
        sheetStatus: 'SUBMITTED',
        pendingCount: 0,
        lastSubmittedAt: '2026-10-25T17:49:00+08:00',
      }),
      score({
        memberId: '2',
        memberName: '判题验证员',
        memberStudentNo: 'judge2026',
        completedCount: 0,
        itemCount: 5,
        totalScore: 0,
        scoreRate: 0,
        sheetStatus: 'NONE',
        lastSubmittedAt: null,
      }),
      score({
        memberId: '3',
        memberName: '草稿生',
        memberStudentNo: '20260003',
        completedCount: 0,
        itemCount: 5,
        totalScore: 0,
        scoreRate: 0,
        sheetStatus: 'DRAFT',
        lastSubmittedAt: null,
      }),
    ])

    expect(rows).toHaveLength(3)
    expect(rows[0]).toMatchObject({
      排名: 1,
      姓名: '冀玉成',
      学号: '202602150114',
      完成条目: '4 / 5',
      客观题状态: '已判分',
      总分: 110,
      满分: 200,
      得分率: '51%',
      最近提交: '2026-10-25T17:49:00+08:00',
    })
    expect(rows[1]).toMatchObject({ 排名: 2, 客观题状态: '未交卷', 最近提交: '未提交' })
    expect(rows[2]).toMatchObject({ 客观题状态: '未交卷(有草稿)' })
  })
})
