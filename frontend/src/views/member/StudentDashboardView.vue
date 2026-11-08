<script setup lang="ts">
/**
 * 学生个人数据看板。
 *
 * 数据来自 GET /dashboard/student，只展示本人安全摘要。
 * 不显示他人报告正文、学号、联系方式、代码、测试数据或 Token。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getStudentDashboard } from '@/api/modules/dashboard'
import { describeError, toApiError } from '@/api/errors'
import {
  reportStatusMeta,
  scoreStatusMeta,
} from '@/components/statusMeta'
import {
  assessmentStatusMeta,
  scoreLabel,
  scoreSourceMeta,
} from '@/components/ojMeta'
import DashboardEmptyState from '@/components/dashboard/DashboardEmptyState.vue'
import DashboardErrorState from '@/components/dashboard/DashboardErrorState.vue'
import DashboardHeader from '@/components/dashboard/DashboardHeader.vue'
import DashboardOverviewStrip, {
  type DashboardMetric,
} from '@/components/dashboard/DashboardOverviewStrip.vue'
import DashboardSection from '@/components/dashboard/DashboardSection.vue'
import DashboardSkeleton from '@/components/dashboard/DashboardSkeleton.vue'
import type {
  DashboardRange,
  StudentAssessmentSummary,
  StudentDashboard,
  StudentReportSummary,
} from '@/api/types'

const router = useRouter()
const range = ref<DashboardRange>('7d')
const data = ref<StudentDashboard | null>(null)
const loading = ref(false)
const error = ref('')
const updatedAt = ref<string | null>(null)

const overviewItems = computed<DashboardMetric[]>(() => {
  const overview = data.value?.overview
  if (!overview) return []
  return [
    { key: 'points', label: '当前积分', value: overview.points, emphasis: true },
    { key: 'overall', label: '总排名', value: overview.overallRank ?? '未上榜', hint: '仅 ACTIVE 学生参与' },
    { key: 'direction', label: '方向排名', value: overview.directionRank ?? '未上榜', hint: '由后端返回名次' },
    { key: 'dailyGap', label: '距上次日报', value: overview.daysSinceLastDaily ?? '暂无记录', suffix: overview.daysSinceLastDaily === null ? '' : '天' },
    { key: 'daily', label: '今日日报', value: overview.dailyStatus === 'SUBMITTED' ? '已提交' : overview.dailyStatus === 'DUE' ? '待提交' : '缺失' },
    { key: 'weekly', label: '本周周报', value: overview.weeklyStatus === 'SUBMITTED' ? '已提交' : overview.weeklyStatus === 'DUE' ? '待提交' : '缺失' },
    { key: 'oj', label: '待完成 OJ', value: overview.pendingOj, emphasis: true },
  ]
})

const dailyProgress = computed(() => {
  const progress = data.value?.progress
  if (!progress || progress.dailyTarget === 0) return 0
  return Math.min(100, Math.round((progress.dailySubmitted / progress.dailyTarget) * 100))
})

const weeklyProgress = computed(() => {
  const progress = data.value?.progress
  if (!progress || progress.weeklyTarget === 0) return 0
  return Math.min(100, Math.round((progress.weeklySubmitted / progress.weeklyTarget) * 100))
})

function reportRoute(report: StudentReportSummary): string {
  return report.reportType === 'DAILY'
    ? `/reports/daily/${report.reportId}`
    : `/reports/weekly/${report.reportId}`
}

/** 首页考核卡片直达成绩页：发布后能看到总分与逐题明细，未发布则显示待公布。 */
function openAssessment(item: StudentAssessmentSummary): void {
  void router.push(`/oj/assessments/${item.assessmentId}/results`)
}

