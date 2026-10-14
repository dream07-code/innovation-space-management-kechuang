<script setup lang="ts">
/**
 * 方向负责人数据看板。
 *
 * 数据来自 GET /dashboard/leader，只展示授权方向摘要。
 * 排行榜预览直接使用后端 items/rank，不删除负责人行、不重算排名。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getLeaderDashboard } from '@/api/modules/dashboard'
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
import RankingPreview from '@/components/dashboard/RankingPreview.vue'
import type {
  DashboardRange,
  LeaderDashboard,
  StudentDashboardRow,
} from '@/api/types'

type SortKey = 'lastDailyAt' | 'points' | 'directionRank'

const router = useRouter()
const range = ref<DashboardRange>('7d')
const data = ref<LeaderDashboard | null>(null)
const loading = ref(false)
const error = ref('')
const updatedAt = ref<string | null>(null)
const sortKey = ref<SortKey>('lastDailyAt')
const sortDirection = ref<'asc' | 'desc'>('desc')

const overviewItems = computed<DashboardMetric[]>(() => {
  const overview = data.value?.overview
  if (!overview) return []
  return [
    { key: 'active', label: '在籍学生', value: overview.activeStudents, hint: `学生总数 ${overview.totalStudents}`, emphasis: true },
    { key: 'today', label: '今日日报', value: overview.todayDailySubmitted, hint: '本方向已提交' },
    { key: 'daily', label: '日报完成率', value: overview.dailyCompletionRate.toFixed(1), suffix: '%' },
    { key: 'weekly', label: '周报完成率', value: overview.weeklyCompletionRate.toFixed(1), suffix: '%' },
    { key: 'points', label: '方向积分', value: overview.pointsTotal, hint: '包含负责人本人积分' },
    { key: 'ai', label: '待 AI 评分', value: overview.pendingAi, hint: `失败 ${overview.failedAi}` },
    { key: 'review', label: '待人工复核', value: overview.pendingReview },
    { key: 'oj', label: '待判定 OJ', value: overview.pendingOj, emphasis: true },
  ]
})

const sortedStudents = computed<StudentDashboardRow[]>(() => {
  const rows = [...(data.value?.students ?? [])]
  const direction = sortDirection.value === 'asc' ? 1 : -1
  return rows.sort((a, b) => {
    if (sortKey.value === 'points') return (a.points - b.points) * direction
    const aValue = sortKey.value === 'lastDailyAt' ? a.lastDailyAt : a.directionRank
    const bValue = sortKey.value === 'lastDailyAt' ? b.lastDailyAt : b.directionRank
    if (aValue === null && bValue === null) return 0
    if (aValue === null) return 1
    if (bValue === null) return -1
    return aValue > bValue ? direction : aValue < bValue ? -direction : 0
  })
})

function sortStudents(key: SortKey): void {
  if (sortKey.value === key) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
    return
  }
  sortKey.value = key
  sortDirection.value = 'desc'
}

function ariaSort(key: SortKey): 'ascending' | 'descending' | 'none' {
  if (sortKey.value !== key) return 'none'
  return sortDirection.value === 'asc' ? 'ascending' : 'descending'
}

function formatDateTime(value: string | null): string {
  if (!value) return '暂无记录'
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
    data.value = await getLeaderDashboard(range.value)
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
      title="方向数据看板"
      description="查看授权方向的报告进度、学生状态、待处理事项和排行榜预览。"
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
        <DashboardSkeleton :rows="6" :height="360" />
        <DashboardSkeleton :rows="5" :height="360" />
      </div>
    </template>

    <template v-else>
      <DashboardOverviewStrip :items="overviewItems" />

      <div class="dashboard-grid dashboard-grid--main">
        <DashboardSection
          title="本方向趋势"
          subtitle="日报提交、评分完成和积分变化"
          :loading="loading"
          :min-height="390"
        >
          <DashboardTrendChart :points="data.trends" aria-label="方向负责人提交、评分与积分趋势" />
        </DashboardSection>

        <DashboardSection
          title="需要处理"
          subtitle="评分、复核、OJ 与提交风险"
          action-label="查看日报"
          action-to="/admin/reports/daily"
          :loading="loading"
          :min-height="390"
        >
          <DashboardEmptyState
            v-if="data.attention.length === 0"
            title="暂无待处理事项"
            description="当前授权方向没有需要立即处理的评分或提交风险。"
            action-label="查看学生"
            @action="router.push('/admin/members')"
          />
          <AttentionList v-else :items="data.attention" />
        </DashboardSection>
      </div>

      <DashboardSection
        title="学生状态"
        subtitle="可按最近日报、积分和方向排名排序"
        :loading="loading"
        :min-height="360"
      >
        <DashboardEmptyState
          v-if="sortedStudents.length === 0"
          title="暂无学生数据"
          description="当前授权方向还没有可展示的学生状态。"
          action-label="添加学生"
          @action="router.push('/admin/members/new')"
        />
        <template v-else>
          <div class="table-scroll" tabindex="0" aria-label="学生状态表，可横向滚动">
            <table>
              <thead>
                <tr>
                  <th>学生</th>
                  <th :aria-sort="ariaSort('lastDailyAt')">
                    <button type="button" @click="sortStudents('lastDailyAt')">最近日报</button>
                  </th>
                  <th>最近周报</th>
                  <th :aria-sort="ariaSort('points')">
                    <button type="button" @click="sortStudents('points')">积分</button>
                  </th>
                  <th :aria-sort="ariaSort('directionRank')">
                    <button type="button" @click="sortStudents('directionRank')">方向排名</button>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="student in sortedStudents" :key="student.memberId">
                  <td>
                    <strong>{{ student.displayName }}</strong>
                    <small>ID {{ student.memberId }} · {{ student.membershipStatus === 'ACTIVE' ? '在册' : '已移出' }}</small>
                  </td>
                  <td>{{ formatDateTime(student.lastDailyAt) }}</td>
                  <td>{{ formatDateTime(student.lastWeeklyAt) }}</td>
                  <td>{{ student.points }}</td>
                  <td>{{ student.directionRank ?? '未上榜' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="scroll-hint">表格可左右滑动查看更多字段</p>
        </template>
      </DashboardSection>

      <DashboardSection
        title="方向排行榜"
        subtitle="直接展示后端 rank 与 total 口径，负责人不参与排名"
        action-label="查看完整榜单"
        action-to="/rankings"
        :loading="loading"
        :min-height="280"
      >
        <DashboardEmptyState
          v-if="data.rankingPreview.length === 0"
          title="暂无排行数据"
          description="当前方向没有符合条件的 ACTIVE 学生排名。"
          action-label="查看排行榜"
          @action="router.push('/rankings')"
        />
        <RankingPreview v-else :items="data.rankingPreview" />
      </DashboardSection>
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
  grid-template-columns: minmax(0, 1.7fr) minmax(320px, 0.8fr);
}

.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

table {
  width: 100%;
  min-width: 720px;
  border-collapse: collapse;
}

th,
td {
  height: 48px;
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--app-border);
}

th {
  color: var(--app-muted);
  font-size: 10px;
  background: var(--app-surface-soft);
}

th button {
  padding: 6px 0;
  color: inherit;
  font: inherit;
  border: 0;
  background: transparent;
  cursor: pointer;
}

td {
  color: var(--app-ink);
  font-size: 12px;
}

td strong,
td small {
  display: block;
}

td small {
  margin-top: 3px;
  color: var(--app-muted);
}

.scroll-hint {
  margin: 9px 0 0;
  color: var(--app-muted);
  font-size: 10px;
}

@media (max-width: 1100px) {
  .dashboard-grid--main {
    grid-template-columns: 1fr;
  }
}
</style>
