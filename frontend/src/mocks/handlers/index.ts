/**
 * MSW handlers 汇总。
 *
 * 覆盖契约中 x-implementation-status: implemented 的全部业务接口。
 * 不覆盖的（规划中功能）：/score-reviews、/system/rules、members/remove ——
 * 前端不调用它们；若误调，请求会被放行到真实网络并在开发环境可见地失败。
 */
import { auditHandlers } from './audits'
import { authHandlers } from './auth'
import { leaderHandlers } from './leaders'
import { memberHandlers } from './members'
import { pointHandlers, rankingHandlers } from './points'
import { referenceHandlers } from './reference'
import { reportHandlers } from './reports'

export const handlers = [
  ...authHandlers,
  ...referenceHandlers,
  ...memberHandlers,
  ...leaderHandlers,
  ...reportHandlers,
  ...pointHandlers,
  ...rankingHandlers,
  ...auditHandlers,
]
