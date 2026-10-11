/**
 * 方向目录缓存。
 *
 * 七个方向由后端预置，首期不支持新增或更名，且契约中没有方向写接口，
 * 因此这是一份**只读**且长期有效的数据，缓存在 store 里避免每个筛选器各拉一次。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { listDirections } from '@/api/modules/directions'
import { toApiError } from '@/api/errors'
import type { Direction, DirectionCode } from '@/api/types'

export const useDirectionsStore = defineStore('directions', () => {
  const items = ref<Direction[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<string | null>(null)

  /** 供 el-select 使用的选项。 */
  const options = computed(() =>
    items.value.map((d) => ({ value: d.id, label: d.name, code: d.code })),
  )

  const byId = computed(() => new Map(items.value.map((d) => [d.id, d])))

  function nameOf(directionId: string | null | undefined): string {
    if (!directionId) return '—'
    return byId.value.get(directionId)?.name ?? '—'
  }

  function codeOf(directionId: string | null | undefined): DirectionCode | null {
    if (!directionId) return null
    return byId.value.get(directionId)?.code ?? null
  }

  /**
   * 加载方向目录。已有缓存时直接返回，不重复请求。
   *
   * @param force 强制刷新。
   */
  async function ensureLoaded(force = false): Promise<Direction[]> {
    if (loaded.value && !force) return items.value

    loading.value = true
    error.value = null
    try {
      items.value = await listDirections()
      loaded.value = true
      return items.value
    } catch (e) {
      const apiError = toApiError(e)
      error.value = apiError.message
      throw apiError
    } finally {
      loading.value = false
    }
  }

  /** 退出登录时清空，避免下一个账号看到上一个账号的缓存。 */
  function reset(): void {
    items.value = []
    loaded.value = false
    error.value = null
  }

  return { items, loading, loaded, error, options, byId, nameOf, codeOf, ensureLoaded, reset }
})