function formatDateTime(value: string | null): string {
  if (!value) return '草稿未提交'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function submissionLabel(value: StudentAssessmentSummary['submissionStatus']): string {
  const labels: Record<StudentAssessmentSummary['submissionStatus'], string> = {
    NONE: '未提交',
    RECEIVED: '已接收',
    JUDGING: '判定中',
    JUDGED: '已判定',
    FAILED: '判定失败',
    NEEDS_REVIEW: '待人工复核',
  }
  return labels[value]
}

function verdictLabel(value: string | null): string {
  if (!value) return '待判定'
  const labels: Record<string, string> = {
    ACCEPTED: '通过',
    WRONG_ANSWER: '答案错误',
    TIME_LIMIT_EXCEEDED: '超时',
    MEMORY_LIMIT_EXCEEDED: '超内存',
    RUNTIME_ERROR: '运行错误',
    COMPILATION_ERROR: '编译错误',
    INTERNAL_ERROR: '内部错误',
  }
  return labels[value] ?? value
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await getStudentDashboard(range.value)
    updatedAt.value = new Date().toISOString()
  } catch (loadError) {
    const apiError = toApiError(loadError)
    if (apiError.isForbidden) {
      await router.replace('/')
      return
    }
    error.value = describeError(apiError)
  } finally {
    loading.value = false
  }
}

function onRangeChange(value: DashboardRange): void {
  range.value = value
  void load()
}

onMounted(load)
</script>

