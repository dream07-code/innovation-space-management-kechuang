<script setup lang="ts">
/**
 * OJ 提交详情。
 *
 * 分开展示 OJ 技术判定、AI 质量评分和人工批阅；人工评分优先于 AI。
 * 不展示完整代码、私有测试数据、OJ 原始日志或 AI 原始响应。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { EditPen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import {
  getAiEvaluation,
  getAssessment,
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
  languageLabel,
  reviewStatusMeta,
  scoreLabel,
  submissionStatusMeta,
  verdictMeta,
} from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import type {
  AiEvaluation,
  Assessment,
  AssessmentProblem,
  AssessmentReview,
  AssessmentSubmission,
} from '@/api/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const submissionId = computed(() => route.params.submissionId as string)
const submission = ref<AssessmentSubmission | null>(null)
const assessment = ref<Assessment | null>(null)
const problem = ref<AssessmentProblem | null>(null)
const aiEvaluation = ref<AiEvaluation | null>(null)
const reviews = ref<AssessmentReview[]>([])
const loading = ref(false)
const submittedCode = ref('')

const canReview = computed(() => auth.isAdmin || auth.isLeader)
const activeReview = computed(() => reviews.value.find((review) => review.status === 'ACTIVE') ?? null)
const finalScore = computed(() => activeReview.value?.score ?? aiEvaluation.value?.score ?? null)
const scoreSource = computed(() =>
  activeReview.value ? '人工评分优先' : aiEvaluation.value?.score !== null && aiEvaluation.value?.score !== undefined
    ? 'AI 质量评分'
    : '待评分',
)

async function load(): Promise<void> {
  loading.value = true
  try {
    submission.value = await getAssessmentSubmission(submissionId.value)
    try {
      submittedCode.value = (await getSubmissionCode(submissionId.value)).code
    } catch {
      submittedCode.value = ''
    }
    const [currentAssessment, currentProblem] = await Promise.all([
      getAssessment(submission.value.assessmentId),
      getAssessmentProblem(submission.value.problemId),
    ])
    assessment.value = currentAssessment
    problem.value = currentProblem

    const tasks: Promise<unknown>[] = [getAiEvaluation(submissionId.value).then((item) => {
      aiEvaluation.value = item
    }).catch((error) => {
      const apiError = toApiError(error)
      if (apiError.code !== 'NOT_FOUND') throw apiError
    })]
    if (canReview.value) {
      tasks.push(listAssessmentReviews(submissionId.value).then((page) => {
        reviews.value = page.items
      }))
    }
    await Promise.all(tasks)
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <span>提交详情 · {{ submission?.id ?? '' }}</span>
        <el-button
          v-if="canReview && submission"
          type="primary"
          :icon="EditPen"
          @click="router.push({ name: 'oj-submission-review', params: { submissionId: submission.id } })"
        >
          人工批阅
        </el-button>
      </div>
    </template>

    <template v-if="submission">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="考核">{{ assessment?.title ?? submission.assessmentId }}</el-descriptions-item>
        <el-descriptions-item label="题目">{{ problem?.title ?? submission.problemId }}</el-descriptions-item>
        <el-descriptions-item label="成员">{{ memberNameLabel(submission) }}</el-descriptions-item>
        <el-descriptions-item label="语言">{{ languageLabel(submission.language) }}</el-descriptions-item>
        <el-descriptions-item label="提交状态">
          <el-tag :type="submissionStatusMeta(submission.status).type" effect="plain">
            {{ submissionStatusMeta(submission.status).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ submission.submittedAt }}</el-descriptions-item>
        <el-descriptions-item label="代码摘要">{{ submission.codeHash ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="判定时间">{{ submission.judgedAt ?? '—' }}</el-descriptions-item>
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

      <el-divider content-position="left">OJ 技术判定</el-divider>
      <el-descriptions v-if="submission.judgeResult" :column="3" border>
        <el-descriptions-item label="判定">
          <el-tag :type="verdictMeta(submission.judgeResult.verdict).type" effect="plain">
            {{ verdictMeta(submission.judgeResult.verdict).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="通过用例">
          {{ submission.judgeResult.passedTestCases }} / {{ submission.judgeResult.totalTestCases }}
        </el-descriptions-item>
        <el-descriptions-item label="资源">
          {{ submission.judgeResult.timeMs }} ms / {{ Math.round(submission.judgeResult.memoryKb / 1024) }} MB
        </el-descriptions-item>
        <el-descriptions-item v-if="submission.judgeResult.safeError" label="安全错误分类" :span="3">
          {{ submission.judgeResult.safeError }}
        </el-descriptions-item>
      </el-descriptions>
      <template v-if="submission.judgeResult?.testCaseResults?.length">
        <div class="test-case-heading">逐测试点结果</div>
        <el-table :data="submission.judgeResult.testCaseResults" max-height="360" class="test-case-table">
          <el-table-column prop="position" label="#" width="60" />
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag :type="verdictMeta(row.verdict).type" effect="plain">
                {{ verdictMeta(row.verdict).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ row.timeMs }} ms</template>
          </el-table-column>
          <el-table-column label="内存" width="110">
            <template #default="{ row }">{{ Math.round(row.memoryKb / 1024) }} MB</template>
          </el-table-column>
          <el-table-column label="安全错误" min-width="180">
            <template #default="{ row }">{{ row.safeError ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else description="OJ 尚未返回技术判定" />

      <el-divider content-position="left">AI 质量评分</el-divider>
      <el-descriptions v-if="aiEvaluation" :column="2" border>
        <el-descriptions-item label="状态">
          <el-tag :type="aiStatusMeta(aiEvaluation.status).type" effect="plain">
            {{ aiStatusMeta(aiEvaluation.status).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="评分">{{ scoreLabel(aiEvaluation.score) }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ aiEvaluation.model ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="Rubric">{{ aiEvaluation.rubricVersion }}</el-descriptions-item>
        <el-descriptions-item v-if="aiEvaluation.reason" label="评分理由" :span="2">
          {{ aiEvaluation.reason }}
        </el-descriptions-item>
      </el-descriptions>
      <el-empty v-else description="暂无 AI 评分记录" />

      <el-divider content-position="left">人工批阅与最终展示</el-divider>
      <el-descriptions v-if="activeReview" :column="2" border>
        <el-descriptions-item label="人工评分">{{ activeReview.score }} 分</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="reviewStatusMeta(activeReview.status).type" effect="plain">
            {{ reviewStatusMeta(activeReview.status).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="批阅意见" :span="2">{{ activeReview.comment }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        description="尚未人工批阅；无有效人工评分时才使用 AI 质量评分。"
      />

      <el-result
        class="final-result"
        :title="`${scoreLabel(finalScore)} · ${scoreSource}`"
        sub-title="OJ 技术判定独立展示；本结果不写入现有积分流水或排行榜。"
      />

      <template v-if="canReview && reviews.length > 0">
        <el-divider content-position="left">批阅历史</el-divider>
        <el-table :data="reviews" row-key="id">
          <el-table-column prop="createdAt" label="时间" width="180" />
          <el-table-column label="批阅人" width="140">
            <template #default="{ row }">{{ row.reviewerName || row.reviewerId }}</template>
          </el-table-column>
          <el-table-column prop="score" label="分数" width="80" />
          <el-table-column prop="comment" label="意见" min-width="240" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="reviewStatusMeta(row.status).type" effect="plain">
                {{ reviewStatusMeta(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="80" />
        </el-table>
      </template>
    </template>
  </el-card>
</template>

<style scoped>
.test-case-heading {
  margin-top: 14px;
  font-weight: 600;
}

.test-case-table {
  margin-top: 10px;
}
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.final-result {
  padding: 12px 0 0;
}
</style>
