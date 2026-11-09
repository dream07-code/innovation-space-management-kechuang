<script setup lang="ts">
/**
 * 学习画像面板。
 *
 * 只展示后端聚合出来的真实数字：尝试题数、通过率、薄弱知识点、推荐练习。
 * 没有任何判题完成的提交时显示空状态并引导去题库，不用 0% 加空列表假装有画像。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, TrendCharts } from '@element-plus/icons-vue'

import { getTutorLearningProfile } from '@/api/modules/tutor'
import { describeError, toApiError } from '@/api/errors'
import { difficultyMetaLabel } from '@/components/ojMeta'
import { formatDateTime } from '@/utils/datetime'
import type { TutorLearningProfile } from '@/api/types'

const router = useRouter()

const loading = ref(true)
const error = ref('')
const profile = ref<TutorLearningProfile | null>(null)

const overview = computed(() => profile.value?.overview ?? null)
/** 「平均分」只统计已发布成绩，没有就显示破折号，不按 0 分算。 */
const averageScore = computed(() =>
  overview.value?.averageScore === null || overview.value?.averageScore === undefined
    ? '—'
    : `${overview.value.averageScore} 分`,
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    profile.value = await getTutorLearningProfile()
  } catch (cause) {
    error.value = describeError(toApiError(cause))
  } finally {
    loading.value = false
  }
}

function practice(problemId: string): void {
  router.push(`/oj/problems/${problemId}/practice`)
}

onMounted(load)

defineExpose({ load })
</script>

<template>
  <div class="profile-panel">
    <div v-if="loading" class="profile-panel__loading">
      <el-skeleton :rows="6" animated />
    </div>

    <el-alert
      v-else-if="error"
      type="warning"
      :closable="false"
      show-icon
      :title="error"
      class="profile-panel__alert"
    >
      <template #default>
        <el-button text type="primary" :icon="Refresh" @click="load">重新加载</el-button>
      </template>
    </el-alert>

    <el-empty
      v-else-if="!profile?.hasData"
      description="还没有判题完成的提交，先去题库练一道，画像会自动生成"
      :image-size="72"
    >
      <el-button type="primary" @click="router.push('/oj/problems')">去题库挑一道</el-button>
      <p class="profile-panel__hint">画像只统计你已判题的提交，不使用推测数据。</p>
    </el-empty>

    <template v-else-if="profile && overview">
      <header class="profile-panel__head">
        <div class="profile-panel__title">
          <el-icon class="profile-panel__icon"><TrendCharts /></el-icon>
          <div>
            <strong>学习画像</strong>
            <span>按最近一次判题结果聚合 · {{ formatDateTime(profile.snapshotAt) }}</span>
          </div>
        </div>
        <el-button text :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </header>

      <div class="stats">
        <div class="stat">
          <span class="stat__label">尝试题目</span>
          <strong class="stat__value">{{ overview.attemptedProblems }}</strong>
          <span class="stat__unit">道</span>
        </div>
        <div class="stat">
          <span class="stat__label">通过题目</span>
          <strong class="stat__value">{{ overview.passedProblems }}</strong>
          <span class="stat__unit">道</span>
        </div>
        <div class="stat stat--rate">
          <span class="stat__label">通过率</span>
          <strong class="stat__value">{{ overview.passRate }}%</strong>
          <el-progress
            :percentage="overview.passRate"
            :stroke-width="6"
            :show-text="false"
            class="stat__bar"
          />
        </div>
        <div class="stat">
          <span class="stat__label">已发布成绩均分</span>
          <strong class="stat__value stat__value--text">{{ averageScore }}</strong>
          <span class="stat__unit">共 {{ overview.submissionCount }} 次提交</span>
        </div>
      </div>

      <section class="panel">
        <h3>薄弱知识点</h3>
        <p v-if="(profile.weakPoints ?? []).length === 0" class="panel__empty">
          已尝试的题目里没有明显薄弱的标签，继续挑战新题即可。
        </p>
        <ul v-else class="weak-list">
          <li v-for="point in profile.weakPoints ?? []" :key="point.tag" class="weak">
            <div class="weak__head">
              <strong>{{ point.tag }}</strong>
              <span>{{ point.passed }} / {{ point.attempted }} 通过</span>
            </div>
            <el-progress
              :percentage="point.passRate"
              :stroke-width="6"
              :show-text="false"
              :status="point.passRate < 50 ? 'exception' : 'warning'"
            />
          </li>
        </ul>
      </section>

      <section class="panel">
        <h3>推荐练习</h3>
        <p v-if="(profile.recommendations ?? []).length === 0" class="panel__empty">
          题库里暂时没有适合继续练的题目。
        </p>
        <ul v-else class="reco-list">
          <li v-for="item in profile.recommendations ?? []" :key="item.problemId" class="reco">
            <div class="reco__main">
              <div class="reco__line">
                <strong>{{ item.title }}</strong>
                <el-tag
                  :type="difficultyMetaLabel(item.difficulty).type"
                  effect="plain"
                  size="small"
                >
                  {{ difficultyMetaLabel(item.difficulty).label }}
                </el-tag>
                <el-tag
                  v-for="tag in item.tags ?? []"
                  :key="tag"
                  type="info"
                  effect="plain"
                  size="small"
                >
                  {{ tag }}
                </el-tag>
              </div>
              <p class="reco__reason">{{ item.reason }}</p>
            </div>
            <el-button text type="primary" @click="practice(item.problemId)">去做这道题</el-button>
          </li>
        </ul>
      </section>

      <p class="profile-panel__foot">
        画像由本人提交、判题结果与已发布成绩聚合而来，不含 AI 推断；判题完成后刷新即可更新。
      </p>
    </template>
  </div>
</template>

<style scoped>
.profile-panel {
  display: flex;
  overflow-y: auto;
  max-height: calc(100vh - 220px);
  flex-direction: column;
  gap: 16px;
  padding: 4px 2px 8px;
}

.profile-panel__loading {
  padding: 8px 4px;
}

.profile-panel__alert {
  align-items: center;
}

.profile-panel__hint {
  margin: 8px 0 0;
  color: var(--app-muted);
  font-size: 12px;
}

.profile-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.profile-panel__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.profile-panel__icon {
  color: #4c7dff;
  font-size: 20px;
}

.profile-panel__title strong {
  display: block;
  color: var(--app-ink);
  font-size: 15px;
}

.profile-panel__title span {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 11px;
}

.stats {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}

.stat__label {
  color: var(--app-muted);
  font-size: 12px;
}

.stat__value {
  color: var(--app-ink);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
}

.stat__value--text {
  font-size: 18px;
}

.stat__unit {
  color: var(--app-muted);
  font-size: 11px;
}

.stat__bar {
  margin-top: 4px;
}

.panel {
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}

.panel h3 {
  margin: 0 0 10px;
  color: var(--app-ink);
  font-size: 14px;
}

.panel__empty {
  margin: 0;
  color: var(--app-muted);
  font-size: 12px;
}

.weak-list,
.reco-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.weak + .weak {
  margin-top: 12px;
}

.weak__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 4px;
}

.weak__head strong {
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 500;
}

.weak__head span {
  color: var(--app-muted);
  font-size: 11px;
}

.reco {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--app-border);
}

.reco:first-child {
  border-top: none;
  padding-top: 0;
}

.reco__main {
  min-width: 0;
}

.reco__line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.reco__line strong {
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 500;
}

.reco__reason {
  margin: 6px 0 0;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.6;
}

.profile-panel__foot {
  margin: 0;
  color: var(--app-muted);
  font-size: 11px;
}

@media (max-width: 960px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
