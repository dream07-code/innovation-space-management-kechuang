/**
 * 考核成绩按学生汇总导出 Excel。
 *
 * member-scores 单页上限 200，导出必须循环取到 total，漏页等于成绩单缺人。
 * 列口径与结果页一致：排名按总分降序同分并列，客观题状态三态文案与页面相同。
 */
import * as XLSX from 'xlsx'

import { listAssessmentMemberScores } from '@/api/modules/oj'
import type { AssessmentMemberScore } from '@/api/types'

const PAGE_SIZE = 200

function objectStatus(score: AssessmentMemberScore): string {
  if (score.sheetStatus === 'SUBMITTED') {
    return score.pendingCount > 0 ? `${score.pendingCount} 题待评分` : '已判分'
  }
  return score.sheetStatus === 'DRAFT' ? '未交卷(有草稿)' : '未交卷'
}

/** 计算竞赛排名（同分并列，1,1,3），输入顺序不限。 */
export function assignRanks(scores: AssessmentMemberScore[]): number[] {
  const sorted = [...scores].sort(
    (a, b) => b.totalScore - a.totalScore || Number(a.memberId) - Number(b.memberId),
  )
  const ranks = new Map<string, number>()
  sorted.forEach((score, index) => {
    const prev = sorted[index - 1]
    ranks.set(
      score.memberId,
      prev && prev.totalScore === score.totalScore ? ranks.get(prev.memberId)! : index + 1,
    )
  })
  return scores.map((score) => ranks.get(score.memberId)!)
}

/** 把分页结果压平为工作表行（列名即表头）。 */
export function buildScoreRows(
  scores: AssessmentMemberScore[],
): Record<string, string | number>[] {
  const ranks = assignRanks(scores)
  return scores.map((score, index) => ({
    排名: ranks[index],
    姓名: score.memberName ?? '',
    学号: score.memberStudentNo ?? '',
    完成条目: `${score.completedCount} / ${score.itemCount}`,
    客观题状态: objectStatus(score),
    总分: score.totalScore,
    满分: score.maxScore,
    得分率: `${score.scoreRate}%`,
    最近提交: score.lastSubmittedAt ?? '未提交',
  }))
}

/** 拉取全部分页数据直到 total。 */
async function fetchAllScores(assessmentId: string): Promise<AssessmentMemberScore[]> {
  const all: AssessmentMemberScore[] = []
  for (let page = 1; ; page++) {
    const data = await listAssessmentMemberScores(assessmentId, { page, size: PAGE_SIZE })
    all.push(...data.items)
    if (all.length >= data.total || data.items.length === 0) return all
  }
}

export async function exportMemberScores(assessmentId: string, title: string): Promise<void> {
  const rows = buildScoreRows(await fetchAllScores(assessmentId))
  const sheet = XLSX.utils.json_to_sheet(rows)
  const book = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(book, sheet, '成绩')
  const stamp = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10).replaceAll('-', '')
  XLSX.writeFile(book, `${title}-成绩-${stamp}.xlsx`)
}
