/**
 * 客观题 UI 状态映射。
 *
 * 只负责展示契约枚举（题型/题库状态/答案预览），不改变判分语义；
 * 判分规则见后端 QuizGrader。
 */
import type { QuizQuestion, QuizQuestionType } from '@/api/types'

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export const QUIZ_TYPE_META: Record<QuizQuestionType, { label: string; type: TagType }> = {
  SINGLE_CHOICE: { label: '单选', type: 'primary' },
  MULTI_CHOICE: { label: '多选', type: 'warning' },
  TRUE_FALSE: { label: '判断', type: 'success' },
  FILL_BLANK: { label: '填空', type: 'info' },
}

export const QUIZ_STATUS_META: Record<QuizQuestion['status'], { label: string; type: TagType }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PUBLISHED: { label: '已发布', type: 'success' },
  RETIRED: { label: '已停用', type: 'danger' },
}

/** 选项下标对应的字母（0→A）。 */
export function optionLetter(index: number): string {
  return String.fromCharCode('A'.charCodeAt(0) + index)
}

/** 题库列表“答案”一列的短预览；学生端永远拿不到这些数据。 */
export function quizAnswerPreview(question: Pick<QuizQuestion, 'type' | 'answer'>): string {
  switch (question.type) {
    case 'SINGLE_CHOICE':
      return `答案 ${question.answer}`
    case 'MULTI_CHOICE':
      return `答案 ${(question.answer as string[]).join('')}`
    case 'TRUE_FALSE':
      return question.answer ? '答案 对' : '答案 错'
    case 'FILL_BLANK': {
      const alternatives = question.answer as string[]
      return alternatives.length > 1
        ? `${alternatives[0]} 等 ${alternatives.length} 个备选`
        : alternatives[0] ?? ''
    }
    default:
      return ''
  }
}
