/**
 * 认证流程的契约一致性测试。
 *
 * 这些测试直接打 Mock 的 HTTP 层，验证的是**契约行为**而不是组件渲染，
 * 目的有两个：
 *   1. 证明 Mock 忠实实现了契约 §3 的规则（否则前端会在错误的模拟上开发）；
 *   2. 为将来切换到真实后端时提供一份可复用的验收清单。
 *
 * 覆盖契约中容易做错的几条：
 *   - 首次登录返回受限会话（无 refresh token、expiresIn 300）；
 *   - 受限 token 访问业务接口得到 403 PASSWORD_CHANGE_REQUIRED；
 *   - refresh 轮换后旧 token 立即失效；
 *   - 改密成功后撤销全部会话且不返回新 token；
 *   - 错误账号、错误密码、被停用账号统一返回 INVALID_CREDENTIALS；
 *   - 被移出成员访问业务接口得到 403 MEMBER_REMOVED。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'
import { ACCOUNTS, resetMockState } from '@/mocks/state'

const BASE = 'http://localhost'

// MSW 3 中该选项名为 onUnhandledFrame（旧版的 onUnhandledRequest 已移除）。
// 测试里设为 'error'：任何未被 handler 覆盖的请求都应让测试失败，
// 避免契约行为在未被拦截的情况下被误判为通过。
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetMockState()
})
afterAll(() => server.close())

/** 登录并返回解包后的 Session。 */
async function login(account: string, password: string) {
  const response = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account, password }),
  })
  return { status: response.status, body: await response.json() }
}

/** 带 Bearer token 发起请求。 */
async function withToken(path: string, token: string, init: RequestInit = {}) {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...init.headers,
    },
  })
  return { status: response.status, body: await response.json() }
}

describe('登录', () => {
  it('普通账号登录返回正常会话（900 秒、有 refresh token）', async () => {
    const { status, body } = await login('MOCK20260001', 'Example-Only9!')

    expect(status).toBe(200)
    expect(body.code).toBe('OK')
    expect(body.data.mustChangePassword).toBe(false)
    expect(body.data.expiresIn).toBe(900)
    expect(body.data.refreshToken).toBeTruthy()
    expect(body.data.refreshExpiresIn).toBe(604800)
    expect(body.data.tokenType).toBe('Bearer')
  })

  it('首次登录返回受限会话：无 refresh token、300 秒、mustChangePassword', async () => {
    const { status, body } = await login('MOCK20260003', 'Example-Only9!')

    // 契约：HTTP 200 且 code=OK，但会话是受限的。
    expect(status).toBe(200)
    expect(body.code).toBe('OK')
    expect(body.data.mustChangePassword).toBe(true)
    expect(body.data.refreshToken).toBeNull()
    expect(body.data.refreshExpiresIn).toBeNull()
    expect(body.data.expiresIn).toBe(300)
  })

  it('错误账号、错误密码、被停用账号返回同一个错误码', async () => {
    const wrongAccount = await login('NOT-EXIST', 'whatever')
    const wrongPassword = await login('MOCK20260001', 'wrong-password')
    const disabled = await login('MOCK20260005', 'Example-Only9!')

    // 契约要求三者统一，避免通过登录响应枚举学号。
    for (const result of [wrongAccount, wrongPassword, disabled]) {
      expect(result.status).toBe(401)
      expect(result.body.code).toBe('INVALID_CREDENTIALS')
      expect(result.body.data).toBeNull()
    }
  })
})

describe('受限改密会话', () => {
  it('只能访问 me / password / logout', async () => {
    const { body } = await login('MOCK20260003', 'Example-Only9!')
    const token = body.data.accessToken

    // 白名单内的接口可用。
    const me = await withToken('/api/v1/auth/me', token)
    expect(me.status).toBe(200)
    expect(me.body.data.mustChangePassword).toBe(true)

    // 白名单外的业务接口一律 403 PASSWORD_CHANGE_REQUIRED。
    for (const path of ['/api/v1/directions', '/api/v1/points/ledger']) {
      const denied = await withToken(path, token)
      expect(denied.status).toBe(403)
      expect(denied.body.code).toBe('PASSWORD_CHANGE_REQUIRED')
    }
  })
})

