<script setup lang="ts">
/**
 * 日报列表（学生/负责人视角：本人的全部草稿与已提交报告）。
 *
 * 契约关键行为：
 *   - 每成员每日最多一份（含草稿），重复 409 REPORT_ALREADY_EXISTS；
 *   - finalScore=null 表示评分中，不是 0 分；
 *   - 已提交报告可编辑时版本号要带上做乐观锁。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { listDailyReports } from '@/api/modules/reports'
import { describeError, toApiError } from '@/api/errors'
import { reportStatusMeta, scoreStatusMeta } from '@/components/statusMeta'
import { useAuthStore } from '@/stores/auth'
import type { DailyReport, PageData } from '@/api/types'

const router = useRouter()
const auth = useAuthStore()

const data = ref<PageData<DailyReport> | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listDailyReports({
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
  void router.push({ name: 'daily-report-new' })
}

function goDetail(row: DailyReport): void {
  void router.push({ name: 'daily-report-detail', params: { reportId: row.id } })
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>我的日报</span>
        <el-button type="primary" data-test="create-daily" @click="goCreate">写日报</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column prop="reportDate" label="日期" width="120" />
      <el-table-column prop="learningContent" label="学习内容" min-width="220" show-overflow-tooltip />
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
      <el-table-column prop="awardedPoints" label="保底积分" width="90" />
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
