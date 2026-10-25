import { describe, expect, it } from 'vitest'

import { backActionFor, isSubpagePath } from '@/layouts/backNavigation'

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
