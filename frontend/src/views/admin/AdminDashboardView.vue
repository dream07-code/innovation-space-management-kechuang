<script setup lang="ts">
/**
 * 管理首页：核心指标、待办摘要与常用操作。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Memo,
  Calendar,
  DataAnalysis,
  Document,
  Guide,
  User,
  UserFilled,
} from '@element-plus/icons-vue'

import { listMembers } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'

const router = useRouter()
const memberCount = ref(0)
const removedCount = ref(0)
const loading = ref(false)

const todayLabel = computed(() =>
  new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date()),
)

onMounted(async () => {
  loading.value = true
  try {
    const [active, removed] = await Promise.all([
      listMembers({ page: 1, size: 1, status: 'ACTIVE' }),
      listMembers({ page: 1, size: 1, status: 'REMOVED' }),
    ])
    memberCount.value = active.total
    removedCount.value = removed.total
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
      <div>
        <p class="dashboard-hero__eyebrow">管理控制台</p>
        <h2>掌握人员动态，推动科创空间有序运转。</h2>
        <p>集中查看成员状态、报告进度与审计信息，所有敏感操作均保留记录。</p>
      </div>
      <div class="dashboard-hero__date">
        <el-icon><Calendar /></el-icon>
        <span>{{ todayLabel }}</span>
      </div>
    </section>

    <section class="metric-grid" aria-label="核心指标">
      <article class="metric-card metric-card--primary">
        <div class="metric-card__top">
          <span>在册成员</span>
          <el-icon><UserFilled /></el-icon>
        </div>
        <strong>{{ memberCount }}</strong>
        <small>当前可参与日常管理的成员</small>
      </article>
      <article class="metric-card">
        <div class="metric-card__top">
          <span>已移出成员</span>
          <el-icon><User /></el-icon>
        </div>
        <strong>{{ removedCount }}</strong>
        <small>历史记录保留，可由管理员恢复</small>
      </article>
      <article class="metric-card metric-card--accent">
        <div class="metric-card__top">
          <span>科创方向</span>
          <el-icon><DataAnalysis /></el-icon>
        </div>
        <strong>7</strong>
        <small>覆盖学院主要技术与项目方向</small>
      </article>
    </section>

    <section class="dashboard-lower">
      <el-card class="quick-actions" shadow="never">
        <template #header>
          <div class="section-heading">
            <div>
              <span class="section-heading__eyebrow">快捷入口</span>
              <h3>常用操作</h3>
            </div>
            <el-icon><Guide /></el-icon>
          </div>
        </template>

        <div class="action-grid">
          <button class="action-item" type="button" @click="router.push({ name: 'admin-members' })">
            <span class="action-item__icon"><el-icon><UserFilled /></el-icon></span>
            <span><strong>人员管理</strong><small>添加、编辑、恢复成员</small></span>
          </button>
          <button class="action-item" type="button" @click="router.push({ name: 'admin-daily-reports' })">
            <span class="action-item__icon"><el-icon><Document /></el-icon></span>
            <span><strong>日报管理</strong><small>查看学习过程与评分</small></span>
          </button>
          <button class="action-item" type="button" @click="router.push({ name: 'admin-weekly-reports' })">
            <span class="action-item__icon"><el-icon><Calendar /></el-icon></span>
            <span><strong>周报管理</strong><small>跟踪每周成果和计划</small></span>
          </button>
          <button class="action-item" type="button" @click="router.push({ name: 'admin-leaders' })">
            <span class="action-item__icon"><el-icon><Guide /></el-icon></span>
            <span><strong>负责人管理</strong><small>任命与调整方向权限</small></span>
          </button>
          <button class="action-item" type="button" @click="router.push({ name: 'admin-audits' })">
            <span class="action-item__icon"><el-icon><Memo /></el-icon></span>
            <span><strong>审计记录</strong><small>核对敏感操作与原因</small></span>
          </button>
          <button class="action-item" type="button" @click="router.push({ name: 'oj-assessments' })">
            <span class="action-item__icon"><el-icon><DataAnalysis /></el-icon></span>
            <span><strong>OJ 考核</strong><small>管理题目、提交和结果</small></span>
          </button>
        </div>
      </el-card>

      <aside class="overview-panel">
        <div class="overview-panel__header">
          <span class="section-heading__eyebrow">管理提醒</span>
          <h3>保持数据清晰</h3>
        </div>
        <ul>
          <li><span>01</span><div><strong>人员状态及时更新</strong><p>移出与恢复都会留痕，并同步账号权限。</p></div></li>
          <li><span>02</span><div><strong>报告评分独立展示</strong><p>评分未完成时不会误显示为 0 分。</p></div></li>
          <li><span>03</span><div><strong>敏感操作可追溯</strong><p>审计列表仅展示脱敏摘要，不泄露隐私。</p></div></li>
        </ul>
      </aside>
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
  min-height: 190px;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  padding: 34px 36px;
  color: #eef8f5;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background: var(--app-brand);
  box-shadow: 0 18px 42px rgba(37, 99, 235, 0.18);
}

.dashboard-hero__eyebrow,
.section-heading__eyebrow {
  margin: 0 0 8px;
  color: var(--app-accent);
  font-size: 10px;
  font-weight: 800;
  text-transform: uppercase;
}

.dashboard-hero h2 {
  max-width: 720px;
  margin: 0;
  font-size: 34px;
  line-height: 1.25;
  letter-spacing: 0;
}

.dashboard-hero p:not(.dashboard-hero__eyebrow) {
  max-width: 690px;
  margin: 13px 0 0;
  color: rgba(238, 248, 245, 0.66);
  line-height: 1.75;
}

.dashboard-hero__date {
  display: flex;
  min-width: 190px;
  align-items: center;
  gap: 9px;
  padding: 12px 14px;
  color: rgba(238, 248, 245, 0.76);
  font-size: 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.055);
}

.metric-grid {
  display: grid;
  margin-top: 18px;
  grid-template-columns: 1.35fr 1fr 1fr;
  gap: 16px;
}

.metric-card {
  min-height: 166px;
  padding: 22px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}

.metric-card--primary {
  color: #fff;
  background: var(--app-brand);
  border-color: var(--app-brand);
}

.metric-card--accent {
  background: var(--app-accent-soft);
  border-color: #ead6b2;
}

.metric-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 700;
}

.metric-card--primary .metric-card__top {
  color: rgba(255, 255, 255, 0.72);
}

.metric-card--accent .metric-card__top {
  color: #805818;
}

.metric-card__top .el-icon {
  font-size: 20px;
}

.metric-card strong {
  display: block;
  margin-top: 19px;
  font-size: 38px;
  line-height: 1;
}

.metric-card small {
  display: block;
  margin-top: 12px;
  color: var(--app-muted);
}

.metric-card--primary small {
  color: rgba(255, 255, 255, 0.66);
}

.metric-card--accent small {
  color: #805818;
}

.dashboard-lower {
  display: grid;
  margin-top: 18px;
  align-items: start;
  grid-template-columns: minmax(0, 1.65fr) minmax(280px, 0.75fr);
  gap: 16px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-heading h3,
.overview-panel h3 {
  margin: 0;
  color: var(--app-ink);
  font-size: 18px;
  letter-spacing: 0;
}

.section-heading > .el-icon {
  color: var(--app-brand);
  font-size: 21px;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.action-item {
  display: flex;
  min-height: 82px;
  align-items: center;
  gap: 13px;
  padding: 14px;
  text-align: left;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: var(--app-surface-soft);
  cursor: pointer;
  transition:
    border-color var(--app-transition),
    background-color var(--app-transition),
    transform var(--app-transition);
}

.action-item:hover {
  border-color: #9eafc2;
  background: #edf2f8;
  transform: translateY(-2px);
}

.action-item__icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  color: var(--app-brand);
  border-radius: 7px;
  background: var(--app-brand-soft);
}

.action-item strong,
.action-item small {
  display: block;
}

.action-item strong {
  color: var(--app-ink);
  font-size: 13px;
}

.action-item small {
  margin-top: 4px;
  color: var(--app-muted);
  font-size: 11px;
}

.overview-panel {
  padding: 23px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}

.overview-panel ul {
  margin: 22px 0 0;
  padding: 0;
  list-style: none;
}

.overview-panel li {
  display: grid;
  padding: 15px 0;
  grid-template-columns: 34px 1fr;
  gap: 10px;
  border-top: 1px solid var(--app-border);
}

.overview-panel li > span {
  color: var(--app-accent);
  font-size: 11px;
  font-weight: 800;
}

.overview-panel strong {
  font-size: 12px;
}

.overview-panel p {
  margin: 5px 0 0;
  color: var(--app-muted);
  font-size: 11px;
  line-height: 1.65;
}

@media (max-width: 1000px) {
  .metric-grid {
    grid-template-columns: 1fr 1fr;
  }

  .metric-card--primary {
    grid-column: 1 / -1;
  }

  .dashboard-lower {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .dashboard-hero {
    align-items: flex-start;
    flex-direction: column;
    padding: 26px 22px;
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }

  .metric-card--primary {
    grid-column: auto;
  }

  .action-grid {
    grid-template-columns: 1fr;
  }
}
</style>
