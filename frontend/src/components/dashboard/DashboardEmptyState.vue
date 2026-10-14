<script setup lang="ts">
/**
 * 看板空状态：保持紧凑，并给出明确的下一步入口。
 */
defineProps<{
  title: string
  description: string
  actionLabel?: string
}>()

defineEmits<{ action: [] }>()
</script>

<template>
  <div class="dashboard-empty">
    <div>
      <strong>{{ title }}</strong>
      <p>{{ description }}</p>
    </div>
    <el-button v-if="actionLabel" type="primary" @click="$emit('action')">
      {{ actionLabel }}
    </el-button>
  </div>
</template>

<style scoped>
.dashboard-empty {
  display: flex;
  min-height: 150px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 24px;
  border: 1px dashed var(--app-border-strong);
  border-radius: 8px;
  background: var(--app-surface-soft);
}

.dashboard-empty strong {
  color: var(--app-ink);
  font-size: 14px;
}

.dashboard-empty p {
  max-width: 520px;
  margin: 7px 0 0;
  color: var(--app-muted);
  line-height: 1.7;
}

@media (max-width: 560px) {
  .dashboard-empty {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
