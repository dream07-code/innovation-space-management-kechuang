<script setup lang="ts">
/**
 * 个人首页：身份概览、积分排名与快捷入口。
 *
 * 契约：GET /auth/me、/points/me、/rankings/me（ALL_TIME 与 MONTH）。
 * 学院管理员没有成员身份，不会到达这里。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Calendar,
  Document,
  List,
  Medal,
  Trophy,
  UserFilled,
} from '@element-plus/icons-vue'

import { getMyPoints } from '@/api/modules/points'
import { getMyRanking } from '@/api/modules/rankings'
import { describeError, toApiError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import { shouldShowPersonalRankings } from '@/views/shared/rankingVisibility'
import type { MyRanking, PointsSummary } from '@/api/types'

const auth = useAuthStore()
const directions = useDirectionsStore()
const router = useRouter()

const points = ref<PointsSummary | null>(null)
const overall = ref<MyRanking | null>(null)
const monthly = ref<MyRanking | null>(null)
const loading = ref(false)

const directionName = computed(() => directions.nameOf(auth.user?.directionId))
const showPersonalRankings = computed(() => shouldShowPersonalRankings(auth.role))
const rankLabel = (rank: number | null): string => (rank === null ? '未上榜' : `第 ${rank} 名`)

onMounted(async () => {
  loading.value = true
  try {
    await directions.ensureLoaded()
    const rankingPromise = showPersonalRankings.value
      ? Promise.all([
          getMyRanking({ period: 'ALL_TIME' }),
          getMyRanking({ period: 'MONTH' }),
        ])
      : Promise.resolve([null, null] as [MyRanking | null, MyRanking | null])

    const [p, [o, m]] = await Promise.all([getMyPoints(), rankingPromise])
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
    <section class="dashboard-hero">
      <div class="dashboard-hero__identity">
        <span class="dashboard-hero__avatar">{{ auth.displayName.slice(0, 1) }}</span>
        <div>
          <p class="dashboard-hero__eyebrow">个人科创工作台</p>
          <h2>{{ auth.displayName }}，继续保持成长节奏。</h2>
          <p>{{ directionName }} · {{ auth.profile?.studentNo ?? auth.user?.account }}</p>
        </div>
      </div>
      <button class="hero-action" type="button" @click="router.push({ name: 'daily-report-new' })">
        <el-icon><Document /></el-icon>
        <span><strong>写今日日报</strong><small>记录学习过程与收获</small></span>
      </button>
    </section>

    <section :class="{ 'overview-grid': true, 'overview-grid--personal': showPersonalRankings }" aria-label="积分与排名">
      <article class="overview-card overview-card--points">
        <div class="overview-card__icon"><el-icon><Medal /></el-icon></div>
        <div>
          <span>累计积分</span>
          <strong>{{ points?.totalPoints ?? 0 }}</strong>
          <small>{{ points?.month ?? '本月' }} 新增 {{ points?.monthlyPoints ?? 0 }} 分</small>
        </div>
      </article>
      <article v-if="showPersonalRankings" class="overview-card">
        <div class="overview-card__top"><span>总榜排名</span><el-icon><Trophy /></el-icon></div>
        <strong>{{ rankLabel(overall?.overallRank ?? null) }}</strong>
        <small>共有 {{ overall?.overallCount ?? 0 }} 人参与排名</small>
      </article>
      <article v-if="showPersonalRankings" class="overview-card">
        <div class="overview-card__top"><span>方向排名</span><el-icon><Trophy /></el-icon></div>
        <strong>{{ rankLabel(overall?.directionRank ?? null) }}</strong>
        <small>{{ directionName }} · 共 {{ overall?.directionCount ?? 0 }} 人</small>
      </article>
      <article v-if="showPersonalRankings" class="overview-card overview-card--accent">
        <div class="overview-card__top"><span>本月排名</span><el-icon><Calendar /></el-icon></div>
        <strong>{{ rankLabel(monthly?.overallRank ?? null) }}</strong>
        <small>{{ monthly?.month ?? points?.month ?? '当前月份' }}</small>
      </article>
    </section>

    <section class="dashboard-lower">
      <el-card shadow="never" class="profile-card">
        <template #header>
          <div class="section-heading">
            <div>
              <span class="section-heading__eyebrow">基本信息</span>
              <h3>我的档案</h3>
            </div>
            <el-icon><UserFilled /></el-icon>
          </div>
        </template>
        <dl class="profile-grid">
          <div><dt>姓名</dt><dd>{{ auth.displayName }}</dd></div>
          <div><dt>学号</dt><dd>{{ auth.profile?.studentNo ?? '—' }}</dd></div>
          <div><dt>方向</dt><dd>{{ directionName }}</dd></div>
          <div><dt>专业</dt><dd>{{ auth.profile?.major ?? '未填写' }}</dd></div>
          <div><dt>班级</dt><dd>{{ auth.profile?.className ?? '未填写' }}</dd></div>
          <div><dt>年级</dt><dd>{{ auth.profile?.grade ?? '未填写' }}</dd></div>
        </dl>
        <button class="text-action" type="button" @click="router.push({ name: 'profile' })">
          查看并编辑个人资料
        </button>
      </el-card>

      <el-card shadow="never" class="shortcuts-card">
        <template #header>
          <div class="section-heading">
            <div>
              <span class="section-heading__eyebrow">继续工作</span>
              <h3>快捷入口</h3>
            </div>
            <el-icon><List /></el-icon>
          </div>
        </template>
        <div class="shortcut-list">
          <button type="button" @click="router.push({ name: 'daily-reports' })">
            <el-icon><Document /></el-icon><span><strong>日报</strong><small>查看历史与草稿</small></span>
          </button>
          <button type="button" @click="router.push({ name: 'weekly-reports' })">
            <el-icon><Calendar /></el-icon><span><strong>周报</strong><small>整理每周成果</small></span>
          </button>
          <button type="button" @click="router.push({ name: 'my-points' })">
            <el-icon><Medal /></el-icon><span><strong>积分记录</strong><small>核对积分变动</small></span>
          </button>
          <button type="button" @click="router.push({ name: 'rankings' })">
            <el-icon><Trophy /></el-icon><span><strong>排行榜</strong><small>查看总榜与方向榜</small></span>
          </button>
        </div>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  max-width: 1500px;
  margin: 0 auto;
}

.dashboard-hero {
  display: flex;
  min-height: 172px;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 30px 34px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}

.dashboard-hero__identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 18px;
}

.dashboard-hero__avatar {
  display: grid;
  width: 62px;
  height: 62px;
  flex: 0 0 62px;
  place-items: center;
  color: #fff;
  font-size: 22px;
  font-weight: 800;
  border-radius: 8px;
  background: var(--app-brand);
}

.dashboard-hero__eyebrow,
.section-heading__eyebrow {
  margin: 0 0 7px;
  color: var(--app-brand);
  font-size: 10px;
  font-weight: 800;
  text-transform: uppercase;
}

.dashboard-hero h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 30px;
  line-height: 1.25;
  letter-spacing: 0;
}

.dashboard-hero__identity p:last-child {
  margin: 9px 0 0;
  color: var(--app-muted);
}

.hero-action {
  display: flex;
  min-width: 210px;
  align-items: center;
  gap: 12px;
  padding: 15px 16px;
  color: var(--app-ink);
  text-align: left;
  border: 1px solid #e5c891;
  border-radius: 7px;
  background: var(--app-accent-soft);
  cursor: pointer;
  transition:
    transform var(--app-transition),
    border-color var(--app-transition);
}

.hero-action:hover {
  border-color: var(--app-accent);
  transform: translateY(-2px);
}

.hero-action .el-icon {
  color: #9a6517;
  font-size: 21px;
}

.hero-action strong,
.hero-action small {
  display: block;
}

.hero-action small {
  margin-top: 4px;
  color: #805818;
}

.overview-grid {
  display: grid;
  margin-top: 16px;
  grid-template-columns: 1.35fr;
  gap: 14px;
}

.overview-grid--personal {
  grid-template-columns: 1.35fr repeat(3, minmax(0, 1fr));
}

.overview-card {
  min-height: 142px;
  padding: 20px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}

.overview-card--points {
  display: flex;
  align-items: center;
  gap: 16px;
  color: #fff;
  background: var(--app-brand);
  border-color: var(--app-brand);
}

.overview-card--accent {
  background: var(--app-accent-soft);
  border-color: #ead6b2;
}

.overview-card__icon {
  display: grid;
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  place-items: center;
  color: var(--app-accent);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.13);
}

.overview-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 700;
}

.overview-card--accent .overview-card__top {
  color: #805818;
}

.overview-card span,
.overview-card strong,
.overview-card small {
  display: block;
}

.overview-card > span,
.overview-card--points span {
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 700;
}

.overview-card--points span {
  color: rgba(255, 255, 255, 0.68);
}

.overview-card strong {
  margin-top: 21px;
  font-size: 22px;
  line-height: 1.15;
}

.overview-card--points strong {
  margin-top: 7px;
  font-size: 38px;
}

.overview-card small {
  margin-top: 10px;
  color: var(--app-muted);
}

.overview-card--points small {
  color: rgba(255, 255, 255, 0.64);
}

.overview-card--accent small {
  color: #805818;
}

.dashboard-lower {
  display: grid;
  margin-top: 16px;
  align-items: start;
  grid-template-columns: minmax(0, 1.35fr) minmax(330px, 0.85fr);
  gap: 16px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-heading h3 {
  margin: 0;
  font-size: 18px;
  letter-spacing: 0;
}

.section-heading > .el-icon {
  color: var(--app-brand);
  font-size: 20px;
}

.profile-grid {
  display: grid;
  margin: 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 22px 18px;
}

.profile-grid div {
  min-width: 0;
}

.profile-grid dt {
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 700;
}

.profile-grid dd {
  margin: 6px 0 0;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.text-action {
  margin-top: 24px;
  padding: 0;
  color: var(--app-brand);
  font-weight: 700;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.shortcut-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.shortcut-list button {
  display: flex;
  min-height: 78px;
  align-items: center;
  gap: 11px;
  padding: 13px;
  text-align: left;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: var(--app-surface-soft);
  cursor: pointer;
  transition:
    border-color var(--app-transition),
    transform var(--app-transition);
}

.shortcut-list button:hover {
  border-color: #9eafc2;
  transform: translateY(-2px);
}

.shortcut-list .el-icon {
  color: var(--app-brand);
  font-size: 19px;
}

.shortcut-list strong,
.shortcut-list small {
  display: block;
}

.shortcut-list small {
  margin-top: 4px;
  color: var(--app-muted);
  font-size: 10px;
}

@media (max-width: 1100px) {
  .overview-grid--personal {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .overview-card--points {
    grid-column: 1 / -1;
  }
}

@media (max-width: 800px) {
  .dashboard-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .hero-action {
    width: 100%;
  }

  .dashboard-lower {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .dashboard-hero {
    padding: 23px 20px;
  }

  .dashboard-hero__identity {
    align-items: flex-start;
  }

  .overview-grid--personal {
    grid-template-columns: 1fr;
  }

  .overview-card--points {
    grid-column: auto;
  }

  .profile-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .shortcut-list {
    grid-template-columns: 1fr;
  }
}
</style>
