<script setup lang="ts">
/**
 * 角色摘要条：在单个横向信息带内展示关键指标。
 */
export interface DashboardMetric {
  key: string
  label: string
  value: string | number
  suffix?: string
  hint?: string
  emphasis?: boolean
}

defineProps<{ items: DashboardMetric[] }>()
</script>

<template>
  <section class="overview-strip" aria-label="数据摘要">
    <div
      v-for="item in items"
      :key="item.key"
      class="overview-item"
      :class="{ 'overview-item--emphasis': item.emphasis }"
    >
      <span>{{ item.label }}</span>
      <strong>{{ item.value }}<small v-if="item.suffix">{{ item.suffix }}</small></strong>
      <p v-if="item.hint">{{ item.hint }}</p>
    </div>
  </section>
</template>

<style scoped>
.overview-strip {
  display: grid;
  overflow: hidden;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}

.overview-item {
  min-height: 112px;
  padding: 18px 20px;
  border-right: 1px solid var(--app-border);
}

.overview-item:nth-child(4n) {
  border-right: 0;
}

.overview-item:nth-child(n + 5) {
  border-top: 1px solid var(--app-border);
}

.overview-item > span {
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 700;
}

.overview-item strong {
  display: block;
  margin-top: 9px;
  color: var(--app-ink);
  font-size: 26px;
  line-height: 1;
}

.overview-item strong small {
  margin-left: 3px;
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 650;
}

.overview-item p {
  margin: 8px 0 0;
  color: var(--app-muted);
  font-size: 10px;
  line-height: 1.5;
}

.overview-item--emphasis {
  background: #f3f7ff;
}

@media (max-width: 980px) {
  .overview-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .overview-item:nth-child(2n) {
    border-right: 0;
  }

  .overview-item:nth-child(n + 3) {
    border-top: 1px solid var(--app-border);
  }
}

@media (max-width: 480px) {
  .overview-strip {
    grid-template-columns: 1fr 1fr;
  }

  .overview-item {
    min-height: 104px;
    padding: 15px;
  }

  .overview-item strong {
    font-size: 22px;
  }
}
</style>
