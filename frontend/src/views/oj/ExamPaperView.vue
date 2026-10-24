<script setup lang="ts">
/**
 * 学生混合试卷作答页。
 *
 * 客观题连续作答（答案变化 2 秒防抖整体暂存草稿，防误关/断网），
 * 程序题条目跳转代码工作台逐题提交；交卷需确认，交卷后锁定，
 * 考核截止由服务端自动补交判分。考试中不显示任何对错反馈。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import { getAssessmentPaper, saveAnswerDraft, submitAnswerSheet } from '@/api/modules/quiz'
import { describeError, toApiError } from '@/api/errors'
import { QUIZ_TYPE_META, optionLetter } from '@/components/quizMeta'
import type { AnswerDraft, AssessmentPaper, PaperItem } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const AUTOSAVE_DELAY_MS = 2000

const route = useRoute()
const router = useRouter()
const assessmentId = computed(() => route.params.assessmentId as string)

const paper = ref<AssessmentPaper | null>(null)
const unavailable = ref(false)
const loading = ref(false)
const submitted = ref(false)
const submitting = ref(false)
const savedAt = ref('')
const answers = reactive<AnswerDraft>({})

let autosaveTimer: ReturnType<typeof setTimeout> | null = null

const locked = computed(() => submitted.value)
const quizItems = computed(() => paper.value?.items.filter((i) => i.type === 'QUIZ') ?? [])
const programItems = computed(() => paper.value?.items.filter((i) => i.type === 'PROGRAM') ?? [])

async function load(): Promise<void> {
  loading.value = true
  try {
    const found = await getAssessmentPaper(assessmentId.value)
    paper.value = found
    submitted.value = found.mySheet?.status === 'SUBMITTED'
    Object.keys(answers).forEach((key) => delete answers[key])
    const draft = found.mySheet?.answers
    if (draft) Object.assign(answers, draft)
  } catch (error) {
    unavailable.value = true
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

/** 草稿答案整体暂存：键为条目 position，值随题型为字母/数组/布尔/文本。 */
async function persistDraft(): Promise<void> {
  if (locked.value || !paper.value) return
  try {
    await saveAnswerDraft(assessmentId.value, { ...answers })
    savedAt.value = new Date().toLocaleTimeString()
  } catch (error) {
    ElMessage.warning(`草稿暂存失败：${describeError(toApiError(error))}`)
  }
}

watch(
  answers,
  () => {
    if (locked.value) return
    if (autosaveTimer) clearTimeout(autosaveTimer)
    autosaveTimer = setTimeout(() => {
      void persistDraft()
    }, AUTOSAVE_DELAY_MS)
  },
  { deep: true },
)

function stopAutosave(): void {
  if (autosaveTimer) clearTimeout(autosaveTimer)
}

