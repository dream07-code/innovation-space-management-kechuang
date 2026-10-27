import { describe, expect, it } from 'vitest'

import { backActionFor, isSubpagePath, reviewReturnAction } from '@/layouts/backNavigation'

describe('子页面返回导航', () => {
  it('有浏览器历史时返回上一页', () => {
    expect(backActionFor('/reports/daily/new', '/reports/daily', 'dashboard')).toEqual({
      kind: 'history',
    })
  })

  it('同一路径仅 query 不同不算缺失历史（结果页明细下钻返回）', () => {
    expect(
      backActionFor(
        '/oj/assessments/4/results?view=submission&member=3&memberName=x',
        '/oj/assessments/4/results',
        'dashboard',
      ),
    ).toEqual({ kind: 'history' })
  })

  it('无浏览器历史时回到当前角色的首页', () => {
    expect(backActionFor('/reports/daily/new', null, 'dashboard')).toEqual({
      kind: 'route',
      name: 'dashboard',
    })
  })

  it('只有角色首页不显示返回按钮', () => {
    expect(isSubpagePath('/', '/')).toBe(false)
    expect(isSubpagePath('/admin', '/admin')).toBe(false)
    expect(isSubpagePath('/admin/points', '/admin')).toBe(true)
    expect(isSubpagePath('/reports/daily/new', '/')).toBe(true)
  })
})

describe('人工评分完成后的返回动作', () => {
  it('上一条历史就是目标时用 back()，不 replace 造重复记录', () => {
    const target = '/oj/assessments/4/results?view=submission'
    expect(reviewReturnAction(target, target)).toBe('history')
  })

  it('深链接直达（无匹配历史）时 replace 兜底', () => {
    expect(
      reviewReturnAction('/oj/assessments/4/results?view=submission', null),
    ).toBe('replace')
    expect(
      reviewReturnAction('/oj/assessments/4/results?view=submission', '/oj/submissions/1'),
    ).toBe('replace')
  })
})
