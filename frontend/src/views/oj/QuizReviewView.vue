<script setup lang="ts">
/**
 * 填空人工阅卷（负责人/管理员）。
 *
 * 按题横向列出全部已交卷学生的作答，同一屏批完一道题再换下一道；
 * 只提交改动过的行，保存走批量接口并带当前 version 防并发覆盖。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { getAssessment } from '@/api/modules/oj'
import {
  getQuizQuestion,
  getQuizReviewSummary,
  listQuizReview,
  saveQuizReviewScores,
} from '@/api/modules/quiz'
import { describeError, toApiError } from '@/api/errors'
import type { QuizReviewFilter, QuizReviewRow, QuizReviewSummary } from '@/api/types'

interface FillQuestion {
  questionId: string
  position: number
  stem: string
  score: number
}

interface EditableRow extends QuizReviewRow {
  draftScore: number | null
  draftComment: string
}

const route = useRoute()
const router = useRouter()
const assessmentId = String(route.params.assessmentId)

const title = ref('')
const questions = ref<FillQuestion[]>([])
const questionId = ref('')
const filter = ref<QuizReviewFilter>('pending')
const page = ref(1)
const size = 50
const total = ref(0)
const rows = ref<EditableRow[]>([])
const progress = ref({ graded: 0, total: 0 })
const referenceAnswers = ref<string[]>([])
const summary = ref<QuizReviewSummary | null>(null)
const loading = ref(false)
const saving = ref(false)
const confirming = ref(false)

const selected = computed(() => questions.value.find((item) => item.questionId === questionId.value))
const pendingCount = computed(() => summary.value?.pendingItems ?? 0)
const canPublish = computed(() => summary.value?.canPublish ?? false)

function newKey(): string {
  return crypto.randomUUID()
}

async function loadQuestions(): Promise<void> {
  const assessment = await getAssessment(assessmentId)
  title.value = assessment.title
  const quizItems = (assessment.items ?? []).filter((item) => item.type === 'QUIZ')
  const found: FillQuestion[] = []
  for (const item of quizItems) {
    const question = await getQuizQuestion(item.refId)
    if (question.type === 'FILL_BLANK') {
      found.push({
        questionId: item.refId,
        position: item.position,
        stem: question.stem,
        score: item.score,
      })
    }
  }
  questions.value = found.sort((a, b) => a.position - b.position)
  questionId.value = found[0]?.questionId ?? ''
}

async function loadSummary(): Promise<void> {
  summary.value = await getQuizReviewSummary(assessmentId)
}

async function loadRows(): Promise<void> {
  if (!questionId.value) return
  loading.value = true
  try {
    const data = await listQuizReview(assessmentId, {
      questionId: questionId.value,
      filter: filter.value,
      page: page.value,
      size,
    })
    rows.value = data.items.map((row) => ({
      ...row,
      draftScore: row.awarded,
      draftComment: row.comment ?? '',
    }))
    progress.value = data.progress
    referenceAnswers.value = data.question.referenceAnswers ?? []
    total.value = data.total
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function isDirty(row: EditableRow): boolean {
  const scoreChanged = row.draftScore !== null && row.draftScore !== row.awarded
  const commentChanged = row.draftComment !== (row.comment ?? '')
  return row.answered && (scoreChanged || commentChanged)
}

async function save(): Promise<void> {
  const items = rows.value.filter(isDirty).map((row) => ({
    sheetId: row.sheetId,
    position: selected.value?.position ?? 0,
    score: row.draftScore ?? 0,
    comment: row.draftComment,
    version: row.version ?? 1,
  }))
  if (!items.length) {
    ElMessage.info('没有需要保存的改动')
    return
  }
  saving.value = true
  try {
    await saveQuizReviewScores(assessmentId, { items }, newKey())
    ElMessage.success(`已保存 ${items.length} 条评分`)
    await Promise.all([loadRows(), loadSummary()])
  } catch (error) {
    const apiError = toApiError(error)
    ElMessage.error(
      apiError.code === 'VERSION_CONFLICT'
        ? '该分数已被其他老师修改，请刷新后重试'
        : describeError(apiError),
    )
  } finally {
    saving.value = false
  }
}

/** 自动判分正确的行可以一键确认，自动 0 分绝不批量确认。 */
async function confirmAutoScored(): Promise<void> {
  if (!selected.value) return
  confirming.value = true
  try {
    const target: Array<{ sheetId: string; position: number; score: number; comment: string; version: number }> = []
    let current = 1
    while (true) {
      const data = await listQuizReview(assessmentId, {
        questionId: questionId.value,
        filter: 'all',
        page: current,
        size: 200,
      })
      for (const row of data.items) {
        if (row.answered && row.manualScore === null && row.autoAwarded > 0 && row.version !== null) {
          target.push({
            sheetId: row.sheetId,
            position: selected.value.position,
            score: row.autoAwarded,
            comment: '',
            version: row.version,
          })
        }
      }
      if (current * 200 >= data.total) break
      current += 1
    }
    if (!target.length) {
      ElMessage.info('没有可确认的自动判分')
      return
    }
    for (let index = 0; index < target.length; index += 200) {
      await saveQuizReviewScores(
        assessmentId,
        { items: target.slice(index, index + 200) },
        newKey(),
      )
    }
    ElMessage.success(`已确认 ${target.length} 条自动判分`)
    await Promise.all([loadRows(), loadSummary()])
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    confirming.value = false
  }
}

