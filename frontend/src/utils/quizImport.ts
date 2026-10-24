/**
 * 客观题 Excel 批量导入：解析与模板。
 *
 * 与学生导入同款体验：模板下载、逐行校验、坏行不拖累好行。
 * 列契约见 docs/superpowers/specs/2026-10-24-mixed-exam-quiz-design.md §7，
 * 行内校验与后端 QuizQuestionService 一致（选项 2–6、字母界内、备选非空）。
 */
import * as XLSX from 'xlsx'

import type { CreateQuizQuestionRequest } from '@/api/types'

export const QUIZ_IMPORT_HEADERS = [
  '题型', '题干', '选项A', '选项B', '选项C', '选项D', '选项E', '选项F', '答案', '解析', '标签',
] as const

export interface QuizImportRow {
  /** Excel 中的物理行号（从 1 计，含表头）。 */
  line: number
  payload: CreateQuizQuestionRequest | null
  error: string | null
}

const TYPE_BY_LABEL: Record<string, CreateQuizQuestionRequest['type']> = {
  单选: 'SINGLE_CHOICE',
  多选: 'MULTI_CHOICE',
  判断: 'TRUE_FALSE',
  填空: 'FILL_BLANK',
}

const OPTION_LETTERS = ['A', 'B', 'C', 'D', 'E', 'F'] as const

/** 模板内容：仅表头行 + 一行示例说明（空白数据行便于直接粘贴）。 */
export function buildQuizTemplateAoA(): string[][] {
  return [[...QUIZ_IMPORT_HEADERS]]
}

export function downloadQuizImportTemplate(): void {
  const sheet = XLSX.utils.aoa_to_sheet(buildQuizTemplateAoA())
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, sheet, '客观题')
  XLSX.writeFile(workbook, '客观题批量导入模板.xlsx')
}

/** 读取 .xlsx 并解析为行；文件级失败（非表格/无工作表）抛出异常由调用方提示。 */
export async function readQuizImportFile(file: File): Promise<QuizImportRow[]> {
  const buf = await file.arrayBuffer()
  const workbook = XLSX.read(buf, { type: 'array' })
  const sheet = workbook.Sheets[workbook.SheetNames[0] ?? '']
  if (!sheet) throw new Error('NO_SHEET')
  // raw:false 统一按字符串取值，避免答案字母被当作数字。
  const matrix = XLSX.utils.sheet_to_json<unknown[]>(sheet, { header: 1, raw: false, defval: '' })
  return parseQuizImportRows(matrix)
}

export function parseQuizImportRows(matrix: unknown[][]): QuizImportRow[] {
  if (matrix.length === 0) return []
  // 首行含「题型」视为表头跳过，兼容无表头直贴数据。
  const first = (matrix[0] ?? []).map((c) => String(c ?? '').trim())
  const startIndex = first.includes('题型') ? 1 : 0

  const rows: QuizImportRow[] = []
  for (let i = startIndex; i < matrix.length; i++) {
    const cells = (matrix[i] ?? []).map((c) => String(c ?? '').trim())
    if (!cells.some((c) => c !== '')) continue // Excel 末尾空行
    rows.push(parseRow(i + 1, cells))
  }
  return rows
}

function parseRow(line: number, cells: string[]): QuizImportRow {
  const typeLabel = cells[0] ?? ''
  const stem = cells[1] ?? ''
  const options = cells.slice(2, 8).filter((c) => c !== '')
  const answer = cells[8] ?? ''
  const analysis = cells[9] ?? ''
  const tags = (cells[10] ?? '')
    .split(/[、,，]/)
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0)

  const type = TYPE_BY_LABEL[typeLabel]
  if (!type) return { line, payload: null, error: `未知题型「${typeLabel}」，应为 单选/多选/判断/填空` }
  if (stem === '') return { line, payload: null, error: '题干为空' }

  if (type === 'TRUE_FALSE') {
    if (answer !== '对' && answer !== '错') {
      return { line, payload: null, error: '判断题答案须填 对 或 错' }
    }
    return { line, payload: buildPayload(type, stem, null, answer === '对', analysis, tags), error: null }
  }

  if (type === 'FILL_BLANK') {
    const alternatives = answer.split('|').map((a) => a.trim()).filter((a) => a.length > 0)
    if (alternatives.length === 0) return { line, payload: null, error: '填空题答案为空' }
    return { line, payload: buildPayload(type, stem, null, alternatives, analysis, tags), error: null }
  }

  // 单选/多选共用选项校验
  if (options.length < 2) return { line, payload: null, error: '选择题至少需要 2 个非空选项' }
  const bound = options.length
  if (type === 'SINGLE_CHOICE') {
    if (!/^[A-F]$/.test(answer) || OPTION_LETTERS.indexOf(answer as (typeof OPTION_LETTERS)[number]) >= bound) {
      return { line, payload: null, error: `单选答案须为 A–${OPTION_LETTERS[bound - 1]} 中的单个字母` }
    }
    return { line, payload: buildPayload(type, stem, options, answer, analysis, tags), error: null }
  }

  if (!/^[A-F]+$/.test(answer)) return { line, payload: null, error: '多选答案须为 A–F 的字母串，如 ABD' }
  const letters = [...new Set(answer.split(''))].sort()
  if (letters.length !== answer.length) return { line, payload: null, error: '多选答案含重复字母' }
  if (letters.some((l) => OPTION_LETTERS.indexOf(l as (typeof OPTION_LETTERS)[number]) >= bound)) {
    return { line, payload: null, error: `多选答案超出选项范围 A–${OPTION_LETTERS[bound - 1]}` }
  }
  return { line, payload: buildPayload(type, stem, options, letters, analysis, tags), error: null }
}

function buildPayload(
  type: CreateQuizQuestionRequest['type'],
  stem: string,
  options: string[] | null,
  answer: string | string[] | boolean,
  analysis: string,
  tags: string[],
): CreateQuizQuestionRequest {
  return {
    type,
    stem,
    options,
    answer,
    analysis: analysis === '' ? null : analysis,
    tags,
  }
}
