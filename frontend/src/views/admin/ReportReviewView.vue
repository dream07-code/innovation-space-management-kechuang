<script setup lang="ts">
/**
 * 日报 / 周报批阅页（管理员与方向负责人共用）。
 *
 * 布局按批阅场景组织：左边是「这个人是谁 + 他交过哪些报告」，右边是当前这一篇的正文与打分，
 * 右下角「下一个」直接串起待批阅队列，避免在列表与抽屉之间来回点。
 *
 * 边界（与契约一致）：
 *   - 日报与周报是两套数据、两条路由，本页只处理 props.reportType 这一种，不混排；
 *   - 可见范围由后端按角色裁剪，负责人只会拿到所辖方向的报告；
 *   - 提交人不能给自己打分，后端也会拒绝，前端提前禁用并说明原因；
 *   - 评分只对已提交报告生效，人工分一经写入 scoreStatus 变 MANUAL，迟到的 AI 结果不覆盖。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { describeError, toApiError } from '@/api/errors'
import {
  getDailyReport,
  getWeeklyReport,
  listDailyReports,
  listWeeklyReports,
  scoreDailyReport,
  scoreWeeklyReport,
} from '@/api/modules/reports'
import ReportContentView from '@/components/report/ReportContentView.vue'
import { rubricOf, type ReportKind } from '@/components/report/reportRubric'
import { REPORT_STATUS_META, SCORE_STATUS_META } from '@/components/statusMeta'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import { formatDateTime } from '@/utils/datetime'
import type { DailyReport, WeeklyReport } from '@/api/types'

const props = defineProps<{ reportType: ReportKind }>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const directions = useDirectionsStore()

type AnyReport = DailyReport | WeeklyReport

/** 队列一次取这么多：学院一个方向一学期的日报量级远小于此，避免翻页打断批阅节奏。 */
const QUEUE_SIZE = 200
const STUDENT_REPORT_SIZE = 100

const queue = ref<AnyReport[]>([])
const detail = ref<AnyReport | null>(null)
const studentReports = ref<AnyReport[]>([])
const loadingQueue = ref(false)
const loadingDetail = ref(false)
const saving = ref(false)

const score = ref<number | null>(null)
const reason = ref('')

const kindLabel = computed(() => (props.reportType === 'DAILY' ? '日报' : '周报'))
const rubric = computed(() => rubricOf(props.reportType))
const routeName = computed(() =>
  props.reportType === 'DAILY' ? 'admin-daily-report-review' : 'admin-weekly-report-review',
)
const directionFilter = computed(() =>
  typeof route.query.directionId === 'string' ? route.query.directionId : undefined,
)

const isSelf = computed(
  () => detail.value !== null && detail.value.memberId === auth.user?.memberId,
)
const canScore = computed(() => detail.value?.status === 'SUBMITTED' && !isSelf.value)

/** 期间文案：日报是日期，周报是周区间。 */
function periodOf(report: AnyReport): string {
  if (props.reportType === 'DAILY') return (report as DailyReport).reportDate
  const weekly = report as WeeklyReport
  return `${weekly.weekStart} ~ ${weekly.weekEnd}`
}

/** 列表里的「期间」列只需要起始日期。 */
function startOf(report: AnyReport): string {
  return props.reportType === 'DAILY'
    ? (report as DailyReport).reportDate
    : (report as WeeklyReport).weekStart
}

/** 未人工评分的排在前面，其余按期间倒序——批阅时先处理没打分的。 */
function orderQueue(items: AnyReport[]): AnyReport[] {
  const pending = items.filter((item) => item.scoreStatus !== 'MANUAL')
  const done = items.filter((item) => item.scoreStatus === 'MANUAL')
  return [...pending, ...done]
}

const pendingCount = computed(() => queue.value.filter((item) => item.scoreStatus !== 'MANUAL').length)
const currentIndex = computed(() =>
  detail.value === null ? -1 : queue.value.findIndex((item) => item.id === detail.value?.id),
)

