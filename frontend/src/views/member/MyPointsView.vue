<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="积分记录：本人累计与本月积分概览，以及逐条积分流水（含来源与业务生效时间）。"
    :endpoints="['GET /api/v1/points/me', 'GET /api/v1/points/ledger']"
  />
</template>
