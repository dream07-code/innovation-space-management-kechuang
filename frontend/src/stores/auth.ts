/**
 * 认证状态。
 *
 * 契约要点（docs/api/README.md §3）：
 *   - token 只放内存，不落 localStorage；
 *   - 首次/重置后登录拿到受限会话（mustChangePassword=true，无 refresh token），
 *     该会话只能访问 me / password / logout；
 *   - 改密成功后全部会话失效且不返回新 token，必须重新登录；
 *   - 后端才是权限的执行者，前端状态只用于界面呈现与路由跳转（UX）。
 *
 * 关于响应式：凭据本身存放在 tokenStore 的模块级变量里（刻意不放进 pinia，
 * 避免被持久化插件意外落盘），那些变量不是响应式的。因此 store 内部维护一个
 * 响应式的 `authenticated` 标志来驱动 computed，凭据只从 tokenStore 读取。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '@/api/modules/auth'
import { ApiError } from '@/api/errors'
import { clearTokens, getAccessToken, hasRefreshToken as storeHasRefreshToken } from '@/api/tokenStore'
import type { Identity, Me, Role, Session } from '@/api/types'

/** 会话可用性状态。用于驱动路由守卫与阻断页。 */
export type SessionState =
  | 'anonymous' // 未登录
  | 'restricted' // 已登录但必须改密，仅可访问改密相关页面
  | 'active' // 正常可用
  | 'blocked' // 账号被停用或成员已移出，禁止继续使用

export type BlockedReason = 'ACCOUNT_DISABLED' | 'MEMBER_REMOVED'

