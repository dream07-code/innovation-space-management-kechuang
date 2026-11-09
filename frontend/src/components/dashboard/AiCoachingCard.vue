<script setup lang="ts">
/**
 * 首页「AI 批改与辅导」卡片。
 *
 * 只展示真实数据：最近一次已判定的提交，以及该提交的批改报告（错因、推荐练习）。
 * 没有提交时给空状态，AI 不可用时说明原因，都不编造批改内容。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChatDotRound, MagicStick, Right } from '@element-plus/icons-vue'

import { getSubmissionReport, listAssessmentSubmissions } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { difficultyMetaLabel, verdictMeta } from '@/components/ojMeta'
import SubmissionTutorDrawer from '@/components/SubmissionTutorDrawer.vue'
import { formatDateTime } from '@/utils/datetime'
import type { AssessmentSubmission, SubmissionReport } from '@/api/types'

const router = useRouter()

const loading = ref(true)
const reportLoading = ref(false)
const submission = ref<AssessmentSubmission | null>(null)
const report = ref<SubmissionReport | null>(null)
const reportError = ref('')
const tutorOpen = ref(false)

const ai = computed(() => report.value?.ai ?? null)
const recommendation = computed(() => report.value?.recommendation ?? null)
const verdict = computed(() => submission.value?.judgeResult?.verdict ?? null)
const problemTitle = computed(
  () => report.value?.problem.title ?? `题目 #${submission.value?.problemId ?? ''}`,
)

/** 错因摘要控制在两行以内，卡片不因长文本被撑开。 */
const causeSummary = computed(() => {
  const cause = ai.value?.cause?.trim() ?? ''
  if (!cause) return ''
  return cause.length > 90 ? `${cause.slice(0, 90)}…` : cause
})

const nextProblem = computed(() => recommendation.value?.problem ?? null)

function openReport(): void {
  if (!submission.value) return
  void router.push({
    name: 'oj-submission-report',
    params: { submissionId: submission.value.id },
  })
}

function goOj(): void {
  void router.push('/oj/assessments')
}

function goProblem(problemId: string): void {
  tutorOpen.value = false
  void router.push({ name: 'oj-problem-detail', params: { problemId } })
}

