/**
 * MSW 浏览器端 worker。
 *
 * 仅开发环境启用：本轮后端 41 个业务接口均未实现（只有 /actuator/health 可用），
 * 前端只能对着 docs/api/openapi.yaml 契约做 Mock 开发。
 *
 * 切换到真实后端的做法：把 .env.local 里的 VITE_USE_MOCK 设为 false，
 * 并把 VITE_DEV_PROXY_TARGET 指向后端。业务代码不需要改动 ——
 * MSW 在 Service Worker 层拦截，api 模块对此无感知。
 */
import { setupWorker } from 'msw/browser'

import { handlers } from './handlers'

export const worker = setupWorker(...handlers)