export const useAuthStore = defineStore('auth', () => {
  // -- 状态 ------------------------------------------------------------------

  /** 是否有有效凭据。响应式，驱动下面所有 computed。 */
  const authenticated = ref(false)

  const user = ref<Identity | null>(null)
  const mustChangePassword = ref(false)
  const accountStatus = ref<Me['accountStatus']>('ENABLED')
  const membershipStatus = ref<Me['membershipStatus']>(null)
  const profile = ref<Me['profile'] | null>(null)

  /** 阻断原因，用于 /blocked 页面展示。 */
  const blockedReason = ref<BlockedReason | null>(null)

  /** 请求进行中标志，避免重复提交。 */
  const loading = ref(false)

  // -- 派生状态 --------------------------------------------------------------

  const sessionState = computed<SessionState>(() => {
    if (blockedReason.value !== null) return 'blocked'
    if (!authenticated.value || user.value === null) return 'anonymous'
    // mustChangePassword 优先于常规跳转：受限会话本就不该访问业务页面。
    if (mustChangePassword.value) return 'restricted'
    return 'active'
  })

  const isAuthenticated = computed(() => authenticated.value && user.value !== null)

  const role = computed<Role | null>(() => user.value?.role ?? null)

  const isAdmin = computed(() => role.value === 'COLLEGE_ADMIN')
  const isLeader = computed(() => role.value === 'DIRECTION_LEADER')
  const isStudent = computed(() => role.value === 'STUDENT')

  /**
   * 学院管理员账号没有成员身份（契约 §5：「管理账号无成员身份，不提交」），
   * 因此没有个人首页、个人积分与个人排名。
   */
  const hasMemberIdentity = computed(
    () => user.value?.memberId !== null && user.value?.memberId !== undefined,
  )

  /** 方向负责人可管理的方向；管理员的范围由后端判定，这里仅用于界面呈现。 */
  const managedDirectionIds = computed<string[]>(() => user.value?.managedDirectionIds ?? [])

  /** 学员与负责人都能提交本人报告；管理员不能。 */
  const canAuthorReports = computed(() => isStudent.value || isLeader.value)

  const displayName = computed(() => user.value?.displayName ?? '')

  /**
   * 当前角色的落地页路由名。
   *
   * 学院管理员没有成员身份，个人首页（dashboard）对它不存在 ——
   * 若把它也送到 `/`，守卫会因角色不符把它弹去阻断页，
   * 造成「管理员登录成功却看到访问受限」的假象。
   */
  const homeRouteName = computed<'dashboard' | 'admin-dashboard'>(() =>
    isAdmin.value ? 'admin-dashboard' : 'dashboard',
  )

  // -- 内部辅助 --------------------------------------------------------------

  /** 记录会话已建立，并同步凭据状态。 */
  function markAuthenticated(): void {
    authenticated.value = getAccessToken() !== null
  }

  /** 把一次会话响应落到 store 上。 */
  function applySession(session: Session): void {
    user.value = session.user
    mustChangePassword.value = session.mustChangePassword
    blockedReason.value = null
    markAuthenticated()
  }

  /** 由账号状态推导阻断原因。 */
  function deriveBlockedReason(me: Me): BlockedReason | null {
    if (me.accountStatus === 'DISABLED') return 'ACCOUNT_DISABLED'
    if (me.membershipStatus === 'REMOVED') return 'MEMBER_REMOVED'
    return null
  }

  /** 清空全部本地状态。 */
  function reset(): void {
    clearTokens()
    authenticated.value = false
    user.value = null
    mustChangePassword.value = false
    accountStatus.value = 'ENABLED'
    membershipStatus.value = null
    profile.value = null
    blockedReason.value = null
  }

  // -- 动作 ------------------------------------------------------------------

  /**
   * 登录。
   *
   * 返回会话，由调用方依据 mustChangePassword 决定去改密页还是首页。
   * 错误账号、错误密码、被停用账号统一抛 INVALID_CREDENTIALS ——
   * 调用方提示必须保持通用，不能区分账号是否存在。
   */
  async function login(account: string, password: string): Promise<Session> {
    loading.value = true
    try {
      const session = await authApi.login({ account, password })
      applySession(session)
      return session
    } finally {
      loading.value = false
    }
  }

  /**
   * 读取当前身份，用于重建状态。
   *
   * 注意：凭据只在内存中，页面刷新后必然丢失，因此正常情况下
   * 不会走到这里；保留该动作是为了将来接入 Cookie 会话时复用。
   */
  async function loadMe(): Promise<Me> {
    const me = await authApi.fetchMe()
    user.value = me.user
    mustChangePassword.value = me.mustChangePassword
    accountStatus.value = me.accountStatus
    membershipStatus.value = me.membershipStatus
    profile.value = me.profile
    blockedReason.value = deriveBlockedReason(me)
    markAuthenticated()
    return me
  }

  /**
   * 修改本人密码。
   *
   * 契约：成功后全部会话失效且不返回新 token，因此这里直接清空本地状态，
   * 调用方必须引导用户重新登录，不能继续使用当前会话。
   */
  async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
    loading.value = true
    try {
      await authApi.changePassword({ oldPassword, newPassword })
      reset()
    } finally {
      loading.value = false
    }
  }

  /**
   * 退出登录。
   *
   * 契约：重复用已失效 token 退出可返回 401，但本地状态仍须清空，
   * 因此无论请求成功与否都要 reset。
   */
  async function logout(): Promise<void> {
    try {
      if (getAccessToken()) {
        await authApi.logout()
      }
    } catch {
      // 忽略退出接口的错误：本地清理才是关键。
    } finally {
      reset()
    }
  }

  /**
   * 会话失效（刷新失败，或后端判定账号不可用）。
   *
   * 先 reset 清空一切，再按错误类型恢复阻断标记 ——
   * 阻断页需要知道原因，但它不属于「有效会话」。
   */
  function handleSessionExpired(error?: ApiError): void {
    const reason: BlockedReason | null = error?.isAccountDisabled
      ? 'ACCOUNT_DISABLED'
      : error?.isMemberRemoved
        ? 'MEMBER_REMOVED'
        : null

    reset()
    if (reason !== null) {
      blockedReason.value = reason
    }
  }

  /** 后端返回 PASSWORD_CHANGE_REQUIRED：切到受限模式。 */
  function handlePasswordChangeRequired(): void {
    mustChangePassword.value = true
  }

  /**
   * 是否持有 refresh token。
   *
   * 凭据在 tokenStore 里是非响应式的，所以这里依据契约的确定关系推导：
   * 受限改密会话不返回 refresh token，正常会话才有。
   */
  const hasRefreshToken = computed(
    () => authenticated.value && !mustChangePassword.value && storeHasRefreshToken(),
  )

  return {
    // 状态
    user,
    mustChangePassword,
    accountStatus,
    membershipStatus,
    profile,
    blockedReason,
    loading,
    // 派生
    sessionState,
    isAuthenticated,
    role,
    isAdmin,
    isLeader,
    isStudent,
    hasMemberIdentity,
    managedDirectionIds,
    canAuthorReports,
    displayName,
    homeRouteName,
    hasRefreshToken,
    // 动作
    login,
    loadMe,
    changePassword,
    logout,
    reset,
    handleSessionExpired,
    handlePasswordChangeRequired,
  }
})
