/**
 * 应用入口。
 *
 * 启动顺序有讲究：
 *   1. 先启动 MSW（若启用），确保后续任何请求都被拦截；
 *   2. 再挂载应用，避免首屏请求漏过 mock；
 *   3. 路由钩子（registerSessionExpiredHandler 等）在 pinia 就绪后安装。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import 'element-plus/dist/index.css'
import './assets/main.css'

import App from './App.vue'
import router, { installAuthHooks } from './router'

async function bootstrap(): Promise<void> {
  // 开发环境下按需启动 Mock。必须在挂载前完成，否则首屏请求会打到真实后端。
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK !== 'false') {
    const { worker } = await import('./mocks/browser')
    await worker.start({
      // 未覆盖的请求交给网络；契约中尚未实现的接口在开发环境会真实失败，
      // 这是可见的、预期的行为，比静默吞掉更利于发现问题。
      // 注意：MSW 3 中该选项名为 onUnhandledFrame（旧版的 onUnhandledRequest 已移除）。
      onUnhandledFrame: 'bypass',
      quiet: false,
    })
  } else if (import.meta.env.DEV) {
    // 关闭 mock 时注销可能残留的 MSW service worker：它注册在源上且不会自行
    // 退出，残留实例会继续拦截 /api 请求，导致真实后端永远收不到登录。
    void navigator.serviceWorker
      ?.getRegistration('/mockServiceWorker.js')
      .then((registration) => registration?.unregister())
      .catch(() => undefined)
  }

  const app = createApp(App)

  const pinia = createPinia()
  app.use(pinia)

  // 刷新页面后用 sessionStorage 里的 refreshToken 静默恢复会话。
  // 必须在安装 router 之前完成：vue-router 安装即开始首次导航，
  // 守卫若在恢复完成前读会话状态，会把已登录用户重定向到登录页。
  const { useAuthStore } = await import('./stores/auth')
  await useAuthStore(pinia).restoreSession()

  app.use(ElementPlus, { locale: zhCn })
  app.use(router)

  // pinia 已就绪，可以安装依赖 store 的认证钩子。
  installAuthHooks()

  app.mount('#app')
}

void bootstrap()
