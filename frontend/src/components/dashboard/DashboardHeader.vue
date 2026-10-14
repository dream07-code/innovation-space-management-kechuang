<script setup lang="ts">
/**
 * 看板页头：时间范围、更新时间与手动刷新。
 */
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import type { DashboardRange } from '@/api/types'

const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    range: DashboardRange
    updatedAt: string | null
    loading?: boolean
  }>(),
  {
    description: '',
    loading: false,
  },
)

const emit = defineEmits<{
  'update:range': [value: DashboardRange]
  refresh: []
}>()

const updatedLabel = computed(() => {
  if (!props.updatedAt) return '尚未获取'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(props.updatedAt))
})

function onRangeChange(value: DashboardRange): void {
  emit('update:range', value)
}
</script>

<template>
  <header class="dashboard-header" :aria-busy="loading ? 'true' : 'false'">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>

    <div class="dashboard-header__controls">
      <span class="updated-at">更新于 {{ updatedLabel }}</span>
      <el-radio-group
        :model-value="range"
        size="large"
        class="range-control"
        aria-label="数据时间范围"
        @update:model-value="onRangeChange"
      >
        <el-radio-button value="7d">7 天</el-radio-button>
        <el-radio-button value="30d">30 天</el-radio-button>
      </el-radio-group>
      <el-tooltip content="刷新数据" placement="bottom">
        <el-button
          class="refresh-button"
          :icon="Refresh"
          :loading="loading"
          circle
          aria-label="刷新数据"
          @click="emit('refresh')"
        />
      </el-tooltip>
    </div>
  </header>
</template>

<style scoped>
.dashboard-header {
  display: flex;
  min-height: 76px;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
  margin-bottom: 18px;
}

.dashboard-header h1 {
  margin: 0;
  color: var(--app-ink);
  font-size: 25px;
  line-height: 1.25;
  letter-spacing: 0;
}

.dashboard-header p {
  margin: 7px 0 0;
  color: var(--app-muted);
  line-height: 1.6;
}

.dashboard-header__controls {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 12px;
}

.updated-at {
  color: var(--app-muted);
  font-size: 11px;
}

.range-control :deep(.el-radio-button__inner) {
  min-width: 64px;
  min-height: 44px;
}

.refresh-button {
  width: 44px;
  height: 44px;
}

@media (max-width: 720px) {
  .dashboard-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .dashboard-header__controls {
    width: 100%;
    justify-content: space-between;
  }

  .updated-at {
    display: none;
  }
}
</style>
