<script setup lang="ts">
/**
 * 报告管理（日报/周报共用）。
 *
 * 契约：负责人看本人全部 + 所辖方向正式报告；管理员只看正式报告。
 * 通过 props.reportType 区分日报/周报。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { listDailyReports, listWeeklyReports } from '@/api/modules/reports'
import { describeError, toApiError } from '@/api/errors'
import { reportStatusMeta, scoreStatusMeta } from '@/components/statusMeta'
import { useDirectionsStore } from '@/stores/directions'
import type { DailyReport, PageData, ReportStatus, WeeklyReport } from '@/api/types'

const props = defineProps<{
  reportType: 'DAILY' | 'WEEKLY'
}>()

const directions = useDirectionsStore()

type AnyReport = DailyReport | WeeklyReport

const data = ref<PageData<AnyReport> | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const filterDirectionId = ref('')
const filterStatus = ref<ReportStatus | ''>('')

const title = computed(() => (props.reportType === 'DAILY' ? '日报' : '周报'))
const periodKey = computed(() => (props.reportType === 'DAILY' ? 'reportDate' : 'weekStart'))

async function load(): Promise<void> {
  loading.value = true
  try {
    const params = {
      page: page.value,
      size: size.value,
      directionId: filterDirectionId.value || undefined,
      status: filterStatus.value === '' ? undefined : filterStatus.value,
    }
    if (props.reportType === 'DAILY') {
      data.value = await listDailyReports(params)
    } else {
      data.value = await listWeeklyReports(params)
    }
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

function onFilterChange(): void {
  page.value = 1
  void load()
}

onMounted(async () => {
  await directions.ensureLoaded()
  await load()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>{{ title }}管理</template>

    <el-form inline class="page__filter">
      <el-form-item label="方向">
        <el-select v-model="filterDirectionId" clearable placeholder="全部" style="width: 160px" @change="onFilterChange">
          <el-option v-for="d in directions.items" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filterStatus" clearable placeholder="全部" style="width: 120px" @change="onFilterChange">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已提交" value="SUBMITTED" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column prop="memberId" label="成员 ID" width="100" />
      <el-table-column :prop="periodKey" :label="reportType === 'DAILY' ? '日期' : '周开始'" width="120" />
      <el-table-column label="方向" width="120">
        <template #default="{ row }">{{ directions.nameOf(row.directionId) }}</template>
      </el-table-column>
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
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
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
.page__filter {
  margin-bottom: 16px;
}

.page__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
