<script setup lang="ts">
/**
 * 我的积分：总览 + 月度查询 + 流水列表。
 *
 * 契约：GET /points/me（可选 month）、GET /points/me/entries。
 * 积分由流水净和得到，前端没有任何写入口。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { getMyPoints, listPointEntries } from '@/api/modules/points'
import { describeError, toApiError } from '@/api/errors'
import { formatDateTime } from '@/utils/datetime'
import type { PageData, PointEntry, PointsSummary } from '@/api/types'

const summary = ref<PointsSummary | null>(null)
const entries = ref<PageData<PointEntry> | null>(null)
const loading = ref(false)
const month = ref('')

const page = ref(1)
const size = ref(20)

function currentMonth(): string {
  const d = new Date(Date.now() + 8 * 3600_000)
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [s, e] = await Promise.all([
      getMyPoints(month.value || undefined),
      listPointEntries({
        page: page.value,
        size: size.value,
        month: month.value || undefined,
      }),
    ])
    summary.value = s
    entries.value = e
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

function onMonthChange(): void {
  page.value = 1
  void load()
}

const SOURCE_LABELS: Record<string, string> = {
  SUBMISSION_BASE: '提交保底',
  AI_ADJUSTMENT: '评分调整',
  MANUAL_ADJUSTMENT: '人工调整',
  REVERSAL: '冲正',
}

onMounted(() => {
  month.value = currentMonth()
  void load()
})
</script>

<template>
  <div>
    <el-card shadow="never" class="points__summary">
      <template #header>积分总览</template>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-statistic title="累计积分" :value="summary?.totalPoints ?? 0" />
        </el-col>
        <el-col :span="8">
          <el-statistic title="本月积分" :value="summary?.monthlyPoints ?? 0" />
        </el-col>
        <el-col :span="8">
          <el-statistic title="结算月份" :value="summary?.month ?? '—'" />
        </el-col>
      </el-row>
    </el-card>

    <el-card shadow="never" class="points__entries">
      <template #header>
        <div class="page__header">
          <span>积分流水</span>
          <el-date-picker
            v-model="month"
            type="month"
            format="YYYY-MM"
            value-format="YYYY-MM"
            placeholder="选择月份"
            @change="onMonthChange"
          />
        </div>
      </template>

      <el-table v-loading="loading" :data="entries?.items ?? []" row-key="id">
        <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.recordedAt) }}</template>
      </el-table-column>
        <el-table-column prop="delta" label="变动" width="80">
          <template #default="{ row }">
            <span :class="row.delta >= 0 ? 'points__positive' : 'points__negative'">
              {{ row.delta >= 0 ? `+${row.delta}` : row.delta }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="110">
          <template #default="{ row }">{{ SOURCE_LABELS[row.source] ?? row.source }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="说明" min-width="200" show-overflow-tooltip />
      </el-table>

      <div class="page__pagination">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="entries?.total ?? 0"
          :current-page="page"
          :page-size="size"
          @current-change="onPageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.points__summary {
  margin-bottom: 16px;
}

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

.points__positive {
  color: var(--el-color-success);
  font-weight: 600;
}

.points__negative {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
