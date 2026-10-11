<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="月度评定：生成候选快照、查看参评人员与同分情况、确认完整候选名单并异步执行。"
    :endpoints="['GET /api/v1/monthly-assessments', 'POST /api/v1/monthly-assessments']"
    pending
  />
</template>
