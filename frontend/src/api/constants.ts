/**
 * 跨模块共享的常量。
 *
 * 单独成文件是为了让 MSW mock 不必从 @/api/client 取前缀 ——
 * 否则 mock 会连带牵入 axios 实例的构造，在 Node 测试环境下是不必要的副作用。
 */

/**
 * 业务接口前缀。
 *
 * 契约 §1.3：「业务路径包含 /api/v1；不要重复添加前缀」。
 * 该前缀属于控制器路径的一部分，因此 baseURL 只填 origin。
 */
export const API_PREFIX = '/api/v1'

/** 业务时区。契约中该字段是单值枚举，前端不据本机时区推断业务日期。 */
export const BUSINESS_TIMEZONE = 'Asia/Shanghai'
