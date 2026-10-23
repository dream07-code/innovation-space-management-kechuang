<script setup lang="ts">
/**
 * 人工批阅页。
 *
 * 自动分按通过的测试点计算，人工评分可覆盖自动分（重新提交会产生新的批阅版本）。
 * 请求使用 submissionVersion/reviewVersion 乐观锁并携带 Idempotency-Key。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import {
  createAssessmentReview,
  getAssessmentProblem,
  getAssessmentSubmission,
  getSubmissionCode,
  listAssessmentReviews,
} from '@/api/modules/oj'
import CodeEditor from '@/components/CodeEditor.vue'
import { describeError, toApiError } from '@/api/errors'
import {
  memberNameLabel,
  scoreLabel,
  submissionStatusMeta,
  verdictMeta,
} from '@/components/ojMeta'
import type {
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
/** 与后端一致的自动分：round(通过数 / 总数 * 100)，判题内部错误不给分。 */
const autoScore = computed(() => {
  const judge = submission.value?.judgeResult
  if (!judge || judge.verdict === 'INTERNAL_ERROR' || judge.totalTestCases <= 0) return null
  return Math.round((judge.passedTestCases * 100) / judge.totalTestCases)
})
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
    const [currentProblem, reviewPage] = await Promise.all([
      getAssessmentProblem(submission.value.problemId),
      listAssessmentReviews(submissionId.value),
    ])
    problem.value = currentProblem
    reviews.value = reviewPage.items
    // 已有批阅时沿用原分数，否则用自动分作为打分起点。
    if (reviewPage.items.some((item) => item.status === 'ACTIVE')) {
      form.score = activeReview.value?.score ?? form.score
    } else if (autoScore.value !== null) {
      form.score = autoScore.value
    }
    if (activeReview.value?.comment) form.comment = activeReview.value.comment
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
      自动分按通过的测试点比例计算；这里确认的人工分会覆盖自动分，OJ 技术判定保持独立。
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
        <el-descriptions-item label="自动分（按测试点）">{{ scoreLabel(autoScore) }}</el-descriptions-item>
        <el-descriptions-item v-if="activeReview" label="当前人工评分" :span="2">
          {{ activeReview.score }} 分 · {{ activeReview.comment }}
        </el-descriptions-item>
      </el-descriptions>

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
