import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'
import { resetMockState } from '@/mocks/state'

const BASE = 'http://localhost'

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetMockState()
})
afterAll(() => server.close())

async function login(account: string, password: string): Promise<string> {
  const response = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account, password }),
  })
  const body = await response.json()
  expect(response.status).toBe(200)
  return body.data.accessToken
}

async function get(path: string, token: string) {
  const response = await fetch(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  return { status: response.status, body: await response.json() }
}

describe('方向负责人排行资格', () => {
  it('负责人不进入榜单，也不返回个人排名', async () => {
    const token = await login('MOCK20260002', 'Example-Only9!')
    const [overall, direction, mine] = await Promise.all([
      get('/api/v1/rankings/overall?page=1&size=20', token),
      get('/api/v1/rankings/directions/1?page=1&size=20', token),
      get('/api/v1/rankings/me?period=ALL_TIME', token),
    ])

    expect(overall.status).toBe(200)
    expect(overall.body.data.items.every((item: { memberId: string }) => item.memberId !== '202')).toBe(true)
    expect(overall.body.data.total).toBe(overall.body.data.items.length)
    expect(direction.status).toBe(200)
    expect(direction.body.data.items.every((item: { memberId: string }) => item.memberId !== '202')).toBe(true)
    expect(mine.status).toBe(403)
    expect(mine.body.code).toBe('FORBIDDEN')
  })

  it('负责人保留积分与积分流水', async () => {
    const token = await login('MOCK20260002', 'Example-Only9!')
    const [points, ledger] = await Promise.all([
      get('/api/v1/points/me', token),
      get('/api/v1/points/ledger?page=1&size=20', token),
    ])

    expect(points.status).toBe(200)
    expect(points.body.data.totalPoints).toBeGreaterThan(0)
    expect(ledger.status).toBe(200)
    expect(ledger.body.data.total).toBeGreaterThan(0)
  })
})

describe('学生排行资格', () => {
  it('学生排名行为保持不变', async () => {
    const token = await login('MOCK20260001', 'Example-Only9!')
    const mine = await get('/api/v1/rankings/me?period=ALL_TIME', token)

    expect(mine.status).toBe(200)
    expect(mine.body.data.eligible).toBe(true)
    expect(mine.body.data.overallRank).not.toBeNull()
    expect(mine.body.data.directionRank).not.toBeNull()
  })
})
