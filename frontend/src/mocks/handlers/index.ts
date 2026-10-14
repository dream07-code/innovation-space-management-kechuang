/**
 * MSW handlers 汇总。
 *
 * 覆盖当前前端开发所需的既有业务接口与 OJ planned 契约。
 * 不覆盖的（规划中功能）：/score-reviews、/system/rules、members/remove ——
 * 前端不调用它们；若误调，请求会被放行到真实网络并在开发环境可见地失败。
 */
import { auditHandlers } from './audits'
import { dashboardHandlers } from './dashboard'
import { authHandlers } from './auth'
import { leaderHandlers } from './leaders'
import { memberHandlers } from './members'
import { ojHandlers } from './oj'
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
  ...dashboardHandlers,
  ...ojHandlers,
]
