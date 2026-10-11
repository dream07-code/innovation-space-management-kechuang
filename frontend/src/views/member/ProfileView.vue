<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="个人资料：仅查看本人信息。学生端没有资料修改入口，契约中学生不使用 PATCH /members。"
    :endpoints="['GET /api/v1/auth/me']"
  />
</template>
