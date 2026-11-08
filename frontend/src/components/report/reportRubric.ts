/**
 * 报告评分的口径文案（v2）。
 *
 * 与后端 ScoringRubric v2 的档位保持一致：规则升级时两处一起改。
 * 详情抽屉与批阅页共用同一份，避免同一档位在不同页面说两套话。
 */
export type ReportKind = 'DAILY' | 'WEEKLY'

export interface RubricLevels {
  three: string
  two: string
  one: string
}

export const REPORT_RUBRIC: Record<ReportKind, RubricLevels> = {
  DAILY: {
    three: '写清具体做了什么（任务/知识点/方法），有可检验的收获，并对问题或下一步有反思。',
    two: '学习内容具体，但收获笼统，或问题/反思缺失。',
    one: '只有「学习了 X」这类笼统描述，或内容与学习无关、明显敷衍。',
  },
  WEEKLY: {
    three: '覆盖本周主要学习，列出可检验的成果，并有复盘（困难与改进）和下周计划。',
    two: '有本周总结和成果，但复盘或下周计划缺失、空泛。',
    one: '只有笼统总结、重复日报内容，或没有可检验的成果。',
  },
}

/** 评分选项，按界面上的 1 / 2 / 3 顺序。 */
export const RUBRIC_OPTIONS: Array<{ score: 1 | 2 | 3; label: string }> = [
  { score: 1, label: '1 分' },
  { score: 2, label: '2 分' },
  { score: 3, label: '3 分' },
]

export function rubricOf(kind: ReportKind): RubricLevels {
  return REPORT_RUBRIC[kind]
}
