<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="我的复核：查看本人报告提出的评分复核申请及其处理结果。"
    :endpoints="['GET /api/v1/score-reviews', 'POST /api/v1/score-reviews']"
    pending
  />
</template>
