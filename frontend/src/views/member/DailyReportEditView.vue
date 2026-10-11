<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="日报编辑：保存草稿、正式提交、查看评分与理由、在允许范围内修改、提出复核。"
    :endpoints="['POST /api/v1/daily-reports', 'GET /api/v1/daily-reports/{reportId}', 'PATCH /api/v1/daily-reports/{reportId}', 'POST /api/v1/daily-reports/{reportId}/submit']"
    pending
  />
</template>
