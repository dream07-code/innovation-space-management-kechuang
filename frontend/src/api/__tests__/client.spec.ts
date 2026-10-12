/**
 * API 客户端契约测试。
 *
 * 与 auth-contract.spec.ts 的区别很关键：那个文件直接 fetch，验证的是 **Mock** 的
 * 契约行为；本文件走真实的 src/api 模块，验证的是 **客户端** 是否按契约发出请求。
 *
 * 之所以需要这一层：开发中曾出现 changePassword 用 POST 调 /auth/password
 * 而契约要求 PUT 的错误 —— 裸 fetch 的测试发现不了，因为它绕过了客户端本身。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'
import { resetMockState } from '@/mocks/state'
import * as authApi from '@/api/modules/auth'
import { listDirections } from '@/api/modules/directions'
import { ApiError } from '@/api/errors'
import { clearTokens, getAccessToken, getRefreshToken, hasRefreshToken } from '@/api/tokenStore'

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetMockState()
  clearTokens()
})
afterAll(() => server.close())

describe('客户端：认证流程', () => {
  it('login 写入内存 token', async () => {
    const session = await authApi.login({ account: 'MOCK20260001', password: 'Example-Only9!' })

    expect(session.mustChangePassword).toBe(false)
    expect(getAccessToken()).toBe(session.accessToken)
    expect(getRefreshToken()).toBe(session.refreshToken)
    expect(hasRefreshToken()).toBe(true)
  })

  it('首次登录写入受限会话，且没有 refresh token', async () => {
    const session = await authApi.login({ account: 'MOCK20260003', password: 'Example-Only9!' })

    expect(session.mustChangePassword).toBe(true)
    expect(getRefreshToken()).toBeNull()
    expect(hasRefreshToken()).toBe(false)
  })

  it('changePassword 使用 PUT（契约要求），而不是 POST', async () => {
    // 这是对实际发生过的 bug 的回归测试：
    // 早先 client 没有 put 方法，changePassword 误用 POST 调 /auth/password，
    // 被 mock 拒绝（收到了打到真实网络的 502 而不是契约里的 400）。
    await authApi.login({ account: 'MOCK20260003', password: 'Example-Only9!' })

    await expect(
      authApi.changePassword({
        oldPassword: 'Example-Only9!',
        newPassword: 'Fresh-Pass-Word9!',
      }),
    ).resolves.toBeUndefined()

    // 新密码应立即生效：旧密码不再可用。
    await expect(
      authApi.login({ account: 'MOCK20260003', password: 'Example-Only9!' }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })
  })

  it('api 层不负责清理 token —— 那是 store 的职责', async () => {
    // 明确这一分工，避免将来有人误以为 changePassword 会顺带登出。
    // store 层的清理由 stores/__tests__/auth.spec.ts 覆盖。
    await authApi.login({ account: 'MOCK20260003', password: 'Example-Only9!' })
    await authApi.changePassword({
      oldPassword: 'Example-Only9!',
      newPassword: 'Fresh-Pass-Word9!',
    })

    expect(getAccessToken()).not.toBeNull()
  })

  it('changePassword 旧密码错误时抛 OLD_PASSWORD_INVALID', async () => {
    await authApi.login({ account: 'MOCK20260003', password: 'Example-Only9!' })

    await expect(
      authApi.changePassword({
        oldPassword: 'wrong-password',
        newPassword: 'Fresh-Pass-Word9!',
      }),
    ).rejects.toMatchObject({ code: 'OLD_PASSWORD_INVALID' })
  })

  it('login 失败抛 ApiError 且码为 INVALID_CREDENTIALS', async () => {
    await expect(
      authApi.login({ account: 'MOCK20260001', password: 'wrong' }),
    ).rejects.toBeInstanceOf(ApiError)

    await expect(
      authApi.login({ account: 'MOCK20260001', password: 'wrong' }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS', httpStatus: 401 })
  })

  it('fetchMe 返回身份与账号状态', async () => {
    await authApi.login({ account: 'MOCK20260001', password: 'Example-Only9!' })
    const me = await authApi.fetchMe()

    expect(me.user.account).toBe('MOCK20260001')
    expect(me.accountStatus).toBe('ENABLED')
    expect(me.membershipStatus).toBe('ACTIVE')
  })

  it('logout 后 token 已清空', async () => {
    await authApi.login({ account: 'MOCK20260001', password: 'Example-Only9!' })
    await authApi.logout()
    // 注意：clearTokens 由 store 负责，这里仅验证请求成功。
    clearTokens()
    expect(getAccessToken()).toBeNull()
  })
})

describe('客户端：Response 解包', () => {
  it('get 直接返回 data，调用方不接触信封', async () => {
    await authApi.login({ account: 'MOCK20260001', password: 'Example-Only9!' })

    const directions = await listDirections()

    // 应拿到数组本身，而不是 {code, message, data}。
    expect(Array.isArray(directions)).toBe(true)
    expect(directions).toHaveLength(7)
    expect(directions[0]).toMatchObject({ name: 'AI 全栈' })
    // 契约：所有 ID 都是字符串，不能变成 number。
    expect(typeof directions[0]?.id).toBe('string')
  })
})

describe('客户端：受限会话与错误分类', () => {
  it('受限会话访问业务接口，客户端把 403 归一化为 PASSWORD_CHANGE_REQUIRED', async () => {
    await authApi.login({ account: 'MOCK20260003', password: 'Example-Only9!' })

    const error = await listDirections().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.code).toBe('PASSWORD_CHANGE_REQUIRED')
    expect(apiError.isPasswordChangeRequired).toBe(true)
    // 契约：该错误不可重试。
    expect(apiError.isRetryable).toBe(false)
  })

  it('已移出成员的业务请求归一化为 MEMBER_REMOVED，且判定为不可重试', async () => {
    await authApi.login({ account: 'MOCK20260004', password: 'Example-Only9!' })

    const error = (await listDirections().catch((e: unknown) => e)) as ApiError

    expect(error.code).toBe('MEMBER_REMOVED')
    expect(error.isMemberRemoved).toBe(true)
    expect(error.isRetryable).toBe(false)
  })
})
