import { beforeEach, describe, expect, it, vi } from 'vitest'

import { get } from '@/api/client'

vi.mock('@/api/client', () => ({
  API_PREFIX: '/api/v1',
  get: vi.fn<(url: string, config?: unknown) => Promise<unknown>>(),
  patch: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
  post: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
}))

const mockedGet = vi.mocked(get)

beforeEach(() => {
  mockedGet.mockReset()
  mockedGet.mockResolvedValue({} as never)
})

describe('OJ 题目测试点 API', () => {
  it('使用幂等键提交批量导入', async () => {
    const api = await import('@/api/modules/oj')
    await api.importAssessmentProblems({ problems: [] }, 'import-key')

    expect(vi.mocked((await import('@/api/client')).post)).toHaveBeenCalledWith(
      '/api/v1/assessment-problems/import',
      { problems: [] },
      { idempotencyKey: 'import-key' },
    )
  })

  it('以 staff 专用路径读取两套测试点', async () => {
    const api = await import('@/api/modules/oj')

    expect(api.getAssessmentProblemTestCases).toBeTypeOf('function')
    await api.getAssessmentProblemTestCases('9101')

    expect(mockedGet).toHaveBeenCalledWith('/api/v1/assessment-problems/9101/test-cases')
  })
})
