/**
 * Vitest 配置。
 *
 * 注意：vite.config.ts 导出的是配置**函数**（因为要用 mode 读取环境变量），
 * 而 mergeConfig 要求传入已解析的对象。因此这里不直接 merge，
 * 而是各自独立定义 —— 测试环境只需要别名，不需要 dev proxy。
 */
import { fileURLToPath } from 'node:url'
import { configDefaults, defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    // MSW 的 handler 用相对路径（/api/v1/...）声明，在 Node 环境下
    // 这些路径要相对 location 解析。jsdom 默认的 url 是 http://localhost:3000，
    // 而测试中请求 http://localhost，两者 origin 不一致会导致 handler 匹配不上
    // 并让请求真的发到网络。这里把 jsdom 的 origin 固定下来，与测试里的 BASE 对齐。
    environmentOptions: {
      jsdom: { url: 'http://localhost/' },
    },
    exclude: [...configDefaults.exclude, 'e2e/**'],
    root: fileURLToPath(new URL('./', import.meta.url)),
  },
})
