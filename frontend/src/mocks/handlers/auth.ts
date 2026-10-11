/**
 * Mock：认证与会话。
 *
 * 严格实现契约 §3 的规则，使前端能在没有后端的情况下验证真实流程：
 *   - 受限会话只能访问 me / password / logout，其余接口 403 PASSWORD_CHANGE_REQUIRED；
 *   - refresh 轮换且旧 refresh token 立即失效；
 *   - 改密成功后撤销该用户全部会话，且响应不返回新 token；
 *   - 错误账号、错误密码、被停用账号统一 401 INVALID_CREDENTIALS。
 */
import { HttpResponse, http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import { errors, ok, okEmpty } from '../state'
import {
  bearerFrom,
  createSession,
  findAccountByAccount,
  findAccountByUserId,
  findSessionByAccess,
  findSessionByRefresh,
  revokeSessionsOfUser,
  rotateSession,
} from '../state'

/**
 * 注意：认证接口刻意**不**走 state.ts 的 guardSession。
 *
 * 原因是这三个接口对受限会话和被阻断的账号都必须可用：
 *   - /auth/me 要能返回 accountStatus 与 membershipStatus，
 *     前端才能据此切到阻断页；
 *   - /auth/password 与 /auth/logout 在被阻断时仍应可用。
 * 受限会话对**业务**接口的限制由 reference.ts 等模块经 guardSession 执行。
 */
export const authHandlers = [
  // -- 登录 ------------------------------------------------------------------
  http.post(`${API_PREFIX}/auth/login`, async ({ request }) => {
    const body = (await request.json()) as { account?: string; password?: string }
    const account = typeof body.account === 'string' ? body.account : ''
    const password = typeof body.password === 'string' ? body.password : ''

    const found = findAccountByAccount(account)

    // 契约要求：错误账号、错误密码、被停用账号统一返回同一错误，
    // 避免通过登录响应枚举学号。因此这里刻意不区分。
    if (!found || found.password !== password || found.accountStatus === 'DISABLED') {
      return errors.invalidCredentials()
    }

    return ok(createSession(found, found.mustChangePassword))
  }),

  // -- 刷新 ------------------------------------------------------------------
  http.post(`${API_PREFIX}/auth/refresh`, async ({ request }) => {
    const body = (await request.json()) as { refreshToken?: string }
    const refreshToken = typeof body.refreshToken === 'string' ? body.refreshToken : ''

    const session = findSessionByRefresh(refreshToken)
    if (!session) return errors.refreshInvalid()

    const account = findAccountByUserId(session.userId)
    if (!account) return errors.refreshInvalid()

    // 刷新重新读取账号状态，不从旧 token 复制过期权限。
    if (account.accountStatus === 'DISABLED') return errors.accountDisabled()
    if (account.membershipStatus === 'REMOVED') return errors.memberRemoved()

    // 轮换：旧 refresh token 立即失效。
    return ok(rotateSession(refreshToken, account))
  }),

  // -- 退出 ------------------------------------------------------------------
  http.post(`${API_PREFIX}/auth/logout`, ({ request }) => {
    const session = findSessionByAccess(bearerFrom(request))
    if (session) {
      // 撤销该用户当前会话及其刷新能力。
      revokeSessionsOfUser(session.userId)
    }
    // 契约：重复用已失效 token 退出可返回 401，但客户端仍应清空本地状态。
    return okEmpty()
  }),

  // -- 当前身份 --------------------------------------------------------------
  http.get(`${API_PREFIX}/auth/me`, ({ request }) => {
    const session = findSessionByAccess(bearerFrom(request))
    if (!session) return errors.unauthorized()

    const account = findAccountByUserId(session.userId)
    if (!account) return errors.unauthorized()

    return ok({
      user: account.user,
      mustChangePassword: account.mustChangePassword,
      accountStatus: account.accountStatus,
      membershipStatus: account.membershipStatus,
      profile: account.profile,
    })
  }),

  // -- 修改密码 --------------------------------------------------------------
  http.put(`${API_PREFIX}/auth/password`, async ({ request }) => {
    const session = findSessionByAccess(bearerFrom(request))
    if (!session) return errors.unauthorized()

    const account = findAccountByUserId(session.userId)
    if (!account) return errors.unauthorized()

    const body = (await request.json()) as { oldPassword?: string; newPassword?: string }
    const oldPassword = typeof body.oldPassword === 'string' ? body.oldPassword : ''
    const newPassword = typeof body.newPassword === 'string' ? body.newPassword : ''

    // 契约：旧密码错误返回 400 OLD_PASSWORD_INVALID，且受限状态保留。
    if (oldPassword !== account.password) {
      return HttpResponse.json(
        { code: 'OLD_PASSWORD_INVALID', message: '当前密码不正确', data: null },
        { status: 400 },
      )
    }

    // 契约的密码策略：12–64 字符、不能全空白、不能与旧密码相同。
    if (
      newPassword.length < 12 ||
      newPassword.length > 64 ||
      newPassword.trim().length === 0 ||
      newPassword === oldPassword
    ) {
      return HttpResponse.json(
        { code: 'PASSWORD_POLICY_VIOLATION', message: '新密码不符合要求', data: null },
        { status: 400 },
      )
    }

    account.password = newPassword
    account.mustChangePassword = false
    // 改密后撤销该用户全部会话；响应不返回新 token，客户端必须重新登录。
    revokeSessionsOfUser(account.user.userId)

    return okEmpty()
  }),
]
