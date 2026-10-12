<script setup lang="ts">
/**
 * 固定高度骨架，避免数据加载时页面跳动。
 */
withDefaults(
  defineProps<{
    rows?: number
    height?: number
  }>(),
  {
    rows: 4,
    height: 280,
  },
)
</script>

<template>
  <div class="dashboard-skeleton" :style="{ minHeight: `${height}px` }" aria-label="正在加载数据">
    <el-skeleton animated>
      <template #template>
        <el-skeleton-item variant="h3" style="width: 38%; height: 20px" />
        <el-skeleton-item
          v-for="row in rows"
          :key="row"
          variant="text"
          :style="{ width: `${92 - row * 7}%`, height: '15px', marginTop: '18px' }"
        />
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped>
.dashboard-skeleton {
  padding: 8px 0;
}
</style>
