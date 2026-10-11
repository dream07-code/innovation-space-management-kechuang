/**
 * Mock：方向目录与系统规则。
 *
 * 数据来源：
 *   - 方向：与后端 V2__seed_directions.sql 完全一致的七个预置方向；
 *   - 规则：如实反映契约的「规则待确认」状态，使前端的禁用逻辑真正被验证到。
 *
 * 所有接口都经 guardSession 校验，因此受限改密会话访问这里会得到
 * 403 PASSWORD_CHANGE_REQUIRED —— 与真实后端的行为一致。
 */
import { http } from 'msw'

import { API_PREFIX } from '@/api/constants'
import { DIRECTIONS, guardSession, ok, errors } from '../state'

export const referenceHandlers = [
  // -- 方向目录 --------------------------------------------------------------
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

  // -- 系统规则 --------------------------------------------------------------
  http.get(`${API_PREFIX}/system/rules`, ({ request }) => {
    const denied = guardSession(request)
    if (denied) return denied

    /**
     * 这里刻意返回 PENDING_CONFIRMATION。
     *
     * 需求文档 §8.1 与验收项 AC-09 要求：规则未确认齐全前不得启用月度淘汰，
     * 也不可假定「每个方向最后 3 人」之类的默认值。因此 Mock 如实呈现
     * 「未确认」状态（eliminateCount 为 null），让前端的禁用逻辑真正被验证到。
     */
    return ok({
      businessTimezone: 'Asia/Shanghai',
      reportPolicy: {
        configStatus: 'PENDING_CONFIRMATION',
        version: null,
        dailyBasePoints: null,
        weeklyBasePoints: null,
        maxFinalScore: null,
        allowBackfill: null,
        dailyEditPolicy: null,
        weeklyEditPolicy: null,
      },
      monthlyAssessment: {
        version: 1,
        configStatus: 'PENDING_CONFIRMATION',
        enabled: false,
        scope: null,
        // 淘汰人数未提供 —— 必须确认为具体值后才能执行。
        eliminateCount: null,
        minimumParticipants: null,
        minimumRetained: null,
        newMemberProtectionDays: null,
        tiePolicy: null,
        leaderParticipates: null,
        executionMode: null,
        effectiveFromMonth: null,
      },
      inactivityPolicy: {
        configStatus: 'PENDING_CONFIRMATION',
        enabled: true,
        thresholdDays: null,
        clockPolicy: null,
      },
      restorePolicy: {
        configStatus: 'PENDING_CONFIRMATION',
        retainHistoricalPoints: null,
      },
    })
  }),
]
