<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="规则配置：查看当前生效的业务规则及各项确认状态；月度评定规则须确认齐全后才可启用。"
    :endpoints="['GET /api/v1/system/rules', 'PUT /api/v1/system/rules/monthly-assessment']"
    pending
  />
</template>
