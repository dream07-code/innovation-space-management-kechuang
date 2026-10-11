<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="负责人管理：任命现有在籍学生为方向负责人，调整其资料、负责方向与账号状态，或撤销任命。"
    :endpoints="['GET /api/v1/direction-leaders', 'POST /api/v1/direction-leaders', 'PATCH /api/v1/direction-leaders/{memberId}', 'POST /api/v1/direction-leaders/{memberId}/revoke']"
  />
</template>
