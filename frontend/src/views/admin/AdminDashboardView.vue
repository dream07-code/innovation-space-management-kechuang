<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="管理首页：全空间人数概览、待处理复核与失败任务提醒。"
    :endpoints="['GET /api/v1/members', 'GET /api/v1/score-reviews', 'GET /api/v1/tasks', 'GET /api/v1/system/rules']"
  />
</template>
