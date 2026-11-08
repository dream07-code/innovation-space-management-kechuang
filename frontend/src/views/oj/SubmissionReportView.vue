<script setup lang="ts">
/**
 * AI 批改报告。
 *
 * 一次判题出一页报告：判题结果 → 错因解析 → 改进建议 → 推荐下一题。
 *
 * 数据全部来自真实链路：判定与失败用例来自 Judge0，题干取自题库，AI 结论来自
 * 与日报/周报评分同一个 openai-compatible 模型（Mimo）。页面不做任何本地推断，
 * 也不展示占位批改：AI 不可用时明确说明原因，其余部分照常展示。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { getSubmissionReport } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { difficultyMetaLabel, languageLabel, verdictMeta } from '@/components/ojMeta'
import type { SubmissionReport } from '@/api/types'

const route = useRoute()
const router = useRouter()

const submissionId = computed(() => route.params.submissionId as string)
const report = ref<SubmissionReport | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const judge = computed(() => report.value?.judge ?? null)
const ai = computed(() => report.value?.ai ?? null)
const recommendation = computed(() => report.value?.recommendation ?? null)
/** 规则文案与后端 NextProblemPolicy 一一对应：做对升档，做错同档补练。 */
const ruleLabel = computed(() =>
  recommendation.value?.rule === 'NEXT_DIFFICULTY' ? '本次通过 · 升一档' : '本次未通过 · 同难度补练',
)
const failedCases = computed(() => judge.value?.failedCases ?? [])
const paperQuestions = computed(() => report.value?.paperQuestions ?? [])

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    report.value = await getSubmissionReport(submissionId.value)
  } catch (error) {
    const apiError = toApiError(error)
    errorMessage.value = describeError(apiError)
    if (apiError.httpStatus !== 409) ElMessage.error(errorMessage.value)
  } finally {
    loading.value = false
  }
}

function goProblem(): void {
  const problem = recommendation.value?.problem
  if (!problem) return
  void router.push({ name: 'oj-problem-detail', params: { problemId: problem.id } })
}

onMounted(load)
</script>

