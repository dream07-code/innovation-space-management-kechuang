<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="个人首页：查看方向、累计与本月积分、总排名与方向排名、当日日报与本周周报状态。"
    :endpoints="['GET /api/v1/auth/me', 'GET /api/v1/points/me', 'GET /api/v1/rankings/me', 'GET /api/v1/daily-reports', 'GET /api/v1/weekly-reports']"
  />
</template>
