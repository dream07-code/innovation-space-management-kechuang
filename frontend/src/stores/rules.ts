/**
 * 业务规则状态。
 *
 * 契约 §7.2 与需求文档 §8.1、验收项 AC-09 反复强调：
 *   规则未确认齐全之前，系统可以展示月榜，但**不得默认启用月度淘汰**，
 *   也不可自行假定「每个方向最后 3 人」之类的具体规则。
 *
 * 因此本 store 只如实反映 /system/rules 返回的 configStatus，
 * 绝不内置任何业务默认值。界面据此禁用按钮，而不是自己决定能否执行。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchSystemRules } from '@/api/modules/system'
import { toApiError } from '@/api/errors'
import type { ConfigStatus, SystemRules } from '@/api/types'

export const useRulesStore = defineStore('rules', () => {
  const rules = ref<SystemRules | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<string | null>(null)

  /** 报告积分规则是否已确认（决定日报周报的计分语义是否可展示为最终值）。 */
  const reportPolicyStatus = computed<ConfigStatus | null>(
    () => rules.value?.reportPolicy.configStatus ?? null,
  )

  /** 连续未提交日报自动移出规则是否已确认。 */
  const inactivityPolicyStatus = computed<ConfigStatus | null>(
    () => rules.value?.inactivityPolicy.configStatus ?? null,
  )

  /** 恢复成员规则是否已确认。 */
  const restorePolicyStatus = computed<ConfigStatus | null>(
    () => rules.value?.restorePolicy.configStatus ?? null,
  )

  /** 月度评定规则是否已确认。未确认时不得执行任何淘汰动作。 */
  const monthlyAssessmentStatus = computed<ConfigStatus | null>(
    () => rules.value?.monthlyAssessment.configStatus ?? null,
  )

  /**
   * 月度评定是否可执行。
   *
   * 必须同时满足：规则状态为 READY、已启用、且淘汰人数等必要参数已配置。
   * 淘汰人数在契约中是可空的（nullable），未配置时**必须**禁止执行 ——
   * 这正是 AC-09「不使用隐藏默认人数」的要求。
   */
  const monthlyAssessmentExecutable = computed(() => {
    const m = rules.value?.monthlyAssessment
    if (!m) return false
    return (
      m.configStatus === 'READY' &&
      m.enabled === true &&
      m.eliminateCount !== null &&
      m.eliminateCount !== undefined &&
      m.scope !== null &&
      m.scope !== undefined
    )
  })

  /** 业务时区，固定为 Asia/Shanghai（契约中该字段是单值枚举）。 */
  const businessTimezone = computed(() => rules.value?.businessTimezone ?? 'Asia/Shanghai')

  async function ensureLoaded(force = false): Promise<SystemRules> {
    if (loaded.value && rules.value && !force) return rules.value

    loading.value = true
    error.value = null
    try {
      const result = await fetchSystemRules()
      rules.value = result
      loaded.value = true
      return result
    } catch (e) {
      const apiError = toApiError(e)
      error.value = apiError.message
      throw apiError
    } finally {
      loading.value = false
    }
  }

  function reset(): void {
    rules.value = null
    loaded.value = false
    error.value = null
  }

  return {
    rules,
    loading,
    loaded,
    error,
    reportPolicyStatus,
    inactivityPolicyStatus,
    restorePolicyStatus,
    monthlyAssessmentStatus,
    monthlyAssessmentExecutable,
    businessTimezone,
    ensureLoaded,
    reset,
  }
})
