<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="成员详情：查看资料与报告提交情况；学院管理员可更正学号、重置密码、移出或恢复成员资格。"
    :endpoints="['GET /api/v1/members/{memberId}', 'POST /api/v1/members/{memberId}/account-corrections', 'POST /api/v1/members/{memberId}/password-reset', 'POST /api/v1/members/{memberId}/remove']"
  />
</template>
