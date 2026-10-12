/**
 * Mock：方向目录。
 *
 * 数据来源：与后端 V2__seed_directions.sql 完全一致的七个预置方向。
 * /system/rules 在契约中仍是 planned —— 前端不调用，mock 也不提供。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import { DIRECTIONS, errors, guardSession, ok } from '../state'

export const referenceHandlers = [
  http.get(`${API_PREFIX}/directions`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied
    // 契约中 data 直接是数组，不是分页对象。
    return ok(DIRECTIONS)
  }),

  http.get(`${API_PREFIX}/directions/:directionId`, ({ request, params }) => {
    const denied = guardSession(request)
    if (denied) return denied
    const direction = DIRECTIONS.find((d) => d.id === params.directionId)
    if (!direction) return errors.notFound()
    return ok(direction)
  }),
]
