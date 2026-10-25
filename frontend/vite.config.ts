import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')

  // 后端显式关闭了 CORS（SecurityConfiguration: .cors(cors -> cors.disable())），
  // 浏览器无法跨域直连，因此开发环境必须经 dev proxy 同源转发。
  // 后端默认监听 127.0.0.1:8080；/api/v1 前缀在控制器中，契约 servers 为 "/"。
  const backendTarget = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    // 显式声明根路径：router 用 import.meta.env.BASE_URL 初始化 history，
    // 未声明时构建产物里可能拿不到值，导致跳转地址拼出 “undefined/…”。
    base: '/',
    plugins: [vue(), vueDevTools()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      // Vite 6+ 默认只允许本机/内网 Host（防 DNS rebinding）。把 dev server
      // 通过隧道暴露到公网演示时需要放行外部域名；生产走 nginx 同源，不受影响。
      allowedHosts: true,
      proxy: {
        // 业务接口
        '/api': { target: backendTarget, changeOrigin: true },
        // 健康检查是唯一已实现的后端接口，返回裸 {status}（无业务信封）
        '/actuator': { target: backendTarget, changeOrigin: true },
      },
    },
  }
})
