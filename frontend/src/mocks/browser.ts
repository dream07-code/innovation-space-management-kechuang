/**
 * MSW 浏览器端 worker。
 *
 * 仅开发环境启用：旧模块和 OJ 考核先对着 docs/api/openapi.yaml 契约做 Mock 开发，
 * 真实后端接入通过 VITE_USE_MOCK=false 切换。
 *
 * 切换到真实后端的做法：把 .env.local 里的 VITE_USE_MOCK 设为 false，
 * 并把 VITE_DEV_PROXY_TARGET 指向后端。业务代码不需要改动 ——
 * MSW 在 Service Worker 层拦截，api 模块对此无感知。
 */
import { setupWorker } from 'msw/browser'

import { handlers } from './handlers'

export const worker = setupWorker(...handlers)