function goResults(): void {
  void router.push({ name: 'oj-assessment-results', params: { assessmentId } })
}

watch([questionId, filter], () => {
  page.value = 1
  void loadRows()
})
watch(page, () => void loadRows())

onMounted(async () => {
  try {
    await loadQuestions()
    await Promise.all([loadSummary(), loadRows()])
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
})
</script>

<template>
  <div class="review-page">
    <div class="review-header">
      <div>
        <h2 class="review-title">填空阅卷</h2>
        <p class="review-subtitle">{{ title }}</p>
      </div>
      <div class="review-actions">
        <el-button :disabled="!canPublish" data-test="go-results" @click="goResults">
          去发布成绩{{ canPublish ? '' : `（还有 ${pendingCount} 条未批）` }}
        </el-button>
        <el-button type="primary" :loading="saving" data-test="save" @click="save">保存本页</el-button>
      </div>
    </div>

    <el-alert
      v-if="!questions.length"
      type="info"
      :closable="false"
      title="本场考核没有填空题，无需人工阅卷。"
    />

    <template v-else>
      <div class="review-toolbar">
        <el-select v-model="questionId" class="review-question" placeholder="选择题目">
          <el-option
            v-for="item in questions"
            :key="item.questionId"
            :label="`第 ${item.position} 题 · ${item.score} 分 · ${item.stem.slice(0, 24)}`"
            :value="item.questionId"
          />
        </el-select>
        <el-radio-group v-model="filter" data-test="filter">
          <el-radio-button value="pending">未批</el-radio-button>
          <el-radio-button value="auto_zero">自动 0 分</el-radio-button>
          <el-radio-button value="manual">已调整</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
        <span class="review-progress">
          已批 {{ progress.graded }} / {{ progress.total }}，剩余 {{ pendingCount }} 条
        </span>
        <el-button :loading="confirming" data-test="confirm-auto" @click="confirmAutoScored">
          全部确认自动分
        </el-button>
      </div>

      <p v-if="referenceAnswers.length" class="review-reference">
        评分参考：{{ referenceAnswers.join(' / ') }}
      </p>

      <el-table v-loading="loading" :data="rows" row-key="sheetId" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre v-if="row.answered" class="review-code">{{ row.answer }}</pre>
            <span v-else class="review-empty">未作答</span>
          </template>
        </el-table-column>
        <el-table-column prop="studentNo" label="学号" width="130" />
        <el-table-column prop="memberName" label="学生" width="120" />
        <el-table-column label="作答" min-width="240">
          <template #default="{ row }">
            <span v-if="!row.answered" class="review-empty">未作答</span>
            <span v-else class="review-preview">{{ (String(row.answer ?? '').split('\n')[0] ?? '').slice(0, 60) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="自动分" width="90">
          <template #default="{ row }">{{ row.answered ? row.autoAwarded : '—' }}</template>
        </el-table-column>
        <el-table-column label="得分" width="150">
          <template #default="{ row }">
            <el-input-number
              v-model="row.draftScore"
              :min="0"
              :max="selected?.score ?? 100"
              :disabled="!row.answered"
              :data-test="`score-${row.sheetId}`"
              controls-position="right"
            />
          </template>
        </el-table-column>
        <el-table-column label="评语" min-width="200">
          <template #default="{ row }">
            <el-input
              v-model="row.draftComment"
              maxlength="500"
              :disabled="!row.answered"
              :data-test="`comment-${row.sheetId}`"
              placeholder="可选"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="!row.answered" type="info" effect="plain">未作答</el-tag>
            <el-tag v-else-if="row.reviewed" type="success" effect="plain">已复核</el-tag>
            <el-tag v-else type="warning" effect="plain">待批</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
        v-model:current-page="page"
        class="review-pagination"
        :page-size="size"
        :total="total"
        layout="prev, pager, next"
      />
    </template>
  </div>
</template>

<style scoped>
.review-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.review-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.review-title {
  margin: 0;
  font-size: 20px;
}
.review-subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
}
.review-actions {
  display: flex;
  gap: 8px;
}
.review-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.review-question {
  width: 360px;
}
.review-progress {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.review-reference {
  margin: 0;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.review-code {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  white-space: pre-wrap;
}
.review-preview {
  color: var(--el-text-color-regular);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
}
.review-empty {
  color: var(--el-text-color-placeholder);
}
.review-pagination {
  justify-content: flex-end;
}
</style>
