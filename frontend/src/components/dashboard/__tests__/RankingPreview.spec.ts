import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import RankingPreview from '@/components/dashboard/RankingPreview.vue'
import type { RankingEntry } from '@/api/types'

describe('RankingPreview', () => {
  it('直接渲染服务端排行行，不在前端过滤成员', () => {
    const items: RankingEntry[] = [
      { memberId: '202', displayName: '示例负责人', directionId: '1', directionName: 'AI 全栈', points: 12, rank: 1 },
      { memberId: '201', displayName: '示例同学', directionId: '1', directionName: 'AI 全栈', points: 8, rank: 2 },
    ]
    const wrapper = mount(RankingPreview, { props: { items } })

    expect(wrapper.text()).toContain('示例负责人')
    expect(wrapper.text()).toContain('示例同学')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })
})
