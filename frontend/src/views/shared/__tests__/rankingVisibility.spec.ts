import { describe, expect, it } from 'vitest'

import { shouldShowPersonalRankings } from '@/views/shared/rankingVisibility'

describe('个人排名展示资格', () => {
  it('仅学生展示个人排名', () => {
    expect(shouldShowPersonalRankings('STUDENT')).toBe(true)
    expect(shouldShowPersonalRankings('DIRECTION_LEADER')).toBe(false)
    expect(shouldShowPersonalRankings('COLLEGE_ADMIN')).toBe(false)
    expect(shouldShowPersonalRankings(null)).toBe(false)
  })
})
