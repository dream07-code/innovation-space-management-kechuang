/**
 * 路由守卫。
 *
 * 重要前提：**守卫只是 UX**。
 * 契约 §12.1 与 API 文档 §3.1 都明确指出「后端强制执行权限和方向隔离，
 * 不能只依赖前端隐藏按钮」，受限改密会话的限制也「由后端执行，前端跳转不是安全控制」。
 * 因此这里的目标是让界面状态正确、少发无谓请求，而不是充当安全边界。
 *
 * 需要处理的会话情形：
 *   1. 未登录 → 去登录页，并记录来源以便登录后跳回；
 *   2. 受限改密会话 → 只能待在改密页（后端亦会对其他接口返回 403）；
 *   3. 账号被停用 / 成员已移出 → 阻断页；
 *   4. 角色不匹配 → 阻断页（不暴露页面是否存在）。
 */
import { createRouter, createWebHistory, type Router } from 'vue-router'

import { routes } from './routes'
import { registerPasswordChangeRequiredHandler, registerSessionExpiredHandler } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'

const router: Router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

/**
 * 安装认证相关的副作用钩子。
 *
 * 在 main.ts 里、pinia 就绪之后调用 —— 因为钩子内部要用 store，
 * 而 store 依赖已安装的 pinia 实例。
 */
export function installAuthHooks(): void {
  const auth = useAuthStore()
  const directions = useDirectionsStore()

  // access token 失效且刷新失败 → 清空状态回登录页。
  registerSessionExpiredHandler((error) => {
    const wasBlocked = error.isAccountDisabled || error.isMemberRemoved
    auth.handleSessionExpired(error)

    // 清掉与账号绑定的缓存，避免下一个账号看到上一个账号的数据。
    directions.reset()

    const target = wasBlocked ? { name: 'blocked' } : { name: 'login' }
    if (router.currentRoute.value.name !== target.name) {
      void router.replace(target)
    }
  })

  // 受限会话访问业务接口 → 切到改密页。
  registerPasswordChangeRequiredHandler(() => {
    auth.handlePasswordChangeRequired()
    if (router.currentRoute.value.name !== 'change-password') {
      void router.replace({ name: 'change-password' })
    }
  })
}

router.beforeEach((to) => {
  const auth = useAuthStore()
  const state = auth.sessionState

  // 1) 已阻断：只允许停在阻断页。
  if (state === 'blocked') {
    return to.name === 'blocked' ? true : { name: 'blocked' }
  }

  // 2) 公开页面：未登录可直接访问；已登录访问登录页则送回该角色的落地页。
  if (to.meta.public) {
    if (to.name === 'login' && state !== 'anonymous') {
      return state === 'restricted' ? { name: 'change-password' } : { name: auth.homeRouteName }
    }
    return true
  }

  // 3) 未登录：去登录页，并记住来源。
  if (state === 'anonymous') {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }

  // 4) 受限改密会话：只能待在改密页。后端同样会拒绝其他接口，这里只是不给出入口。
  if (state === 'restricted') {
    return to.meta.allowRestricted ? true : { name: 'change-password' }
  }

  // 5) 角色校验。不匹配时统一去阻断页，不透露目标页面是否存在。
  const allowed = to.meta.roles
  if (allowed && allowed.length > 0) {
    if (!auth.role || !allowed.includes(auth.role)) {
      return { name: 'blocked' }
    }
  }

  return true
})

router.afterEach((to) => {
  const base = '学院科创空间管理系统'
  document.title = to.meta.title ? `${to.meta.title} · ${base}` : base
})

export default router