async function load(): Promise<void> {
  loading.value = true
  reportError.value = ''
  try {
    // 学生角色由服务端强制只返回本人提交，这里取最近的一条已判定记录。
    const page = await listAssessmentSubmissions({ status: 'JUDGED' })
    submission.value = page.items[0] ?? null
    if (!submission.value) return

    reportLoading.value = true
    try {
      report.value = await getSubmissionReport(submission.value.id)
    } catch (error) {
      reportError.value = describeError(toApiError(error))
    } finally {
      reportLoading.value = false
    }
  } catch (error) {
    reportError.value = describeError(toApiError(error))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="ai-coaching" :aria-busy="loading || reportLoading ? 'true' : 'false'">
    <header class="ai-coaching__head">
      <span class="ai-coaching__badge">
        <el-icon><MagicStick /></el-icon>
        AI 批改与辅导
      </span>
      <span v-if="submission" class="ai-coaching__meta">
        最近一次 · {{ formatDateTime(submission.submittedAt) }}
      </span>
    </header>

    <div v-if="loading" class="ai-coaching__loading">
      <el-skeleton :rows="2" animated />
    </div>

    <div v-else-if="!submission" class="ai-coaching__empty">
      <div>
        <strong>还没有 AI 批改记录</strong>
        <p>完成一次 OJ 提交，判题结束后这里会显示错因摘要和推荐练习。</p>
      </div>
      <el-button type="primary" @click="goOj">去做题</el-button>
    </div>

    <div v-else class="ai-coaching__body">
      <div
        class="ai-coaching__main"
        role="button"
        tabindex="0"
        title="查看完整批改报告"
        @click="openReport"
        @keydown.enter.prevent="openReport"
      >
        <div class="ai-coaching__row">
          <strong class="ai-coaching__problem">{{ problemTitle }}</strong>
          <el-tag v-if="verdict" :type="verdictMeta(verdict).type" effect="plain" size="small">
            {{ verdictMeta(verdict).label }}
          </el-tag>
        </div>

        <p v-if="causeSummary" class="ai-coaching__cause">{{ causeSummary }}</p>
        <p v-else-if="reportLoading" class="ai-coaching__cause ai-coaching__cause--muted">
          正在读取这次提交的 AI 批改…
        </p>
        <p v-else-if="ai && !ai.available" class="ai-coaching__cause ai-coaching__cause--muted">
          AI 批改暂不可用：{{ ai.error ?? '未获取到 AI 结论。' }}
        </p>
        <p v-else-if="reportError" class="ai-coaching__cause ai-coaching__cause--muted">
          {{ reportError }}
        </p>

        <div v-if="nextProblem" class="ai-coaching__next">
          <span>推荐练习</span>
          <strong>{{ nextProblem.title }}</strong>
          <el-tag
            :type="difficultyMetaLabel(nextProblem.difficulty).type"
            effect="plain"
            size="small"
          >
            {{ difficultyMetaLabel(nextProblem.difficulty).label }}
          </el-tag>
        </div>
        <div v-else-if="recommendation && !reportLoading" class="ai-coaching__next ai-coaching__next--muted">
          <span>推荐难度 {{ difficultyMetaLabel(recommendation.targetDifficulty).label }}</span>
          <span>题库暂时没有匹配的题目</span>
        </div>
      </div>

      <div class="ai-coaching__actions">
        <el-button
          type="primary"
          :icon="ChatDotRound"
          :disabled="!submission"
          @click.stop="tutorOpen = true"
        >
          问 AI 助教
        </el-button>
        <el-button link type="primary" @click.stop="openReport">
          查看完整报告
          <el-icon class="ai-coaching__arrow"><Right /></el-icon>
        </el-button>
      </div>
    </div>

    <SubmissionTutorDrawer
      v-if="submission"
      v-model="tutorOpen"
      :submission-id="submission.id"
      :problem-title="problemTitle"
      :recommendation="recommendation"
      @open-problem="goProblem"
    />
  </section>
</template>

<style scoped>
.ai-coaching {
  margin-top: 16px;
  padding: 16px 18px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background:
    linear-gradient(120deg, rgba(64, 128, 255, 0.06), rgba(140, 90, 255, 0.05)),
    var(--app-surface);
}

.ai-coaching__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ai-coaching__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: linear-gradient(135deg, #4c7dff, #8b5cf6);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.ai-coaching__meta {
  color: var(--app-muted);
  font-size: 11px;
}

.ai-coaching__loading {
  margin-top: 14px;
}

.ai-coaching__empty {
  display: flex;
  min-height: 88px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-top: 12px;
  padding: 16px;
  border: 1px dashed var(--app-border-strong);
  border-radius: 8px;
  background: var(--app-surface-soft);
}

.ai-coaching__empty strong {
  color: var(--app-ink);
  font-size: 13px;
}

.ai-coaching__empty p {
  margin: 6px 0 0;
  color: var(--app-muted);
  font-size: 11px;
}

.ai-coaching__body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 12px;
}

.ai-coaching__main {
  min-width: 0;
  flex: 1;
  cursor: pointer;
  border-radius: 6px;
  outline: none;
}

.ai-coaching__main:focus-visible {
  box-shadow: 0 0 0 2px var(--app-brand);
}

.ai-coaching__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-coaching__problem {
  color: var(--app-ink);
  font-size: 14px;
}

.ai-coaching__cause {
  margin: 7px 0 0;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 12px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.ai-coaching__cause--muted {
  color: var(--app-muted);
}

.ai-coaching__next {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 9px;
  font-size: 12px;
}

.ai-coaching__next span {
  color: var(--app-muted);
}

.ai-coaching__next strong {
  color: var(--app-brand);
}

.ai-coaching__next--muted strong {
  color: var(--app-muted);
}

.ai-coaching__actions {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
}

.ai-coaching__arrow {
  margin-left: 2px;
}

@media (max-width: 900px) {
  .ai-coaching__body {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }

  .ai-coaching__actions {
    flex-direction: row;
    align-items: center;
  }
}
</style>
