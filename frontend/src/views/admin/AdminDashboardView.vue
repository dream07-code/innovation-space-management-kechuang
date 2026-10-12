<script setup lang="ts">
/**
 * 学院管理员数据看板。
 *
 * 数据来自 GET /dashboard/admin，不在前端调用多个业务接口拼装。
 * 风险列表最多展示后端返回的10条；不显示学号、联系方式、报告正文、代码或Token。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  DataAnalysis,
  Document,
  Memo,
  UserFilled,
} from '@element-plus/icons-vue'

import { getAdminDashboard } from '@/api/modules/dashboard'
import { describeError, toApiError } from '@/api/errors'
import AttentionList from '@/components/dashboard/AttentionList.vue'
import DashboardEmptyState from '@/components/dashboard/DashboardEmptyState.vue'
import DashboardErrorState from '@/components/dashboard/DashboardErrorState.vue'
import DashboardHeader from '@/components/dashboard/DashboardHeader.vue'
import DashboardOverviewStrip, {
  type DashboardMetric,
} from '@/components/dashboard/DashboardOverviewStrip.vue'
import DashboardSection from '@/components/dashboard/DashboardSection.vue'
import DashboardSkeleton from '@/components/dashboard/DashboardSkeleton.vue'
import DashboardTrendChart from '@/components/dashboard/DashboardTrendChart.vue'
import type { AdminDashboard, DashboardRange, RecentActivityItem } from '@/api/types'

const router = useRouter()
const range = ref<DashboardRange>('30d')
const data = ref<AdminDashboard | null>(null)
const loading = ref(false)
const error = ref('')
const updatedAt = ref<string | null>(null)

const overviewItems = computed<DashboardMetric[]>(() => {
  const overview = data.value?.overview
  if (!overview) return []
  return [
    { key: 'active', label: '在籍学生', value: overview.activeStudents, hint: `学生总数 ${overview.totalStudents}` , emphasis: true },
    { key: 'leaders', label: '方向负责人', value: overview.directionLeaders, hint: '负责方向授权管理' },
    { key: 'removed', label: '已移出学生', value: overview.removedStudents, hint: '历史记录仍可恢复' },
    { key: 'today', label: '今日日报', value: overview.todayDailySubmitted, hint: '已正式提交' },
    { key: 'daily', label: '日报完成率', value: overview.dailyCompletionRate.toFixed(1), suffix: '%', hint: '当前统计范围' },
    { key: 'weekly', label: '周报完成率', value: overview.weeklyCompletionRate.toFixed(1), suffix: '%', hint: '当前统计范围' },
    { key: 'pending', label: '待 AI 评分', value: overview.pendingAi, hint: `失败 ${overview.failedAi} · 待复核 ${overview.pendingReview}` },
    { key: 'oj', label: '待判定 OJ', value: overview.pendingOj, hint: '不含代码和测试数据', emphasis: true },
  ]
})

const pendingTotal = computed(() => {
  const overview = data.value?.overview
  return overview ? overview.pendingAi + overview.failedAi + overview.pendingReview + overview.pendingOj : 0
})

const maxDirectionStudents = computed(() =>
  Math.max(1, ...(data.value?.directions ?? []).map((item) => item.activeStudents)),
)

const ACTIVITY_LABELS: Record<string, string> = {
  MEMBER_CREATED: '添加成员',
  MEMBER_RESTORED: '恢复成员',
  MEMBER_REMOVED: '移出成员',
  POINTS_ADDED: '积分调整',
  LEADER_APPOINTED: '任命负责人',
  PASSWORD_RESET: '密码重置',
  ACCOUNT_CORRECTED: '账号更正',
  OJ_ASSESSMENT_PUBLISHED: '发布 OJ 考核',
  REPORT_REVIEWED: '报告复核',
}

const activityLabel = (item: RecentActivityItem): string => ACTIVITY_LABELS[item.type] ?? item.type

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await getAdminDashboard(range.value)
    updatedAt.value = new Date().toISOString()
  } catch (loadError) {
    const apiError = toApiError(loadError)
    if (apiError.isForbidden) {
      await router.replace('/admin')
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
      title="管理数据看板"
      description="查看学院规模、报告完成度、评分风险和 OJ 判定状态。"
      :range="range"
      :updated-at="updatedAt"
      :loading="loading"
      @update:range="onRangeChange"
      @refresh="load"
    />

    <DashboardErrorState v-if="error && !data" :message="error" show-back @retry="load" @back="router.push('/admin')" />

    <template v-else-if="!data">
      <DashboardSkeleton :rows="4" :height="120" />
      <div class="dashboard-grid dashboard-grid--main">
        <DashboardSkeleton :rows="6" :height="360" />
        <DashboardSkeleton :rows="5" :height="360" />
      </div>
    </template>

    <template v-else>
      <DashboardOverviewStrip :items="overviewItems" />

      <div class="dashboard-grid dashboard-grid--main">
        <DashboardSection
          title="提交与积分趋势"
          :subtitle="`最近 ${range === '7d' ? '7' : '30'} 天，图表提供数据表替代`"
          :loading="loading"
          :min-height="390"
        >
          <DashboardTrendChart :points="data.trends" aria-label="管理员提交、评分与积分趋势" />
        </DashboardSection>

        <DashboardSection
          title="需要处理"
          :subtitle="`当前共 ${pendingTotal} 项风险或待办，最多展示 10 条`"
          action-label="查看审计"
          action-to="/admin/audits"
          :loading="loading"
          :min-height="390"
        >
          <DashboardEmptyState
            v-if="data.attention.length === 0"
            title="暂无待处理事项"
            description="评分、人工复核和 OJ 判定目前没有需要立即处理的记录。"
            action-label="查看日报"
            @action="router.push('/admin/reports/daily')"
          />
          <AttentionList v-else :items="data.attention" />
        </DashboardSection>
      </div>

      <DashboardSection
        title="方向对比"
        subtitle="完成率、平均积分与待处理数量"
        :loading="loading"
        :min-height="360"
      >
        <div class="direction-layout">
          <div class="direction-bars" aria-label="各方向在籍学生数量">
            <div v-for="item in data.directions" :key="item.directionId" class="direction-bar">
              <span>{{ item.directionName }}</span>
              <div class="bar-track">
                <i :style="{ width: `${(item.activeStudents / maxDirectionStudents) * 100}%` }"></i>
              </div>
              <strong>{{ item.activeStudents }}</strong>
            </div>
          </div>

          <div class="table-scroll" tabindex="0" aria-label="方向数据表，可横向滚动">
            <table>
              <thead>
                <tr>
                  <th>方向</th>
                  <th>在籍学生</th>
                  <th>日报完成</th>
                  <th>周报完成</th>
                  <th>平均积分</th>
                  <th>待处理</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in data.directions" :key="item.directionId">
                  <td><strong>{{ item.directionName }}</strong></td>
                  <td>{{ item.activeStudents }}</td>
                  <td>{{ item.dailyCompletionRate.toFixed(1) }}%</td>
                  <td>{{ item.weeklyCompletionRate.toFixed(1) }}%</td>
                  <td>{{ item.averagePoints.toFixed(1) }}</td>
                  <td>{{ item.pendingItems }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </DashboardSection>

      <div class="dashboard-grid dashboard-grid--bottom">
        <DashboardSection
          title="最近动态"
          action-label="查看审计记录"
          action-to="/admin/audits"
          :loading="loading"
          :min-height="310"
        >
          <ul class="activity-list">
            <li v-for="(item, index) in data.recentActivity" :key="`${item.type}-${item.occurredAt}`">
              <span class="activity-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <div>
                <strong>{{ activityLabel(item) }}</strong>
                <p>{{ item.summary }}</p>
              </div>
              <time>{{ formatDateTime(item.occurredAt) }}</time>
            </li>
          </ul>
        </DashboardSection>

        <DashboardSection
          title="OJ 考核摘要"
          subtitle="仅展示安全状态，不显示代码、测试数据或判题输出"
          action-label="进入 OJ 考核"
          action-to="/oj/assessments"
          :loading="loading"
          :min-height="310"
        >
          <div class="oj-summary">
            <div><el-icon><DataAnalysis /></el-icon><span>待判定</span><strong>{{ data.overview.pendingOj }}</strong></div>
            <div><el-icon><Memo /></el-icon><span>待人工复核</span><strong>{{ data.overview.pendingReview }}</strong></div>
            <div><el-icon><Document /></el-icon><span>评分失败</span><strong>{{ data.overview.failedAi }}</strong></div>
            <div><el-icon><UserFilled /></el-icon><span>待 AI 评分</span><strong>{{ data.overview.pendingAi }}</strong></div>
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
  grid-template-columns: minmax(0, 1.75fr) minmax(320px, 0.85fr);
}

.dashboard-grid--bottom {
  margin-top: 16px;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.75fr);
}

.direction-layout {
  display: grid;
  align-items: start;
  grid-template-columns: minmax(280px, 0.8fr) minmax(620px, 1.5fr);
  gap: 22px;
}

.direction-bars {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.direction-bar {
  display: grid;
  align-items: center;
  grid-template-columns: 112px 1fr 32px;
  gap: 10px;
  color: var(--app-muted);
  font-size: 11px;
}

.bar-track {
  height: 8px;
  overflow: hidden;
  border-radius: 4px;
  background: #e8edf5;
}

.bar-track i {
  display: block;
  height: 100%;
  background: var(--app-brand);
}

.direction-bar strong {
  color: var(--app-ink);
  text-align: right;
}

.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

table {
  width: 100%;
  min-width: 680px;
  border-collapse: collapse;
}

th,
td {
  height: 43px;
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

.activity-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.activity-list li {
  display: grid;
  min-height: 58px;
  align-items: center;
  grid-template-columns: 34px 1fr 92px;
  gap: 11px;
  border-bottom: 1px solid var(--app-border);
}

.activity-list li:last-child {
  border-bottom: 0;
}

.activity-index {
  color: var(--app-brand);
  font-size: 10px;
  font-weight: 800;
}

.activity-list strong {
  color: var(--app-ink);
  font-size: 11px;
}

.activity-list p {
  margin: 3px 0 0;
  color: var(--app-muted);
  font-size: 10px;
  line-height: 1.5;
}

.activity-list time {
  color: var(--app-muted);
  font-size: 10px;
  text-align: right;
}

.oj-summary {
  display: grid;
  min-height: 190px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.oj-summary > div {
  display: grid;
  min-height: 88px;
  align-content: center;
  padding: 14px;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: var(--app-surface-soft);
}

.oj-summary .el-icon {
  color: var(--app-brand);
  font-size: 18px;
}

.oj-summary span {
  margin-top: 7px;
  color: var(--app-muted);
  font-size: 10px;
}

.oj-summary strong {
  margin-top: 4px;
  color: var(--app-ink);
  font-size: 23px;
}

@media (max-width: 1120px) {
  .dashboard-grid--main,
  .dashboard-grid--bottom,
  .direction-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .direction-bar {
    grid-template-columns: 92px 1fr 28px;
  }

  .activity-list li {
    grid-template-columns: 28px 1fr;
  }

  .activity-list time {
    display: none;
  }
}
</style>
