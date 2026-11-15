import { describe, expect, it } from 'vitest'

import { TOP100_CATEGORIES, TOP100_PROBLEMS } from '@/data/leetcodeTop100'

describe('力扣 Top 100 题单数据', () => {
  it('正好 100 道，序号连续且标题不重复', () => {
    expect(TOP100_PROBLEMS).toHaveLength(100)
    expect(TOP100_PROBLEMS.map((item) => item.no)).toEqual(
      Array.from({ length: 100 }, (_, index) => index + 1),
    )
    expect(new Set(TOP100_PROBLEMS.map((item) => item.title)).size).toBe(100)
  })

  it('每道题都有难度、分类与 AI 预填用的知识点/题型', () => {
    for (const item of TOP100_PROBLEMS) {
      expect(['EASY', 'MEDIUM', 'HARD']).toContain(item.difficulty)
      expect(item.category.length).toBeGreaterThan(0)
      expect(item.topic.length).toBeGreaterThan(0)
      expect(item.pattern.length).toBeGreaterThan(0)
    }
    expect(TOP100_CATEGORIES.length).toBeGreaterThan(5)
    expect(new Set(TOP100_CATEGORIES).size).toBe(TOP100_CATEGORIES.length)
  })

  it('已收录的题都指向题库 id，且共 26 道（题库 30 条里有 4 条两数之和副本）', () => {
    const collected = TOP100_PROBLEMS.filter((item) => item.problemId)
    expect(collected).toHaveLength(26)
    for (const item of collected) {
      expect(item.problemId).toMatch(/^\d+$/)
    }
    // 同一道题库题目不能被题单重复挂两次。
    expect(new Set(collected.map((item) => item.problemId)).size).toBe(26)
  })
})
