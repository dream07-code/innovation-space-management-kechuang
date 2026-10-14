<script setup lang="ts">
/**
 * 个人资料：仅查看，不提供编辑入口。
 *
 * 契约 §5：学生不使用 PATCH /members——资料由管理员或所辖方向负责人维护。
 */
import { computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

import { describeError, toApiError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'

const auth = useAuthStore()
const directions = useDirectionsStore()

const profile = computed(() => auth.profile)
const directionName = computed(() => directions.nameOf(auth.user?.directionId))

onMounted(async () => {
  try {
    await auth.loadMe()
    await directions.ensureLoaded()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
})
</script>

<template>
  <el-card shadow="never" class="profile">
    <template #header>个人资料</template>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="学生资料由学院管理员或所辖方向负责人维护，个人端只读。"
      class="profile__tip"
    />
    <el-descriptions :column="2" border>
      <el-descriptions-item label="姓名">{{ auth.displayName }}</el-descriptions-item>
      <el-descriptions-item label="学号">{{ profile?.studentNo ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="方向">{{ directionName }}</el-descriptions-item>
      <el-descriptions-item label="专业">{{ profile?.major ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="班级">{{ profile?.className ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="年级">{{ profile?.grade ?? '—' }}</el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped>
.profile__tip {
  margin-bottom: 1rem;
}
</style>
