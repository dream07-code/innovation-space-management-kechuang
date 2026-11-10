import { describe, expect, it } from 'vitest'

import { ApiError, describeError } from '@/api/errors'

/**
 * 字段级校验错误要能看到是哪个字段。
 *
 * 后端对 INVALID_REQUEST 会回「Invalid request: unknown field: problemId」这类信息，
 * 前端不能只显示「请求内容不合法」，否则调用方不知道该改哪里。
 */
describe('describeError', () => {
  it('INVALID_REQUEST 带字段细节时一并展示', () => {
    const error = new ApiError({
      code: 'INVALID_REQUEST',
      httpStatus: 400,
      message: 'Invalid request: unknown field: problemId',
    })

    const text = describeError(error)
    expect(text).toContain('请求内容不合法')
    expect(text).toContain('unknown field: problemId')
  })

  it('INVALID_REQUEST 没有细节时保持通用文案', () => {
    const error = new ApiError({
      code: 'INVALID_REQUEST',
      httpStatus: 400,
      message: 'Invalid request',
    })

    expect(describeError(error)).toBe('请求内容不合法')
  })

  it('考核已关闭有专门提示', () => {
    const error = new ApiError({
      code: 'ASSESSMENT_CLOSED',
      httpStatus: 409,
      message: 'Assessment closed',
    })

    expect(describeError(error)).toContain('考核已关闭')
  })
})
