<script setup lang="ts">
/**
 * 周报列表（学生/负责人视角）。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { listWeeklyReports } from '@/api/modules/reports'
import { describeError, toApiError } from '@/api/errors'
import { reportStatusMeta, scoreStatusMeta } from '@/components/statusMeta'
import { useAuthStore } from '@/stores/auth'
import type { PageData, WeeklyReport } from '@/api/types'

const router = useRouter()
const auth = useAuthStore()

const data = ref<PageData<WeeklyReport> | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listWeeklyReports({
      page: page.value,
      size: size.value,
      memberId: auth.user?.memberId ?? undefined,
    })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

function goCreate(): void {
  void router.push({ name: 'weekly-report-new' })
}

function goDetail(row: WeeklyReport): void {
  void router.push({ name: 'weekly-report-detail', params: { reportId: row.id } })
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>我的周报</span>
        <el-button type="primary" data-test="create-weekly" @click="goCreate">写周报</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column prop="weekStart" label="周开始" width="120" />
      <el-table-column prop="weekEnd" label="周结束" width="120" />
      <el-table-column prop="summary" label="总结" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="reportStatusMeta(row.status).type" effect="plain">
            {{ reportStatusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="评分" width="130">
        <template #default="{ row }">
          <span v-if="row.finalScore !== null">{{ row.finalScore }} 分</span>
          <el-tag v-else :type="scoreStatusMeta(row.scoreStatus).type" effect="plain">
            {{ scoreStatusMeta(row.scoreStatus).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="page__pagination">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="data?.total ?? 0"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </div>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
