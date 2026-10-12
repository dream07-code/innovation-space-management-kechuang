import { beforeEach, describe, expect, it, vi } from 'vitest'

import { get } from '@/api/client'
import {
  getAdminDashboard,
  getLeaderDashboard,
  getStudentDashboard,
} from '@/api/modules/dashboard'

vi.mock('@/api/client', () => ({ API_PREFIX: '/api/v1', get: vi.fn<(url: string, config?: unknown) => Promise<unknown>>() }))

const mockedGet = vi.mocked(get)

beforeEach(() => {
  mockedGet.mockReset()
  mockedGet.mockResolvedValue({} as never)
})

describe('dashboard API', () => {
  it('三个角色分别请求独立聚合接口和目标范围', async () => {
    await getAdminDashboard('30d')
    await getLeaderDashboard('7d')
    await getStudentDashboard('7d')

    expect(mockedGet).toHaveBeenNthCalledWith(1, '/api/v1/dashboard/admin', { params: { range: '30d' } })
    expect(mockedGet).toHaveBeenNthCalledWith(2, '/api/v1/dashboard/leader', { params: { range: '7d' } })
    expect(mockedGet).toHaveBeenNthCalledWith(3, '/api/v1/dashboard/student', { params: { range: '7d' } })
  })
})
