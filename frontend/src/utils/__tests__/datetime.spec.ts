import { describe, expect, it } from 'vitest'

import { formatDateTime } from '@/utils/datetime'

describe('formatDateTime', () => {
  it('把后端带微秒的 ISO 串格式化为到分钟', () => {
    expect(formatDateTime('2026-10-21T16:23:45.616641+08:00')).toMatch(
      /^\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}$/,
    )
  })

  it('空值显示占位符', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime(undefined)).toBe('—')
  })

  it('无法解析的值原样返回', () => {
    expect(formatDateTime('not-a-date')).toBe('not-a-date')
  })
})