<template>
  <div class="role-dashboard">
    <DashboardHeader
      title="我的学习看板"
      description="查看个人积分、报告进度、OJ 考核和最近积分变化。"
      :range="range"
      :updated-at="updatedAt"
      :loading="loading"
      @update:range="onRangeChange"
      @refresh="load"
    />

    <DashboardErrorState v-if="error && !data" :message="error" show-back @retry="load" @back="router.push('/')" />

    <template v-else-if="!data">
      <DashboardSkeleton :rows="4" :height="120" />
      <div class="dashboard-grid dashboard-grid--main">
        <DashboardSkeleton :rows="5" :height="360" />
        <DashboardSkeleton :rows="5" :height="360" />
      </div>
    </template>

    <template v-else>
      <DashboardOverviewStrip :items="overviewItems" />

      <DashboardSection
        title="学习进度"
        :subtitle="`最近 ${range === '7d' ? '7' : '30'} 天的提交完成度与积分变化`"
        :loading="loading"
        :min-height="250"
      >
        <div class="progress-layout">
          <div class="progress-item">
            <div><span>日报提交</span><strong>{{ data.progress.dailySubmitted }} / {{ data.progress.dailyTarget }}</strong></div>
            <el-progress :percentage="dailyProgress" :stroke-width="10" :show-text="false" />
          </div>
          <div class="progress-item">
            <div><span>周报提交</span><strong>{{ data.progress.weeklySubmitted }} / {{ data.progress.weeklyTarget }}</strong></div>
            <el-progress :percentage="weeklyProgress" :stroke-width="10" :show-text="false" />
          </div>
          <div class="progress-stat">
            <span>区间新增积分</span>
            <strong>+{{ data.progress.pointsAdded }}</strong>
            <small>距上次日报 {{ data.progress.daysSinceLastDaily ?? '暂无记录' }}{{ data.progress.daysSinceLastDaily === null ? '' : ' 天' }}</small>
          </div>
        </div>
      </DashboardSection>

      <div class="dashboard-grid dashboard-grid--main">
        <DashboardSection
          title="最近报告"
          action-label="查看全部日报"
          action-to="/reports/daily"
          :loading="loading"
          :min-height="340"
        >
          <DashboardEmptyState
            v-if="data.reports.length === 0"
            title="还没有报告记录"
            description="提交第一份日报，记录今天的学习内容和收获。"
            action-label="提交日报"
            @action="router.push('/reports/daily/new')"
          />
          <template v-else>
            <div class="table-scroll" tabindex="0" aria-label="最近报告表，可横向滚动">
              <table>
                <thead>
                  <tr>
                    <th>类型</th>
                    <th>状态</th>
                    <th>评分</th>
                    <th>提交时间</th>
                    <th>可编辑</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="report in data.reports" :key="report.reportId">
                    <td>{{ report.reportType === 'DAILY' ? '日报' : '周报' }}</td>
                    <td>
                      <el-tag :type="reportStatusMeta(report.status).type" effect="plain">
                        {{ reportStatusMeta(report.status).label }}
                      </el-tag>
                    </td>
                    <td>
                      <el-tag :type="scoreStatusMeta(report.scoreStatus).type" effect="plain">
                        {{ scoreStatusMeta(report.scoreStatus).label }}
                      </el-tag>
                    </td>
                    <td>{{ formatDateTime(report.submittedAt) }}</td>
                    <td>{{ report.editable ? '可编辑' : '已锁定' }}</td>
                    <td>
                      <el-button link type="primary" @click="router.push(reportRoute(report))">
                        查看详情
                      </el-button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="scroll-hint">表格可左右滑动查看更多字段</p>
          </template>
        </DashboardSection>

        <DashboardSection
          title="OJ 考核"
          action-label="进入 OJ"
          action-to="/oj/assessments"
          :loading="loading"
          :min-height="340"
        >
          <DashboardEmptyState
            v-if="data.assessments.length === 0"
            title="暂无 OJ 考核"
            description="进入题库或考核列表，开始完成编程任务。"
            action-label="查看题库"
            @action="router.push('/oj/problems')"
          />
          <ul v-else class="assessment-list">
            <li
              v-for="item in data.assessments"
              :key="item.assessmentId"
              class="assessment-item"
              data-test="dashboard-assessment"
              title="查看成绩与明细"
              @click="openAssessment(item)"
            >
              <div>
                <strong>{{ item.title }}</strong>
                <span>{{ submissionLabel(item.submissionStatus) }} · {{ verdictLabel(item.technicalVerdict) }}</span>
              </div>
              <div class="assessment-result">
                <el-tag :type="assessmentStatusMeta(item.status).type" effect="plain">
                  {{ assessmentStatusMeta(item.status).label }}
                </el-tag>
                <template v-if="item.resultsPublished">
                  <strong>{{ scoreLabel(item.finalScore) }}</strong>
                  <small>最近提交 · {{ scoreSourceMeta(item.scoreSource).label }}</small>
                </template>
                <strong v-else class="assessment-pending">待公布</strong>
              </div>
            </li>
          </ul>
        </DashboardSection>
      </div>

      <div class="dashboard-grid dashboard-grid--bottom">
        <DashboardSection
          title="积分流水"
          action-label="查看完整流水"
          action-to="/points"
          :loading="loading"
          :min-height="290"
        >
          <DashboardEmptyState
            v-if="data.points.length === 0"
            title="暂无积分流水"
            description="正式提交日报或周报后，积分变动会显示在这里。"
            action-label="写日报"
            @action="router.push('/reports/daily/new')"
          />
          <ul v-else class="point-list">
            <li v-for="item in data.points" :key="item.entryId">
              <strong :class="item.delta >= 0 ? 'positive' : 'negative'">{{ item.delta >= 0 ? `+${item.delta}` : item.delta }}</strong>
              <div>
                <span>{{ item.reason ?? item.source }}</span>
                <small>{{ item.source }} · {{ formatDateTime(item.effectiveAt) }}</small>
              </div>
            </li>
          </ul>
        </DashboardSection>

        <DashboardSection
          title="排名摘要"
          subtitle="仅 ACTIVE 学生参与，名次由后端返回"
          action-label="查看排行榜"
          action-to="/rankings"
          :loading="loading"
          :min-height="290"
        >
          <div class="ranking-summary">
            <div><span>总榜</span><strong>{{ data.rankingPreview.overallRank ?? '未上榜' }}</strong><small>/ {{ data.rankingPreview.overallCount }}</small></div>
            <div><span>方向榜</span><strong>{{ data.rankingPreview.directionRank ?? '未上榜' }}</strong><small>/ {{ data.rankingPreview.directionCount }}</small></div>
            <p>{{ data.rankingPreview.eligible ? '当前参与学生积分排名' : '当前不参与积分排行榜' }}</p>
          </div>
        </DashboardSection>
      </div>
    </template>
  </div>
