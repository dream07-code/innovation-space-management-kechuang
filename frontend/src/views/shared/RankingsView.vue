<script setup lang="ts">
/**
 * 排行榜：总榜 + 方向榜 + 月度榜。
 *
 * 契约要点：
 *   - 仅 ACTIVE 成员上榜；
 *   - 展示最小字段（displayName、directionName、points、rank），绝不含学号；
 *   - 同分并列竞赛排名（1,1,3）；
 *   - period=MONTH 只展示数据，不触发淘汰。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { getDirectionRanking, getMyRanking, getOverallRanking } from '@/api/modules/rankings'
import { describeError, toApiError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import type { MyRanking, RankingPage } from '@/api/types'

const auth = useAuthStore()
const directions = useDirectionsStore()

const activeTab = ref('overall')
const loading = ref(false)

const overall = ref<RankingPage | null>(null)
const direction = ref<RankingPage | null>(null)
const monthly = ref<RankingPage | null>(null)
const myOverall = ref<MyRanking | null>(null)
const myMonthly = ref<MyRanking | null>(null)

const page = ref(1)
const size = ref(20)

const isAdmin = computed(() => auth.isAdmin)

async function loadOverall(): Promise<void> {
  loading.value = true
  try {
    overall.value = await getOverallRanking({ page: page.value, size: size.value })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function loadDirection(): Promise<void> {
  loading.value = true
  try {
    const dirId = auth.user?.directionId
    if (!dirId) return
    direction.value = await getDirectionRanking(dirId, { page: page.value, size: size.value })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function loadMonthly(): Promise<void> {
  loading.value = true
  try {
    const now = new Date(Date.now() + 8 * 3600_000)
    const month = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`
    monthly.value = await getOverallRanking({ period: 'MONTH', month, page: page.value, size: size.value })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function loadMyRankings(): Promise<void> {
  if (isAdmin.value) return
  try {
    const [o, m] = await Promise.all([getMyRanking({ period: 'ALL_TIME' }), getMyRanking({ period: 'MONTH' })])
    myOverall.value = o
    myMonthly.value = m
  } catch {
    // 个人排名失败不阻断榜单展示。
  }
}

function onPageChange(next: number): void {
  page.value = next
  if (activeTab.value === 'overall') void loadOverall()
  else if (activeTab.value === 'direction') void loadDirection()
  else void loadMonthly()
}

function onTabChange(): void {
  page.value = 1
  if (activeTab.value === 'overall') void loadOverall()
  else if (activeTab.value === 'direction') void loadDirection()
  else void loadMonthly()
}

onMounted(async () => {
  await directions.ensureLoaded().catch(() => undefined)
  await Promise.all([loadOverall(), loadMyRankings()])
})
</script>

<template>
  <div>
    <el-row v-if="!isAdmin" :gutter="16" class="rankings__me">
      <el-col :span="12">
        <el-card shadow="never">
          <el-statistic title="我的总排名" :value="myOverall?.overallRank ?? '未上榜'" :suffix="`/ ${myOverall?.overallCount ?? 0}`" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <el-statistic title="我的本月排名" :value="myMonthly?.overallRank ?? '未上榜'" :suffix="`/ ${myMonthly?.overallCount ?? 0}`" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>排行榜</template>

      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="总榜" name="overall" />
        <el-tab-pane v-if="!isAdmin" label="方向榜" name="direction" />
        <el-tab-pane label="月度榜" name="monthly" />
      </el-tabs>

      <el-table v-loading="loading" :data="activeTab === 'overall' ? (overall?.items ?? []) : activeTab === 'direction' ? (direction?.items ?? []) : (monthly?.items ?? [])" row-key="memberId">
        <el-table-column prop="rank" label="排名" width="80" />
        <el-table-column prop="displayName" label="姓名" width="120" />
        <el-table-column prop="directionName" label="方向" width="140" />
        <el-table-column prop="points" label="积分" width="100" />
      </el-table>

      <div class="page__pagination">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="activeTab === 'overall' ? (overall?.total ?? 0) : activeTab === 'direction' ? (direction?.total ?? 0) : (monthly?.total ?? 0)"
          :current-page="page"
          :page-size="size"
          @current-change="onPageChange"
        />
      </div>

      <el-alert
        v-if="activeTab === 'monthly'"
        type="info"
        :closable="false"
        show-icon
        title="月度积分仅用于展示，不触发淘汰或移出。"
        class="rankings__tip"
      />
    </el-card>
  </div>
</template>

<style scoped>
.rankings__me {
  margin-bottom: 16px;
}

.page__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.rankings__tip {
  margin-top: 16px;
}
</style>
