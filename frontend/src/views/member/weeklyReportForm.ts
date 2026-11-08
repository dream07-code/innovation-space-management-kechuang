/**
 * 周报表单的输入规整。
 *
 * 后端对周报有两条硬约束，前端必须先把住，否则只会收到一个笼统的
 * 「请求内容不合法」，学生无从知道错在哪：
 *   - weekStart 必须是周一（自然周口径）；
 *   - links 每条都要是 http/https 绝对地址，最多 10 条。
 */

/** 把 YYYY-MM-DD 解析为 UTC 零点，只做星期判断，不受本地时区影响。 */
function parseIsoDate(value: string): Date | null {
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim())
  if (!matched) return null
  const [, year, month, day] = matched
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)))
  // 2026-02-31 这类不存在的日期会被 Date 归一化成 3 月，需要回读校验。
  if (date.getUTCMonth() !== Number(month) - 1 || date.getUTCDate() !== Number(day)) return null
  return date
}

/** 是否为合法的周一开始日（YYYY-MM-DD 且是周一）。 */
export function isWeekStart(value: string): boolean {
  const date = parseIsoDate(value)
  return date !== null && date.getUTCDay() === 1
}

export interface LinkLines {
  /** 合法的链接，按输入顺序。 */
  links: string[]
  /** 不合法的行号，从 1 开始，用于提示用户改哪一行。 */
  invalidLines: number[]
  /** 行数超过后端上限（10 条）。 */
  tooMany: boolean
}

/** 解析「每行一个」的链接文本框。空行忽略，不做去重（顺序即用户意图）。 */
export function parseLinkLines(text: string): LinkLines {
  const links: string[] = []
  const invalidLines: number[] = []
  text.split('\n').forEach((raw, index) => {
    const line = raw.trim()
    if (!line) return
    if (isHttpUrl(line)) links.push(line)
    else invalidLines.push(index + 1)
  })
  return { links, invalidLines, tooMany: links.length > 10 }
}

/** 只接受 http/https 绝对地址；与后端 ReportServiceImpl 的校验保持一致。 */
export function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}
