<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="评定详情：查看快照内容（规则版本、参评人员、分数与入选原因），处理同分并确认执行。"
    :endpoints="['GET /api/v1/monthly-assessments/{assessmentId}', 'POST /api/v1/monthly-assessments/{assessmentId}/confirm', 'POST /api/v1/monthly-assessments/{assessmentId}/execute']"
    pending
  />
</template>
