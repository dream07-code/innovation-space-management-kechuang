<script setup lang="ts">
/**
 * 管理首页：核心指标 + 待办事项。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { listMembers } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'

const memberCount = ref(0)
const removedCount = ref(0)
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const [active, removed] = await Promise.all([
      listMembers({ page: 1, size: 1, status: 'ACTIVE' }),
      listMembers({ page: 1, size: 1, status: 'REMOVED' }),
    ])
    memberCount.value = active.total
    removedCount.value = removed.total
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading">
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never">
          <el-statistic title="在册成员" :value="memberCount" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <el-statistic title="已移出成员" :value="removedCount" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <el-statistic title="方向数" :value="7" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="dashboard__actions">
      <template #header>常用操作</template>
      <el-space wrap>
        <el-button type="primary" @click="$router.push({ name: 'admin-members' })">人员管理</el-button>
        <el-button @click="$router.push({ name: 'admin-daily-reports' })">日报管理</el-button>
        <el-button @click="$router.push({ name: 'admin-weekly-reports' })">周报管理</el-button>
        <el-button @click="$router.push({ name: 'admin-leaders' })">负责人管理</el-button>
        <el-button @click="$router.push({ name: 'admin-audits' })">审计记录</el-button>
      </el-space>
    </el-card>
  </div>
</template>

<style scoped>
.dashboard__actions {
  margin-top: 16px;
}
</style>
