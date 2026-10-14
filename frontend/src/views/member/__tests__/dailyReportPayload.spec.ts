import { describe, expect, it } from 'vitest'

import {
  buildCreateDailyReportPayload,
  buildUpdateDailyReportPayload,
  validateDailyReportForm,
} from '@/views/member/dailyReportPayload'

describe('日报表单载荷', () => {
  it('创建时trim正文，只发送学习内容、收获和问题', () => {
    const payload = buildCreateDailyReportPayload({
      learningContent: '  今天学习 Java  ',
      gains: '  理解集合  ',
      problems: '  事务边界  ',
    })

    expect(payload).toEqual({
      learningContent: '今天学习 Java',
      gains: '理解集合',
      problems: '事务边界',
    })
    expect(Object.keys(payload)).not.toContain('reportDate')
    expect(Object.keys(payload)).not.toContain('nextPlan')
    expect(Object.keys(payload)).not.toContain('links')
  })

  it('选填字段为空时省略，不发送空字符串或null', () => {
    const payload = buildCreateDailyReportPayload({
      learningContent: '  Java 集合  ',
      gains: '   ',
      problems: '',
    })

    expect(payload).toEqual({ learningContent: 'Java 集合' })
  })

  it('修改时只发送版本号和三个正文字段', () => {
    const payload = buildUpdateDailyReportPayload(3, {
      learningContent: ' 更新后的学习内容 ',
      gains: '可选收获',
      problems: '可选问题',
    })

    expect(payload).toEqual({
      version: 3,
      learningContent: '更新后的学习内容',
      gains: '可选收获',
      problems: '可选问题',
    })
    expect(Object.keys(payload)).not.toContain('reportDate')
    expect(Object.keys(payload)).not.toContain('nextPlan')
    expect(Object.keys(payload)).not.toContain('links')
  })

  it('学习内容必填并执行最大长度校验', () => {
    expect(validateDailyReportForm({ learningContent: '   ', gains: '', problems: '' }).learningContent).toBeTruthy()
    expect(validateDailyReportForm({ learningContent: 'x'.repeat(10001), gains: '', problems: '' }).learningContent).toBeTruthy()
    expect(validateDailyReportForm({ learningContent: 'ok', gains: 'x'.repeat(10001), problems: '' }).gains).toBeTruthy()
    expect(validateDailyReportForm({ learningContent: 'ok', gains: '', problems: 'x'.repeat(5001) }).problems).toBeTruthy()
    expect(validateDailyReportForm({ learningContent: 'ok', gains: '', problems: '' })).toEqual({})
  })
})