/** 左侧统计：用该学生已提交的报告算，避免把草稿算进平均分。 */
const studentStats = computed(() => {
  const scored = studentReports.value.filter((item) => item.finalScore !== null)
  const reviewed = studentReports.value.filter((item) => item.scoreStatus === 'MANUAL')
  const sum = scored.reduce((acc, item) => acc + (item.finalScore ?? 0), 0)
  return {
    total: studentReports.value.length,
    reviewed: reviewed.length,
    average: scored.length ? (sum / scored.length).toFixed(1) : '—',
    points: studentReports.value.reduce((acc, item) => acc + item.awardedPoints, 0),
  }
})

function fetchList(params: Record<string, unknown>) {
  return props.reportType === 'DAILY'
    ? listDailyReports(params)
    : listWeeklyReports(params)
}

function fetchOne(id: string) {
  return props.reportType === 'DAILY' ? getDailyReport(id) : getWeeklyReport(id)
}

function sendScore(id: string, payload: { score: number; reason: string | null }) {
  return props.reportType === 'DAILY'
    ? scoreDailyReport(id, payload)
    : scoreWeeklyReport(id, payload)
}

/** 把评分结果同步回队列与左侧列表，避免整页重拉。 */
function patchLocal(updated: AnyReport): void {
  detail.value = updated
  queue.value = queue.value.map((item) => (item.id === updated.id ? updated : item))
  studentReports.value = studentReports.value.map((item) =>
    item.id === updated.id ? updated : item,
  )
}

async function loadStudentReports(memberId: string): Promise<void> {
  try {
    const page = await fetchList({
      page: 1,
      size: STUDENT_REPORT_SIZE,
      status: 'SUBMITTED',
      memberId,
    })
    studentReports.value = page.items
  } catch (error) {
    // 左侧只是辅助信息，拉不到不阻塞批阅
    studentReports.value = []
    ElMessage.warning(describeError(toApiError(error)))
  }
}

async function select(id: string): Promise<void> {
  loadingDetail.value = true
  try {
    const report = await fetchOne(id)
    detail.value = report
    score.value = report.scoreStatus === 'MANUAL' ? report.finalScore : null
    reason.value = report.scoreStatus === 'MANUAL' ? (report.scoreReason ?? '') : ''
    await loadStudentReports(report.memberId)
    void router.replace({ name: routeName.value, query: { ...route.query, reportId: id } })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loadingDetail.value = false
  }
}

async function loadQueue(): Promise<void> {
  loadingQueue.value = true
  try {
    const page = await fetchList({
      page: 1,
      size: QUEUE_SIZE,
      status: 'SUBMITTED',
      directionId: directionFilter.value,
    })
    queue.value = orderQueue(page.items)
    const wanted = typeof route.query.reportId === 'string' ? route.query.reportId : null
    const target =
      (wanted && queue.value.some((item) => item.id === wanted) ? wanted : null) ??
      queue.value[0]?.id ??
      null
    if (target) await select(target)
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loadingQueue.value = false
  }
}

/** 队列里下一条：优先跳过已经人工评过的，全部评完才按顺序走。 */
function nextCandidate(): AnyReport | null {
  const from = Math.max(currentIndex.value, -1) + 1
  const rest = queue.value.slice(from)
  return rest.find((item) => item.scoreStatus !== 'MANUAL') ?? rest[0] ?? null
}

async function goNext(): Promise<void> {
  const next = nextCandidate()
  if (!next) {
    ElMessage.info('已经是最后一条了')
    return
  }
  await select(next.id)
}

async function saveScore(): Promise<void> {
  if (detail.value === null) return
  if (score.value === null) {
    ElMessage.warning('请先选择 1–3 分')
    return
  }
  saving.value = true
  try {
    const updated = await sendScore(detail.value.id, {
      score: score.value,
      reason: reason.value.trim() || null,
    })
    patchLocal(updated)
    ElMessage.success('评分已保存，积分已按差额调整')
    // 保存后自动进入下一条待批阅，批阅一份报告只需一次点击。
    if (nextCandidate()) await goNext()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    saving.value = false
  }
}

