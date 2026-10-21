<script setup lang="ts">
/**
 * 负责人管理：列表 + 任命 + 调整管辖 + 撤销。
 *
 * 契约：/direction-leaders。全部仅学院管理员可用。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { appointLeader, listLeaders, revokeLeader, updateLeader } from '@/api/modules/leaders'
import { getMember } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'
import { useDirectionsStore } from '@/stores/directions'
import type { AppointLeaderRequest, Member, MemberPage, UpdateLeaderRequest } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const directions = useDirectionsStore()

const data = ref<MemberPage | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listLeaders({ page: page.value, size: size.value })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function handleAppoint(): Promise<void> {
  const { value: memberId } = await ElMessageBox.prompt('请输入要任命为负责人的成员 ID', '任命负责人', {
    confirmButtonText: '下一步',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length > 0 ? true : '请输入成员 ID'),
  }).catch(() => ({ value: null as string | null }))
  if (!memberId) return

  const { value: managedDirectionIds } = await ElMessageBox.prompt('请输入管辖方向 ID（多个用逗号分隔）', '任命负责人', {
    confirmButtonText: '下一步',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length > 0 ? true : '请输入方向 ID'),
  }).catch(() => ({ value: null as string | null }))
  if (!managedDirectionIds) return

  const { value: reason } = await ElMessageBox.prompt('请输入任命原因（至少 5 个字符）', '任命负责人', {
    confirmButtonText: '确认任命',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    const target = await getMember(memberId.trim())
    const payload: AppointLeaderRequest = {
      memberId: target.id,
      version: target.version,
      managedDirectionIds: managedDirectionIds.split(',').map((s) => s.trim()).filter(Boolean),
      reason: reason.trim(),
    }
    await appointLeader(payload, newIdempotencyKey())
    ElMessage.success('已任命')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleUpdate(row: Member): Promise<void> {
  const { value: managedDirectionIds } = await ElMessageBox.prompt(
    `当前管辖方向：${row.managedDirectionIds.join(', ')}。请输入新的方向 ID（多个用逗号分隔）`,
    '调整管辖方向',
    {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputValue: row.managedDirectionIds.join(', '),
      inputValidator: (v) => (v && v.trim().length > 0 ? true : '请输入方向 ID'),
    },
  ).catch(() => ({ value: null as string | null }))
  if (!managedDirectionIds) return

  const { value: reason } = await ElMessageBox.prompt('请输入调整原因（至少 5 个字符）', '调整管辖方向', {
    confirmButtonText: '确认调整',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    const payload: UpdateLeaderRequest = {
      version: row.version,
      reason: reason.trim(),
      managedDirectionIds: managedDirectionIds.split(',').map((s) => s.trim()).filter(Boolean),
    }
    await updateLeader(row.id, payload)
    ElMessage.success('已调整')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleRevoke(row: Member): Promise<void> {
  const { value: reason } = await ElMessageBox.prompt(`请输入撤销 ${row.name} 负责人身份的原因（至少 5 个字符）`, '撤销负责人', {
    confirmButtonText: '确认撤销',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    await revokeLeader(row.id, { version: row.version, reason }, newIdempotencyKey())
    ElMessage.success('已撤销')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

onMounted(async () => {
  await directions.ensureLoaded()
  await load()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>负责人管理</span>
        <el-button type="primary" @click="handleAppoint">任命负责人</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column prop="studentNo" label="学号" width="140" />
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column label="所属方向" width="120">
        <template #default="{ row }">{{ directions.nameOf(row.directionId) }}</template>
      </el-table-column>
      <el-table-column label="管辖方向" min-width="200">
        <template #default="{ row }">
          {{ row.managedDirectionIds.map((id: string) => directions.nameOf(id)).join('、') }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleUpdate(row)">调整管辖</el-button>
          <el-button link type="danger" @click="handleRevoke(row)">撤销</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
