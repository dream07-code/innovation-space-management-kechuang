<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="人员管理：按方向、姓名或学号、状态筛选并分页；新增学生（仅限获授权方向）、查看与修改资料、移出成员。"
    :endpoints="['GET /api/v1/members', 'POST /api/v1/members', 'PATCH /api/v1/members/{memberId}', 'POST /api/v1/members/{memberId}/remove']"
  />
</template>
