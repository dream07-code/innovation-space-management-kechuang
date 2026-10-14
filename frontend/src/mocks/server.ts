/**
 * MSW Node server，供单元测试使用。
 *
 * 与 browser.ts 共用同一份 handlers，因此测试验证的是与浏览器中
 * 完全相同的契约行为。
 */
import { setupServer } from 'msw/node'

import { handlers } from './handlers'

export const server = setupServer(...handlers)
