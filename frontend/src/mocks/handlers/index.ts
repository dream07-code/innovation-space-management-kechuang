/**
 * MSW handlers 汇总。
 *
 * 本轮范围是「地基 + 认证」，因此只实现认证及其依赖的只读接口
 * （方向目录、系统规则）。其余业务接口尚未实现 ——
 * 相应地，未覆盖的请求会被 MSW 放行到真实网络，开发环境下会失败，
 * 这正是预期的：占位页面还没有数据需求。
 *
 * 后续实现各业务页面时，在此追加对应的 handler 模块。
 */
import { authHandlers } from './auth'
import { referenceHandlers } from './reference'

export const handlers = [...authHandlers, ...referenceHandlers]
