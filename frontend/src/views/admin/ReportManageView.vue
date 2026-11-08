<script setup lang="ts">
/**
 * 报告管理（日报/周报共用）。
 *
 * 契约：负责人看本人全部 + 所辖方向正式报告；管理员只看正式报告。
 * 通过 props.reportType 区分日报/周报。两个路由复用同一组件实例，
 * 因此必须监听 reportType 变化重新拉取，否则周报页会残留日报数据。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { listDailyReports, listWeeklyReports } from '@/api/modules/reports'
import { describeError, toApiError } from '@/api/errors'
import ReportDetailDrawer from '@/components/report/ReportDetailDrawer.vue'
import { reportStatusMeta, scoreStatusMeta } from '@/components/statusMeta'
import { useDirectionsStore } from '@/stores/directions'
import { formatDateTime } from '@/utils/datetime'
import type { DailyReport, PageData, ReportStatus, WeeklyReport } from '@/api/types'

const props = defineProps<{
  reportType: 'DAILY' | 'WEEKLY'
}>()

const router = useRouter()
const directions = useDirectionsStore()

type AnyReport = DailyReport | WeeklyReport

const data = ref<PageData<AnyReport> | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const filterDirectionId = ref('')
const filterStatus = ref<ReportStatus | ''>('')
const drawerVisible = ref(false)
const activeReportId = ref<string | null>(null)

const title = computed(() => (props.reportType === 'DAILY' ? '日报' : '周报'))
const periodKey = computed(() => (props.reportType === 'DAILY' ? 'reportDate' : 'weekStart'))

// 快速切换日报/周报时，先发出的请求可能后返回；只接受最后一次请求的结果。
let loadSeq = 0

async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  try {
    const params = {
      page: page.value,
      size: size.value,
      directionId: filterDirectionId.value || undefined,
      status: filterStatus.value === '' ? undefined : filterStatus.value,
    }
    const result =
      props.reportType === 'DAILY'
        ? await listDailyReports(params)
        : await listWeeklyReports(params)
    if (seq === loadSeq) data.value = result
  } catch (error) {
    if (seq === loadSeq) ElMessage.error(describeError(toApiError(error)))
  } finally {
    if (seq === loadSeq) loading.value = false
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

/**
 * 学生刚提交的报告要能立刻被看到。
 *
 * 列表只在进页面时拉一次，管理员常把页面开着等报告，切回标签页时如果不刷新就会
 * 一直看到旧数据。窗口重新可见时静默拉一次，另配一个手动刷新按钮兜底。
 */
function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') void load()
}

/** 进入批阅页：把当前的方向筛选带过去，批阅范围与列表看到的一致。 */
function goReview(): void {
  void router.push({
    name: props.reportType === 'DAILY' ? 'admin-daily-report-review' : 'admin-weekly-report-review',
    query: filterDirectionId.value ? { directionId: filterDirectionId.value } : {},
  })
}

function openDetail(reportId: string): void {
  activeReportId.value = reportId
  drawerVisible.value = true
}

// 日报/周报路由复用同一组件实例：props 变了但 onMounted 不会重跑，
// 必须显式重新加载，否则周报管理会显示日报数据。
watch(
  () => props.reportType,
  () => {
    page.value = 1
    filterStatus.value = ''
    data.value = null
    // 抽屉里的报告属于旧类型，切页时必须关闭，否则会用周报模板渲染日报。
    drawerVisible.value = false
    activeReportId.value = null
    void load()
  },
)

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  await directions.ensureLoaded()
  await load()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>{{ title }}管理</span>
        <el-button type="primary" data-test="review-entry" @click="goReview">批阅</el-button>
      </div>
    </template>

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
      <el-form-item>
        <el-button :loading="loading" data-test="refresh" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column label="成员" min-width="150">
        <template #default="{ row }">
          <div class="member-cell">
            <span class="member-cell__name">{{ row.memberName ?? '未知成员' }}</span>
            <span v-if="row.memberStudentNo" class="member-cell__no">
              {{ row.memberStudentNo }}
            </span>
          </div>
        </template>
      </el-table-column>
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
          <span v-if="row.finalScore !== null" class="score-cell">
            {{ row.finalScore }} 分
            <el-tag v-if="row.scoreStatus === 'MANUAL'" size="small" type="primary" effect="plain">
              人工
            </el-tag>
          </span>
          <el-tag v-else :type="scoreStatusMeta(row.scoreStatus).type" effect="plain">
            {{ scoreStatusMeta(row.scoreStatus).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="awardedPoints" label="已入账积分" width="100" />
      <el-table-column label="更新时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">查看 / 评分</el-button>
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

    <ReportDetailDrawer
      v-model="drawerVisible"
      :report-type="reportType"
      :report-id="activeReportId"
      @scored="load"
    />
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page__filter {
  margin-bottom: 16px;
}

.member-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.member-cell__name {
  font-weight: 500;
}

.member-cell__no {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.score-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.page__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