<template>
  <div class="report-page">
    <el-card shadow="never">
      <template #header>
        <div class="page__header">
          <div class="page__title">
            <el-button :icon="ArrowLeft" text @click="router.back()">返回</el-button>
            <span>AI 批改报告 · 提交 {{ submissionId }}</span>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="load">重新获取</el-button>
        </div>
      </template>

      <el-alert
        v-if="errorMessage"
        type="warning"
        :closable="false"
        show-icon
        :title="errorMessage"
        description="判题尚未完成时报告不可用，请等提交状态变为「已判定」后重试。"
      />

      <template v-else-if="report">
        <div class="report-summary">
          <div class="summary-item">
            <span class="summary-item__label">考核</span>
            <span class="summary-item__value">{{ report.submission.assessmentTitle ?? '—' }}</span>
          </div>
          <div class="summary-item">
            <span class="summary-item__label">题目</span>
            <span class="summary-item__value">{{ report.problem.title }}</span>
          </div>
          <div class="summary-item">
            <span class="summary-item__label">难度</span>
            <el-tag :type="difficultyMetaLabel(report.problem.difficulty).type" effect="plain">
              {{ difficultyMetaLabel(report.problem.difficulty).label }}
            </el-tag>
          </div>
          <div class="summary-item">
            <span class="summary-item__label">语言</span>
            <span class="summary-item__value">{{ languageLabel(report.submission.language) }}</span>
          </div>
          <div class="summary-item">
            <span class="summary-item__label">判定</span>
            <el-tag v-if="judge" :type="verdictMeta(judge.verdict).type" effect="plain">
              {{ verdictMeta(judge.verdict).label }}
            </el-tag>
          </div>
          <div class="summary-item">
            <span class="summary-item__label">通过用例</span>
            <span class="summary-item__value">
              {{ judge?.passedTestCases }} / {{ judge?.totalTestCases }}
            </span>
          </div>
          <div class="summary-item">
            <span class="summary-item__label">资源</span>
            <span class="summary-item__value">
              {{ judge?.timeMs }} ms / {{ Math.round((judge?.memoryKb ?? 0) / 1024) }} MB
            </span>
          </div>
          <div class="summary-item">
            <span class="summary-item__label">提交时间</span>
            <span class="summary-item__value">{{ report.submission.submittedAt }}</span>
          </div>
        </div>

        <el-divider content-position="left">错因解析</el-divider>
        <template v-if="ai?.available && ai.cause">
          <p class="report-cause">{{ ai.cause }}</p>
          <p class="report-meta">
            由 {{ ai.model ?? 'AI 模型' }} 生成 · 提示词 {{ ai.rubricVersion }}
            <template v-if="ai.generatedAt"> · {{ ai.generatedAt }}</template>
            <template v-if="ai.cached"> · 命中缓存</template>
          </p>
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          title="AI 批改暂不可用"
          :description="ai?.error ?? '未获取到 AI 结论。'"
        />

        <el-divider content-position="left">改进建议</el-divider>
        <ol v-if="ai?.advice?.length" class="advice-list">
          <li v-for="(item, index) in ai.advice" :key="index">{{ item }}</li>
        </ol>
        <el-empty v-else description="没有可展示的建议" :image-size="64" />

        <el-divider content-position="left">推荐下一题</el-divider>
        <div class="next-card">
          <div class="next-card__head">
            <el-tag :type="recommendation?.rule === 'NEXT_DIFFICULTY' ? 'success' : 'warning'" effect="dark">
              {{ ruleLabel }}
            </el-tag>
            <span class="next-card__reason">{{ recommendation?.reason }}</span>
          </div>
          <div v-if="recommendation?.problem" class="next-card__body">
            <div>
              <div class="next-card__title">{{ recommendation.problem.title }}</div>
              <el-tag :type="difficultyMetaLabel(recommendation.problem.difficulty).type" effect="plain" size="small">
                {{ difficultyMetaLabel(recommendation.problem.difficulty).label }}
              </el-tag>
            </div>
            <el-button type="primary" plain @click="goProblem">去做这道题</el-button>
          </div>
          <el-alert v-else type="info" :closable="false" show-icon title="题库里暂时没有匹配难度的其他题目" />
        </div>

        <template v-if="failedCases.length">
          <el-divider content-position="left">失败用例</el-divider>
          <el-table :data="failedCases" size="small">
            <el-table-column prop="position" label="#" width="60" />
            <el-table-column label="判定" width="130">
              <template #default="{ row }">
                <el-tag :type="verdictMeta(row.verdict).type" effect="plain" size="small">
                  {{ verdictMeta(row.verdict).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="input" label="输入" min-width="140" show-overflow-tooltip />
            <el-table-column prop="expectedOutput" label="期望输出" min-width="140" show-overflow-tooltip />
            <el-table-column label="实际输出" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.actualOutput || '(空输出)' }}</template>
            </el-table-column>
          </el-table>
        </template>

        <template v-if="paperQuestions.length">
          <el-divider content-position="left">本场客观题题面</el-divider>
          <p class="report-meta">复用阅卷汇总接口的 questions 字段（仅管理角色可见）。</p>
          <el-table :data="paperQuestions" size="small">
            <el-table-column prop="position" label="题号" width="80" />
            <el-table-column prop="stem" label="题干" min-width="320" show-overflow-tooltip />
          </el-table>
        </template>
      </template>

      <el-skeleton v-else :rows="8" animated />
    </el-card>
  </div>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.report-summary {
  display: grid;
  gap: 12px 24px;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
}

.summary-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.summary-item__label {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.summary-item__value {
  overflow: hidden;
  color: var(--app-ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.report-cause {
  margin: 0;
  padding: 12px 14px;
  color: var(--app-ink);
  line-height: 1.7;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
}

.report-meta {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.advice-list {
  margin: 0;
  padding-left: 22px;
  line-height: 1.9;
}

.next-card {
  padding: 14px 16px;
  background: var(--el-fill-color-lighter);
  border-radius: 10px;
}

.next-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.next-card__reason {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.next-card__body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
}

.next-card__title {
  margin-bottom: 6px;
  font-weight: 600;
}
</style>
