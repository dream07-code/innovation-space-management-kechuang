<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="自动任务：查看 AI 评分、未提交日报检测与月度评定任务的执行状态，并重试符合条件的失败任务。"
    :endpoints="['GET /api/v1/tasks', 'GET /api/v1/tasks/{taskId}', 'POST /api/v1/tasks/{taskId}/retry']"
    pending
  />
</template>