</template>

<style scoped>
.role-dashboard {
  max-width: 1540px;
  margin: 0 auto;
}

.dashboard-grid {
  display: grid;
  gap: 16px;
}

.dashboard-grid--main {
  margin-top: 16px;
  grid-template-columns: minmax(0, 1.35fr) minmax(340px, 0.85fr);
}

.dashboard-grid--bottom {
  margin-top: 16px;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.75fr);
}

.progress-layout {
  display: grid;
  min-height: 150px;
  align-items: center;
  grid-template-columns: 1fr 1fr 0.8fr;
  gap: 22px;
}

.progress-item > div,
.ranking-summary > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.progress-item span,
.progress-stat span,
.ranking-summary span {
  color: var(--app-muted);
  font-size: 11px;
}

.progress-item strong {
  color: var(--app-ink);
}

.progress-item :deep(.el-progress) {
  margin-top: 14px;
}

.progress-stat {
  padding: 18px;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: var(--app-surface-soft);
}

.progress-stat strong {
  display: block;
  margin-top: 7px;
  color: var(--app-brand);
  font-size: 28px;
}

.progress-stat small {
  display: block;
  margin-top: 8px;
  color: var(--app-muted);
}

.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

table {
  width: 100%;
  min-width: 620px;
  border-collapse: collapse;
}

th,
td {
  height: 46px;
  padding: 9px 11px;
  text-align: left;
  border-bottom: 1px solid var(--app-border);
}

th {
  color: var(--app-muted);
  font-size: 10px;
  background: var(--app-surface-soft);
}

td {
  color: var(--app-ink);
  font-size: 12px;
}

.scroll-hint {
  margin: 9px 0 0;
  color: var(--app-muted);
  font-size: 10px;
}

.assessment-list,
.point-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.assessment-list li,
.point-list li {
  display: flex;
  min-height: 66px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--app-border);
}

.assessment-list li:last-child,
.point-list li:last-child {
  border-bottom: 0;
}

.assessment-list li.assessment-item {
  cursor: pointer;
  transition: background 0.15s ease;
}

.assessment-list li.assessment-item:hover {
  background: var(--app-surface-soft);
}

.assessment-pending {
  color: var(--app-muted);
  font-weight: 500;
}

.assessment-list strong,
.point-list span {
  color: var(--app-ink);
  font-size: 12px;
}

.assessment-list span,
.assessment-list small,
.point-list small {
  display: block;
  margin-top: 4px;
  color: var(--app-muted);
  font-size: 10px;
}

.assessment-result {
  display: grid;
  justify-items: end;
  gap: 3px;
}

.assessment-result strong {
  font-size: 14px;
}

.point-list li {
  justify-content: flex-start;
}

.point-list > li > strong {
  width: 42px;
  font-size: 16px;
}

.positive {
  color: var(--el-color-success);
}

.negative {
  color: var(--el-color-danger);
}

.ranking-summary {
  display: grid;
  min-height: 180px;
  align-content: center;
  gap: 18px;
}

.ranking-summary > div {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--app-border);
}

.ranking-summary strong {
  margin-left: auto;
  color: var(--app-brand);
  font-size: 25px;
}

.ranking-summary small {
  color: var(--app-muted);
}

.ranking-summary p {
  margin: 0;
  color: var(--app-muted);
  font-size: 11px;
}

@media (max-width: 1080px) {
  .dashboard-grid--main,
  .dashboard-grid--bottom,
  .progress-layout {
    grid-template-columns: 1fr;
  }
}
</style>
