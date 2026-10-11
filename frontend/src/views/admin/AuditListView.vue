<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="审计记录：查看脱敏后的管理员敏感操作记录，按资源与操作类型筛选。"
    :endpoints="['GET /api/v1/audits']"
  />
</template>
