<script setup lang="ts">
/**
 * 看板内容区：统一标题、操作入口、loading 和稳定高度。
 */
import { useRouter } from 'vue-router'

withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    actionLabel?: string
    actionTo?: string
    loading?: boolean
    minHeight?: number
  }>(),
  {
    subtitle: '',
    actionLabel: '',
    actionTo: '',
    loading: false,
    minHeight: 220,
  },
)

const router = useRouter()
</script>

<template>
  <section
    class="dashboard-section"
    :aria-busy="loading ? 'true' : 'false'"
    :style="{ minHeight: `${minHeight}px` }"
  >
    <header class="dashboard-section__header">
      <div>
        <h2>{{ title }}</h2>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
      <el-button
        v-if="actionLabel && actionTo"
        link
        type="primary"
        @click="router.push(actionTo)"
      >
        {{ actionLabel }}
      </el-button>
      <slot name="action" />
    </header>
    <div class="dashboard-section__body" :class="{ 'is-loading': loading }">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.dashboard-section {
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}

.dashboard-section__header {
  display: flex;
  min-height: 66px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--app-border);
}

.dashboard-section__header h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 15px;
  line-height: 1.35;
}

.dashboard-section__header p {
  margin: 4px 0 0;
  color: var(--app-muted);
  font-size: 11px;
  line-height: 1.5;
}

.dashboard-section__body {
  min-width: 0;
  padding: 18px;
  transition: opacity var(--app-transition);
}

.dashboard-section__body.is-loading {
  opacity: 0.72;
}

@media (max-width: 560px) {
  .dashboard-section__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .dashboard-section__body {
    padding: 14px;
  }
}
</style>
