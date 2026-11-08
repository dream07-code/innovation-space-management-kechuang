import { describe, expect, it } from 'vitest'

import { isHttpUrl, isWeekStart, parseLinkLines } from '../weeklyReportForm'

describe('isWeekStart', () => {
  it('只接受周一', () => {
    expect(isWeekStart('2026-11-02')).toBe(true)
    expect(isWeekStart('2026-11-09')).toBe(true)
  })

  it('周日与工作日都不算周开始', () => {
    expect(isWeekStart('2026-11-01')).toBe(false) // 周日
    expect(isWeekStart('2026-11-08')).toBe(false) // 周日（今天）
    expect(isWeekStart('2026-11-05')).toBe(false) // 周四
  })

  it('拒绝格式错误与不存在的日期', () => {
    expect(isWeekStart('')).toBe(false)
    expect(isWeekStart('2026/11/02')).toBe(false)
    expect(isWeekStart('2026-11-02T00:00:00Z')).toBe(false)
    expect(isWeekStart('2026-02-31')).toBe(false)
  })
})

describe('parseLinkLines', () => {
  it('逐行解析并忽略空行', () => {
    const result = parseLinkLines('https://a.com\n\n  http://b.com/x  \n')
    expect(result.links).toEqual(['https://a.com', 'http://b.com/x'])
    expect(result.invalidLines).toEqual([])
    expect(result.tooMany).toBe(false)
  })

  it('指出不合法的行号（从 1 开始，空行也计入行号）', () => {
    const result = parseLinkLines('https://a.com\n请问\n\nftp://x.com')
    expect(result.links).toEqual(['https://a.com'])
    expect(result.invalidLines).toEqual([2, 4])
  })

  it('超过 10 条时标记 tooMany', () => {
    const text = Array.from({ length: 11 }, (_, i) => `https://a.com/${i}`).join('\n')
    const result = parseLinkLines(text)
    expect(result.links).toHaveLength(11)
    expect(result.tooMany).toBe(true)
  })
})

describe('isHttpUrl', () => {
  it('只认 http/https 绝对地址', () => {
    expect(isHttpUrl('https://docs.qq.com/a')).toBe(true)
    expect(isHttpUrl('http://127.0.0.1:8080/x')).toBe(true)
    expect(isHttpUrl('请问')).toBe(false)
    expect(isHttpUrl('javascript:alert(1)')).toBe(false)
    expect(isHttpUrl('//a.com')).toBe(false)
    expect(isHttpUrl('a.com')).toBe(false)
  })
})
