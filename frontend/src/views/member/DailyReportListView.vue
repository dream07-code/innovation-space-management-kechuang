<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="日报列表：筛选与分页查看本人的草稿与已提交日报，并轮询异步评分状态。"
    :endpoints="['GET /api/v1/daily-reports']"
    pending
  />
</template>
