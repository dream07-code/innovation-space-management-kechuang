<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="排行榜：切换全空间或指定方向、累计榜或指定月份榜，并高亮本人名次。列表只展示契约允许的最小字段。"
    :endpoints="['GET /api/v1/rankings/overall', 'GET /api/v1/rankings/directions/{directionId}', 'GET /api/v1/rankings/me']"
    pending
  />
</template>