describe('刷新令牌', () => {
  it('轮换后旧 refresh token 立即失效', async () => {
    const login1 = await login('MOCK20260001', 'Example-Only9!')
    const firstRefresh = login1.body.data.refreshToken

    const refreshed = await fetch(`${BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: firstRefresh }),
    })
    const refreshedBody = await refreshed.json()

    expect(refreshed.status).toBe(200)
    const secondRefresh = refreshedBody.data.refreshToken
    expect(secondRefresh).not.toBe(firstRefresh)

    // 旧 refresh token 必须已失效。
    const reuse = await fetch(`${BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: firstRefresh }),
    })
    expect(reuse.status).toBe(401)
    expect((await reuse.json()).code).toBe('REFRESH_TOKEN_INVALID')
  })
})

describe('修改密码', () => {
  it('成功后撤销全部会话，且不返回新 token', async () => {
    const { body } = await login('MOCK20260003', 'Example-Only9!')
    const token = body.data.accessToken

    const changed = await withToken('/api/v1/auth/password', token, {
      method: 'PUT',
      body: JSON.stringify({
        oldPassword: 'Example-Only9!',
        newPassword: 'Brand-New-Pass9!',
      }),
    })

    expect(changed.status).toBe(200)
    // 契约：data 为 null，不返回新 token。
    expect(changed.body.data).toBeNull()
    expect(changed.body.accessToken).toBeUndefined()

    // 全部会话已失效：原 token 不能再访问 /auth/me。
    const reused = await withToken('/api/v1/auth/me', token)
    expect(reused.status).toBe(401)

    // 旧密码不再可用，新密码可以登录。
    expect((await login('MOCK20260003', 'Example-Only9!')).status).toBe(401)
    const relogin = await login('MOCK20260003', 'Brand-New-Pass9!')
    expect(relogin.status).toBe(200)
    expect(relogin.body.data.mustChangePassword).toBe(false)
  })

  it('旧密码错误返回 400 OLD_PASSWORD_INVALID', async () => {
    const { body } = await login('MOCK20260003', 'Example-Only9!')

    const changed = await withToken('/api/v1/auth/password', body.data.accessToken, {
      method: 'PUT',
      body: JSON.stringify({
        oldPassword: 'not-the-current-one',
        newPassword: 'Brand-New-Pass9!',
      }),
    })

    expect(changed.status).toBe(400)
    expect(changed.body.code).toBe('OLD_PASSWORD_INVALID')
  })

  it('新密码不符合策略时返回 400 PASSWORD_POLICY_VIOLATION', async () => {
    const { body } = await login('MOCK20260003', 'Example-Only9!')

    // 少于 12 个字符。
    const tooShort = await withToken('/api/v1/auth/password', body.data.accessToken, {
      method: 'PUT',
      body: JSON.stringify({ oldPassword: 'Example-Only9!', newPassword: 'short' }),
    })
    expect(tooShort.status).toBe(400)
    expect(tooShort.body.code).toBe('PASSWORD_POLICY_VIOLATION')
  })
})

describe('成员状态', () => {
  it('已移出成员访问业务接口得到 403 MEMBER_REMOVED', async () => {
    const { status, body } = await login('MOCK20260004', 'Example-Only9!')
    expect(status).toBe(200)

    const denied = await withToken('/api/v1/directions', body.data.accessToken)
    expect(denied.status).toBe(403)
    expect(denied.body.code).toBe('MEMBER_REMOVED')
  })
})

describe('退出登录', () => {
  it('撤销会话后原 token 无法继续使用', async () => {
    const { body } = await login('MOCK20260001', 'Example-Only9!')
    const token = body.data.accessToken

    const loggedOut = await withToken('/api/v1/auth/logout', token, { method: 'POST' })
    expect(loggedOut.status).toBe(200)

    const reused = await withToken('/api/v1/auth/me', token)
    expect(reused.status).toBe(401)
  })
})

describe('账号列表一致性', () => {
  it('Mock 账号均为虚构数据，且不包含真实学生信息', () => {
    // 所有账号都带 MOCK 前缀，密码是明显的示例值。
    for (const account of ACCOUNTS) {
      expect(account.account).toMatch(/MOCK/)
    }
  })
})
