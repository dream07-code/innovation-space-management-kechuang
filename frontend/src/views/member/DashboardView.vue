<script setup lang="ts">
/**
 * 个人首页。
 *
 * 契约：GET /auth/me、/points/me、/rankings/me（ALL_TIME 与 MONTH）。
 * 学院管理员没有成员身份，落地页是 /admin，不会到达这里。
 */
import { computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

import { getMyPoints } from '@/api/modules/points'
import { getMyRanking } from '@/api/modules/rankings'
import { describeError, toApiError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import { ref } from 'vue'
import type { MyRanking, PointsSummary } from '@/api/types'

const auth = useAuthStore()
const directions = useDirectionsStore()

const points = ref<PointsSummary | null>(null)
const overall = ref<MyRanking | null>(null)
const monthly = ref<MyRanking | null>(null)
const loading = ref(false)

const directionName = computed(() => directions.nameOf(auth.user?.directionId))

onMounted(async () => {
  loading.value = true
  try {
    await directions.ensureLoaded()
    const [p, o, m] = await Promise.all([
      getMyPoints(),
      getMyRanking({ period: 'ALL_TIME' }),
      getMyRanking({ period: 'MONTH' }),
    ])
    points.value = p
    overall.value = o
    monthly.value = m
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading" class="dashboard">
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>我的信息</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="姓名">{{ auth.displayName }}</el-descriptions-item>
            <el-descriptions-item label="学号">{{ auth.profile?.studentNo ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="方向">{{ directionName }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>积分</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="累计积分">{{ points?.totalPoints ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="本月积分">{{ points?.monthlyPoints ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="结算月份">{{ points?.month ?? '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="dashboard__row">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>总排名</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="名次">
              {{ overall?.overallRank ?? '未上榜' }} / {{ overall?.overallCount ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="方向排名">
              {{ overall?.directionRank ?? '未上榜' }} / {{ overall?.directionCount ?? '—' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>本月排名</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="名次">
              {{ monthly?.overallRank ?? '未上榜' }} / {{ monthly?.overallCount ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="月份">{{ monthly?.month ?? '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard__row {
  margin-top: 16px;
}
</style>
