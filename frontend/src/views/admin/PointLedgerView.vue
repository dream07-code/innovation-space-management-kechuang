<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="积分流水：查看权限范围内的积分变动明细，含来源、关联报告与业务生效时间。"
    :endpoints="['GET /api/v1/points/ledger']"
  />
</template>
