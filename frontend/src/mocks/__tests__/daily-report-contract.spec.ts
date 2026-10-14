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

async function login(): Promise<string> {
  const response = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account: 'MOCK20260002', password: 'Example-Only9!' }),
  })
  const body = await response.json()
  expect(response.status).toBe(200)
  return body.data.accessToken
}

describe('日报新表单契约', () => {
  it('创建时不接收日期，由Mock按当天生成', async () => {
    const token = await login()
    const response = await fetch(`${BASE}/api/v1/daily-reports`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': 'daily-create-payload-test',
      },
      body: JSON.stringify({ learningContent: '今天学习了 Java 集合' }),
    })
    const body = await response.json()
    const today = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10)

    expect(response.status).toBe(201)
    expect(body.data.reportDate).toBe(today)
    expect(body.data.learningContent).toBe('今天学习了 Java 集合')
    expect(body.data.gains).toBe('')
    expect(body.data.problems).toBeNull()
  })

  it('修改时只接收版本号和三个正文字段', async () => {
    const token = await login()
    const create = await fetch(`${BASE}/api/v1/daily-reports`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': 'daily-update-payload-create',
      },
      body: JSON.stringify({ learningContent: '原始内容', gains: '原始收获' }),
    })
    const created = await create.json()

    const update = await fetch(`${BASE}/api/v1/daily-reports/${created.data.id}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        version: created.data.version,
        learningContent: '更新后的学习内容',
        gains: '可选收获',
        problems: '可选问题',
      }),
    })
    const updated = await update.json()

    expect(update.status).toBe(200)
    expect(updated.data.learningContent).toBe('更新后的学习内容')
    expect(updated.data.gains).toBe('可选收获')
    expect(updated.data.problems).toBe('可选问题')
    expect(updated.data.reportDate).toBe(created.data.reportDate)
  })
})