async function submit(): Promise<void> {
  if (locked.value || submitting.value) return
  const confirmed = await ElMessageBox.confirm(
    '交卷后客观题答案不可再修改，系统将立即判分。程序题以最后一次提交为准。确定交卷？',
    '确认交卷',
    { type: 'warning', confirmButtonText: '交卷', cancelButtonText: '再检查一下' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return
  submitting.value = true
  try {
    stopAutosave()
    if (autosaveTimer === null) {
      // 交卷前把未落草稿的答案一并提交前暂存一次，避免丢失最近 2 秒内的改动。
      await persistDraft().catch(() => undefined)
    }
    await submitAnswerSheet(assessmentId.value, newIdempotencyKey())
    submitted.value = true
    ElMessage.success('已交卷，客观题判分完成')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    submitting.value = false
  }
}

function goSolve(item: PaperItem): void {
  void router.push({
    name: 'oj-submit-code',
    params: { assessmentId: assessmentId.value, problemId: item.refId },
  })
}

function letters(item: PaperItem): string[] {
  return (item.options ?? []).map((_, index) => optionLetter(index))
}

onMounted(load)
onBeforeUnmount(stopAutosave)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <div>
          <span class="paper-title">{{ paper?.title ?? '考试试卷' }}</span>
          <el-tag v-if="locked" class="status-tag" type="success" effect="plain">已交卷</el-tag>
          <span v-else-if="savedAt" class="saved-at">草稿 {{ savedAt }} 已自动保存</span>
        </div>
        <el-button
          data-test="submit-paper"
          type="primary"
          :loading="submitting"
          :disabled="locked || unavailable"
          @click="submit"
        >
          交卷
        </el-button>
      </div>
    </template>

    <el-alert
      v-if="unavailable"
      type="warning"
      :closable="false"
      title="试卷不可用：考核不存在、未发布或不属于你的方向"
    />

    <template v-else-if="paper">
      <el-divider content-position="left">客观题</el-divider>
      <div
        v-for="item in quizItems"
        :key="item.position"
        class="quiz-item"
        :data-test="`quiz-${item.position}`"
      >
        <div class="quiz-item__head">
          <span class="quiz-item__no">第 {{ item.position }} 题</span>
          <el-tag effect="plain" type="info">{{ QUIZ_TYPE_META[item.quizType ?? 'FILL_BLANK'].label }}</el-tag>
          <span class="quiz-item__score">{{ item.score }} 分</span>
        </div>
        <p class="quiz-item__stem">{{ item.stem }}</p>

        <el-radio-group
          v-if="item.quizType === 'SINGLE_CHOICE'"
          v-model="answers[item.position]"
          :disabled="locked"
        >
          <el-radio v-for="letter in letters(item)" :key="letter" :value="letter">{{ letter }}. {{ item.options?.[letters(item).indexOf(letter)] }}</el-radio>
        </el-radio-group>
        <el-checkbox-group
          v-else-if="item.quizType === 'MULTI_CHOICE'"
          v-model="answers[item.position]"
          :disabled="locked"
        >
          <el-checkbox v-for="letter in letters(item)" :key="letter" :value="letter">{{ letter }}. {{ item.options?.[letters(item).indexOf(letter)] }}</el-checkbox>
        </el-checkbox-group>
        <el-radio-group
          v-else-if="item.quizType === 'TRUE_FALSE'"
          v-model="answers[item.position]"
          :disabled="locked"
        >
          <el-radio-button :value="true">对</el-radio-button>
          <el-radio-button :value="false">错</el-radio-button>
        </el-radio-group>
        <el-input
          v-else
          v-model="answers[item.position]"
          :data-test="`fill-${item.position}`"
          :disabled="locked"
          placeholder="填写答案，忽略首尾空格、区分大小写"
          style="max-width: 360px"
        />
      </div>

      <template v-if="programItems.length">
        <el-divider content-position="left">程序设计题</el-divider>
        <div v-for="item in programItems" :key="item.position" class="program-item">
          <div class="program-item__info">
            <span class="quiz-item__no">第 {{ item.position }} 题</span>
            <span class="program-item__title">{{ item.problemTitle }}</span>
            <el-tag effect="plain" type="info">{{ item.score }} 分</el-tag>
          </div>
          <el-button
            :data-test="`solve-${item.position}`"
            type="primary"
            :disabled="locked"
            @click="goSolve(item)"
          >
            {{ locked ? '已结束' : '去作答' }}
          </el-button>
        </div>
      </template>
    </template>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.paper-title {
  font-size: 18px;
  font-weight: 600;
}

.status-tag {
  margin-left: 10px;
}

.saved-at {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.quiz-item {
  padding: 12px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.quiz-item__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.quiz-item__no {
  font-weight: 600;
}

.quiz-item__score {
  color: var(--el-text-color-secondary);
}

.quiz-item__stem {
  margin: 8px 0;
  white-space: pre-wrap;
  line-height: 1.7;
}

.program-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.program-item__info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.program-item__title {
  font-weight: 600;
}
</style>
