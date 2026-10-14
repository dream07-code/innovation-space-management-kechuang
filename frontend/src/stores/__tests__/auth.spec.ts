/**
 * auth store 的行为测试。
 *
 * 与 api 层测试的分工：api 层只负责发请求与归一化错误；
 * 会话状态的流转（受限模式、改密后登出、被阻断）由 store 负责，因此在这里验证。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { server } from '@/mocks/server'
import { resetMockState } from '@/mocks/state'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/errors'
import { getAccessToken } from '@/api/tokenStore'

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))

afterEach(() => {
  server.resetHandlers()
  resetMockState()
})

afterAll(() => server.close())

/** 每个用例一个全新的 pinia，避免 store 状态跨用例泄漏。 */
function freshStore() {
  setActivePinia(createPinia())
  return useAuthStore()
}

describe('会话状态', () => {
  it('初始为 anonymous', () => {
    const auth = freshStore()
    expect(auth.sessionState).toBe('anonymous')
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.role).toBeNull()
  })

  it('普通账号登录后为 active，且角色计算正确', async () => {
    const auth = freshStore()
    await auth.login('MOCK20260001', 'Example-Only9!')

    expect(auth.sessionState).toBe('active')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.isStudent).toBe(true)
    expect(auth.isLeader).toBe(false)
    expect(auth.isAdmin).toBe(false)
    expect(auth.displayName).toBe('示例同学')
    // 学生有成员身份。
    expect(auth.hasMemberIdentity).toBe(true)
    expect(auth.canAuthorReports).toBe(true)
  })

  it('首次登录后为 restricted（必须改密）', async () => {
    const auth = freshStore()
    await auth.login('MOCK20260003', 'Example-Only9!')

    expect(auth.sessionState).toBe('restricted')
    expect(auth.mustChangePassword).toBe(true)
    // 受限会话没有 refresh token。
    expect(auth.hasRefreshToken).toBe(false)
  })

  it('学院管理员没有成员身份，因此不能提交报告', async () => {
    const auth = freshStore()
    await auth.login('MOCK-ADMIN', 'Admin-Example9!')

    expect(auth.isAdmin).toBe(true)
    // 契约 §5：管理账号无成员身份。
    expect(auth.hasMemberIdentity).toBe(false)
    expect(auth.canAuthorReports).toBe(false)
  })

  it('方向负责人既是成员又有管理范围', async () => {
    const auth = freshStore()
    await auth.login('MOCK20260002', 'Example-Only9!')

    expect(auth.isLeader).toBe(true)
    expect(auth.canAuthorReports).toBe(true)
    expect(auth.hasMemberIdentity).toBe(true)
    // managedDirectionIds 原样来自契约。
    expect(auth.managedDirectionIds).toEqual(['1', '2'])
  })
})

describe('改密后必须重新登录', () => {
  it('changePassword 成功后清空全部本地状态', async () => {
    const auth = freshStore()
    await auth.login('MOCK20260003', 'Example-Only9!')
    expect(getAccessToken()).not.toBeNull()

    await auth.changePassword('Example-Only9!', 'Fresh-Pass-Word9!')

    // 契约：改密成功后全部会话失效且不返回新 token，必须重新登录。
    expect(getAccessToken()).toBeNull()
    expect(auth.sessionState).toBe('anonymous')
    expect(auth.user).toBeNull()
    expect(auth.mustChangePassword).toBe(false)
  })

  it('旧密码错误时抛错，且保留受限状态（不能把用户困在登录页）', async () => {
    const auth = freshStore()
    await auth.login('MOCK20260003', 'Example-Only9!')

    await expect(auth.changePassword('wrong-old', 'Fresh-Pass-Word9!')).rejects.toBeInstanceOf(
      ApiError,
    )

    // 契约：旧密码错误时受限状态保留。
    expect(auth.sessionState).toBe('restricted')
    expect(getAccessToken()).not.toBeNull()
  })
})

describe('退出登录', () => {
  it('清空 token 与身份', async () => {
    const auth = freshStore()
    await auth.login('MOCK20260001', 'Example-Only9!')

    await auth.logout()

    expect(getAccessToken()).toBeNull()
    expect(auth.sessionState).toBe('anonymous')
    expect(auth.user).toBeNull()
  })

  it('即使服务端拒绝退出，本地状态也必须清空', async () => {
    const auth = freshStore()
    await auth.login('MOCK20260001', 'Example-Only9!')

    // 先手动让 token 失效，模拟「重复用已失效 token 退出」。
    const { clearTokens } = await import('@/api/tokenStore')
    clearTokens()

    await auth.logout()

    // 契约：重复用已失效 token 退出可返回 401，前端仍清空本地状态。
    expect(auth.sessionState).toBe('anonymous')
  })
})

describe('异常状态处理', () => {
  it('账号被停用时标记为 blocked', () => {
    const auth = freshStore()
    const error = new ApiError({ code: 'ACCOUNT_DISABLED', httpStatus: 403, message: 'disabled' })

    auth.handleSessionExpired(error)

    expect(auth.sessionState).toBe('blocked')
    expect(auth.blockedReason).toBe('ACCOUNT_DISABLED')
  })

  it('成员被移出时标记为 blocked', () => {
    const auth = freshStore()
    const error = new ApiError({ code: 'MEMBER_REMOVED', httpStatus: 403, message: 'removed' })

    auth.handleSessionExpired(error)

    expect(auth.sessionState).toBe('blocked')
    expect(auth.blockedReason).toBe('MEMBER_REMOVED')
  })

  it('普通 token 过期不算被阻断，只是回到未登录', () => {
    const auth = freshStore()
    const error = new ApiError({ code: 'TOKEN_EXPIRED', httpStatus: 401, message: 'expired' })

    auth.handleSessionExpired(error)

    expect(auth.sessionState).toBe('anonymous')
    expect(auth.blockedReason).toBeNull()
  })

  it('收到 PASSWORD_CHANGE_REQUIRED 时切到受限模式', () => {
    const auth = freshStore()
    auth.handlePasswordChangeRequired()

    expect(auth.mustChangePassword).toBe(true)
  })
})
