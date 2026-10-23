<script setup lang="ts">
/**
 * 人工批阅页。
 *
 * 人工评分确认后优先于 AI 评分，迟到 AI 评分不得覆盖人工结果。
 * 请求使用 submissionVersion/reviewVersion 乐观锁并携带 Idempotency-Key。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import {
  createAssessmentReview,
  getAiEvaluation,
  getAssessmentProblem,
  getAssessmentSubmission,
  getSubmissionCode,
  listAssessmentReviews,
} from '@/api/modules/oj'
import CodeEditor from '@/components/CodeEditor.vue'
import { describeError, toApiError } from '@/api/errors'
import {
  aiStatusMeta,
  memberNameLabel,
  scoreLabel,
  submissionStatusMeta,
  verdictMeta,
} from '@/components/ojMeta'
import type {
  AiEvaluation,
  AssessmentProblem,
  AssessmentReview,
  AssessmentSubmission,
} from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const route = useRoute()
const router = useRouter()

const submissionId = computed(() => route.params.submissionId as string)
/** 从考核结果页进来时带 redirect，评完回原列表而不是跳到提交详情。 */
const redirect = computed(() => (typeof route.query.redirect === 'string' ? route.query.redirect : ''))
const submission = ref<AssessmentSubmission | null>(null)
const problem = ref<AssessmentProblem | null>(null)
const aiEvaluation = ref<AiEvaluation | null>(null)
const reviews = ref<AssessmentReview[]>([])
const submittedCode = ref('')
const loading = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  score: 80,
  comment: '',
})

const rules: FormRules = {
  score: [{ required: true, type: 'number', min: 0, max: 100, message: '评分必须是 0 到 100', trigger: 'change' }],
  comment: [{ required: true, min: 1, max: 2000, message: '请输入批阅意见', trigger: 'blur' }],
}

const activeReview = computed(() => reviews.value.find((review) => review.status === 'ACTIVE') ?? null)
const nextReviewVersion = computed(() => (activeReview.value?.version ?? 0) + 1)

async function load(): Promise<void> {
  loading.value = true
  try {
    submission.value = await getAssessmentSubmission(submissionId.value)
    try {
      submittedCode.value = (await getSubmissionCode(submissionId.value)).code
    } catch {
      submittedCode.value = ''
    }
    const [currentProblem, reviewPage, currentAi] = await Promise.all([
      getAssessmentProblem(submission.value.problemId),
      listAssessmentReviews(submissionId.value),
      getAiEvaluation(submissionId.value).catch((error) => {
        const apiError = toApiError(error)
        if (apiError.code === 'NOT_FOUND') return null
        throw apiError
      }),
    ])
    problem.value = currentProblem
    reviews.value = reviewPage.items
    aiEvaluation.value = currentAi
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !submission.value) return

  saving.value = true
  try {
    await createAssessmentReview(
      submission.value.id,
      {
        submissionVersion: submission.value.version,
        reviewVersion: nextReviewVersion.value,
        score: form.score,
        comment: form.comment.trim(),
      },
      newIdempotencyKey(),
    )
    ElMessage.success('人工评分已确认，将优先于 AI 评分')
    if (redirect.value.startsWith('/oj/')) {
      void router.replace(redirect.value)
    } else {
      void router.replace({ name: 'oj-submission-detail', params: { submissionId: submission.value.id } })
    }
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('提交或批阅版本已变化，已刷新，请重新确认')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>人工批阅 · {{ submission?.id ?? '' }}</template>

    <el-alert type="warning" :closable="false" show-icon class="review-notice">
      人工评分一旦确认即优先于 AI 评分；OJ 技术判定保持独立，不被人工分数覆盖。
    </el-alert>

    <template v-if="submission">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="题目">{{ problem?.title ?? submission.problemId }}</el-descriptions-item>
        <el-descriptions-item label="成员">{{ memberNameLabel(submission) }}</el-descriptions-item>
        <el-descriptions-item label="提交状态">
          <el-tag :type="submissionStatusMeta(submission.status).type" effect="plain">
            {{ submissionStatusMeta(submission.status).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="提交版本">v{{ submission.version }}</el-descriptions-item>
        <el-descriptions-item label="OJ 判定">
          <el-tag v-if="submission.judgeResult" :type="verdictMeta(submission.judgeResult.verdict).type" effect="plain">
            {{ verdictMeta(submission.judgeResult.verdict).label }}
          </el-tag>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="AI 评分">
          {{ aiEvaluation ? `${scoreLabel(aiEvaluation.score)}（${aiStatusMeta(aiEvaluation.status).label}）` : '暂无' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="activeReview" label="当前人工评分" :span="2">
          {{ activeReview.score }} 分 · {{ activeReview.comment }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="aiEvaluation?.status === 'FAILED'"
        type="warning"
        :closable="false"
        show-icon
        class="review-notice"
      >
        {{ aiEvaluation.reason || 'AI 评分调用失败，未产生分数' }}。可以直接人工评分，人工分优先。
      </el-alert>

      <template v-if="submittedCode">
        <el-divider content-position="left">提交代码</el-divider>
        <CodeEditor
          :model-value="submittedCode"
          :language="submission.language"
          readonly
          height="360px"
        />
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="review-form">
        <el-form-item label="人工评分（0–100）" prop="score">
          <el-slider v-model="form.score" :min="0" :max="100" show-input />
        </el-form-item>
        <el-form-item label="批阅意见" prop="comment">
          <el-input
            v-model="form.comment"
            type="textarea"
            :rows="6"
            maxlength="2000"
            show-word-limit
            placeholder="说明评分依据、改进建议或待复核事项"
          />
        </el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="saving" @click="save">
            {{ activeReview ? `确认新版本 v${nextReviewVersion}` : '确认人工评分' }}
          </el-button>
          <el-button @click="router.back()">取消</el-button>
        </div>
      </el-form>
    </template>
  </el-card>
</template>

<style scoped>
.review-notice {
  margin-bottom: 16px;
}

.review-form {
  max-width: 760px;
  margin-top: 20px;
}

.form-actions {
  display: flex;
  gap: 12px;
}
</style>
