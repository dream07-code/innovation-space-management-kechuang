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

  it('修改时发送版本号、三个正文字段与图片列表', () => {
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
      images: [],
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


describe('日报图片', () => {
  const image = {
    url: 'https://kckj-1330648838.cos.ap-beijing.myqcloud.com/reports/2026/11/7/a.jpg',
    name: '笔记.jpg',
    size: 2048,
    anchor: 'end' as const,
  }

  it('新建草稿时带上图片', () => {
    const payload = buildCreateDailyReportPayload({
      learningContent: '今天整理了笔记',
      gains: '',
      problems: '',
      images: [image],
    })
    expect(payload.images).toEqual([image])
  })

  it('没有图片时不发送 images 字段', () => {
    const payload = buildCreateDailyReportPayload({
      learningContent: '纯文字日报',
      gains: '',
      problems: '',
      images: [],
    })
    expect(Object.keys(payload)).not.toContain('images')
  })
})
