<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="周报编辑：按自然周（北京时间周一至周日）撰写、提交与查看评分。"
    :endpoints="['POST /api/v1/weekly-reports', 'GET /api/v1/weekly-reports/{reportId}', 'PATCH /api/v1/weekly-reports/{reportId}', 'POST /api/v1/weekly-reports/{reportId}/submit']"
    pending
  />
</template>
