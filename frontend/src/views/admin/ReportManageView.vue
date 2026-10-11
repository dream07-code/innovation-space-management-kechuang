<script setup lang="ts">
// 占位骨架：业务实现待后续开发。
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    description="报告管理：按方向、学生、日期区间与评分状态查看权限范围内的报告；管理员只看正式报告，负责人可看本人草稿。"
    :endpoints="['GET /api/v1/daily-reports', 'GET /api/v1/weekly-reports']"
  />
</template>