function onRowClick(row: AnyReport): void {
  if (row.id !== detail.value?.id) void select(row.id)
}

function backToManage(): void {
  void router.push({
    name: props.reportType === 'DAILY' ? 'admin-daily-reports' : 'admin-weekly-reports',
  })
}

onMounted(async () => {
  await directions.ensureLoaded()
  await loadQueue()
})

// 日报/周报共用一个组件实例时 props 变化必须重拉，否则会把另一种报告渲染进来。
watch(
  () => props.reportType,
  () => {
    queue.value = []
    detail.value = null
    studentReports.value = []
    score.value = null
    reason.value = ''
    void loadQueue()
  },
)
</script>

<template>
  <div class="report-review" v-loading="loadingQueue">
    <el-card shadow="never" class="report-review__card">
      <template #header>
        <div class="report-review__header">
          <div class="report-review__title">
            <span>{{ kindLabel }}批阅</span>
            <el-tag v-if="pendingCount" type="warning" effect="plain" size="small">
              待评 {{ pendingCount }} 条
            </el-tag>
            <el-tag v-else type="success" effect="plain" size="small">已全部评完</el-tag>
          </div>
          <div class="report-review__header-actions">
            <el-button data-test="back-manage" @click="backToManage">返回{{ kindLabel }}管理</el-button>
          </div>
        </div>
      </template>

      <el-empty
        v-if="!queue.length && !loadingQueue"
        :description="`当前没有可批阅的${kindLabel}`"
        data-test="empty-queue"
      />

      <el-row v-else :gutter="16">
        <el-col :span="9">
          <section class="report-review__panel">
            <h4>学生信息</h4>
            <template v-if="detail">
              <el-descriptions :column="1" border size="small" data-test="student-info">
                <el-descriptions-item label="姓名">
                  {{ detail.memberName ?? '未知成员' }}
                </el-descriptions-item>
                <el-descriptions-item label="学号">
                  {{ detail.memberStudentNo ?? '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="方向">
                  {{ directions.nameOf(detail.directionId) }}
                </el-descriptions-item>
                <el-descriptions-item label="已入账积分">
                  {{ studentStats.points }} 分
                </el-descriptions-item>
                <el-descriptions-item :label="`${kindLabel}统计`">
                  共 {{ studentStats.total }} 条 · 已人工评 {{ studentStats.reviewed }} 条 · 平均
                  {{ studentStats.average }} 分
                </el-descriptions-item>
              </el-descriptions>
            </template>
          </section>

          <section class="report-review__panel">
            <h4>此学生{{ kindLabel }}列表</h4>
            <el-table
              :data="studentReports"
              size="small"
              highlight-current-row
              row-key="id"
              max-height="360"
              :row-class-name="({ row }: { row: AnyReport }) => (row.id === detail?.id ? 'is-current-row' : '')"
              data-test="student-reports"
              @row-click="onRowClick"
            >
              <el-table-column :label="reportType === 'DAILY' ? '日期' : '周开始'" width="110">
                <template #default="{ row }: { row: AnyReport }">
                  {{ startOf(row) }}
                </template>
              </el-table-column>
              <el-table-column label="分数" width="90">
                <template #default="{ row }: { row: AnyReport }">
                  <span v-if="row.finalScore !== null">{{ row.finalScore }} 分</span>
                  <el-tag v-else size="small" :type="SCORE_STATUS_META[row.scoreStatus].type" effect="plain">
                    {{ SCORE_STATUS_META[row.scoreStatus].label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }: { row: AnyReport }">
                  {{ REPORT_STATUS_META[row.status].label }}
                </template>
              </el-table-column>
              <el-table-column label="更新" min-width="120">
                <template #default="{ row }: { row: AnyReport }">{{ formatDateTime(row.updatedAt) }}</template>
              </el-table-column>
            </el-table>
            <div class="report-review__total" data-test="student-reports-total">
              共 {{ studentReports.length }} 条
            </div>
          </section>
        </el-col>

        <el-col :span="15">
          <section v-if="detail" class="report-review__panel" data-test="review-detail">
            <div class="report-review__detail-head">
              <h4>{{ kindLabel }}详情 · {{ periodOf(detail) }}</h4>
              <div class="report-review__detail-tags">
                <el-tag size="small" :type="REPORT_STATUS_META[detail.status].type" effect="plain">
                  {{ REPORT_STATUS_META[detail.status].label }}
                </el-tag>
                <el-tag size="small" :type="SCORE_STATUS_META[detail.scoreStatus].type" effect="plain">
                  {{ SCORE_STATUS_META[detail.scoreStatus].label }}
                </el-tag>
                <span class="report-review__progress">
                  {{ currentIndex >= 0 ? `${currentIndex + 1} / ${queue.length}` : '' }}
                </span>
              </div>
            </div>

            <div v-loading="loadingDetail" class="report-review__content">
              <ReportContentView :report="detail" :kind="reportType" />
            </div>

            <el-divider />

            <div class="report-review__score">
              <div class="report-review__score-row">
                <span class="report-review__score-label">评分</span>
                <el-radio-group v-model="score" :disabled="!canScore" data-test="score-group">
                  <el-radio-button :value="1">1 分</el-radio-button>
                  <el-radio-button :value="2">2 分</el-radio-button>
                  <el-radio-button :value="3">3 分</el-radio-button>
                </el-radio-group>
                <el-tag v-if="detail.scoreStatus === 'MANUAL'" size="small" type="primary" effect="plain">
                  已有人工评分
                </el-tag>
              </div>

              <ul class="report-review__rubric">
                <li><strong>3 分：</strong>{{ rubric.three }}</li>
                <li><strong>2 分：</strong>{{ rubric.two }}</li>
                <li><strong>1 分：</strong>{{ rubric.one }}</li>
              </ul>

              <el-alert
                v-if="isSelf"
                type="info"
                :closable="false"
                show-icon
                title="不能给自己的报告评分，可点「下一个」继续批阅他人的报告。"
              />

              <el-input
                v-model="reason"
                type="textarea"
                :rows="2"
                maxlength="1000"
                show-word-limit
                :disabled="!canScore"
                placeholder="评语（可选），会展示给学生作为评分理由"
                data-test="score-reason"
              />

              <div class="report-review__actions">
                <el-button
                  type="primary"
                  :loading="saving"
                  :disabled="!canScore || score === null"
                  data-test="save-score"
                  @click="saveScore"
                >
                  保存评分
                </el-button>
                <el-button data-test="next-report" @click="goNext">下一个</el-button>
              </div>
            </div>
          </section>

          <el-empty v-else description="从左侧选择一条报告开始批阅" />
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<style scoped>
.report-review__card :deep(.el-card__body) {
  padding-top: 12px;
}

.report-review__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.report-review__title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.report-review__panel {
  margin-bottom: 16px;
}

.report-review__panel h4 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.report-review__total {
  margin-top: 8px;
  text-align: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.report-review__detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.report-review__detail-head h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.report-review__detail-tags {
  display: flex;
  align-items: center;
  gap: 8px;
}

.report-review__progress {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.report-review__content {
  margin-top: 12px;
  max-height: 46vh;
  overflow-y: auto;
  padding-right: 4px;
}

.report-review__score-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.report-review__score-label {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.report-review__rubric {
  margin: 10px 0;
  padding-left: 18px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.7;
}

.report-review__actions {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}

:deep(.is-current-row) {
  background: var(--el-fill-color-light);
}
</style>
