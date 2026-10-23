<script setup lang="ts">
/**
 * OJ 考核结果（独立成绩单）。
 *
 * OJ 技术判定、AI 评分、人工评分分别展示；人工评分优先。
 * 结果不写入日报/周报积分流水，也不改变现有排行榜或淘汰状态。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { EditPen, View } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

import SubmissionCodeDialog from '@/components/SubmissionCodeDialog.vue'

import { getAssessment, listAssessmentProblems, listAssessmentResults } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import {
  assessmentStatusMeta,
  resultStatusMeta,
  scoreLabel,
  scoreSourceMeta,
  verdictMeta,
} from '@/components/ojMeta'
import type {
  Assessment,
  AssessmentProblem,
  AssessmentResult,
  PageData,
} from '@/api/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const assessmentId = computed(() => route.params.assessmentId as string)
const assessment = ref<Assessment | null>(null)
const problems = ref<AssessmentProblem[]>([])
const data = ref<PageData<AssessmentResult> | null>(null)
const loading = ref(false)
const scoreSource = ref('')
const codeDialogVisible = ref(false)
const codeSubmissionId = ref<string | null>(null)

function openCode(submissionId: string): void {
  codeSubmissionId.value = submissionId
  codeDialogVisible.value = true
}

/** 管理员和方向负责人可进入人工批阅，评完回到当前结果页。 */
const canReview = computed(() => auth.isAdmin || auth.isLeader)

function openReview(submissionId: string): void {
  void router.push({
    name: 'oj-submission-review',
    params: { submissionId },
    query: { redirect: route.fullPath },
  })
}

const problemNames = computed(() => new Map(problems.value.map((item) => [item.id, item.title])))
/** AI 评分调用失败的条数：这些记录来源是“暂不可用”，只能靠人工评分给出最终分。 */
const aiFailedCount = computed(
  () => items.value.filter((item) => item.scoreSource === 'UNAVAILABLE').length,
)
/** 一条失败的 AI 评分原因直接显示出来，避免只能靠悬停发现。 */
const firstAiFailure = computed(
  () => items.value.find((item) => item.scoreSource === 'UNAVAILABLE' && item.aiReason) ?? null,
)
const items = computed(() =>
  (data.value?.items ?? []).filter((item) => !scoreSource.value || item.scoreSource === scoreSource.value),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [currentAssessment, resultPage, problemPage] = await Promise.all([
      getAssessment(assessmentId.value),
      listAssessmentResults(assessmentId.value),
      listAssessmentProblems(),
    ])
    assessment.value = currentAssessment
    data.value = resultPage
    problems.value = problemPage.items
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
        <span>{{ assessment?.title ?? '考核结果' }}</span>
        <el-tag v-if="assessment" :type="assessmentStatusMeta(assessment.status).type" effect="plain">
          {{ assessmentStatusMeta(assessment.status).label }}
        </el-tag>
      </div>
    </template>

    <el-alert type="info" :closable="false" show-icon class="result-notice">
      本页是独立 OJ 考核成绩单：人工评分优先于 AI 评分，技术判定独立展示；不计入现有积分、流水或排行榜。
    </el-alert>

    <el-alert
      v-if="aiFailedCount"
      type="warning"
      :closable="false"
      show-icon
      class="result-notice"
    >
      <p class="ai-failure__text">
        有 {{ aiFailedCount }} 条提交的 AI 评分没有成功，分数来源显示为“暂不可用”，最终分只能由人工给出。
        <template v-if="canReview">点右侧「评分」查看提交代码并人工打分。</template>
        <template v-else>请联系方向负责人或学院管理员人工评分。</template>
      </p>
      <p v-if="firstAiFailure" class="ai-failure__reason">原因：{{ firstAiFailure.aiReason }}</p>
    </el-alert>

    <el-form inline class="page__filter">
      <el-form-item label="分数来源">
        <el-select v-model="scoreSource" clearable placeholder="全部" style="width: 150px">
          <el-option label="人工评分" value="HUMAN" />
          <el-option label="AI 评分" value="AI" />
          <el-option label="待评分" value="PENDING" />
          <el-option label="暂不可用" value="UNAVAILABLE" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-table :data="items" row-key="submissionId">
      <el-table-column label="成员" min-width="150">
        <template #default="{ row }">
          <div class="member-cell">
            <span class="member-cell__name">{{ row.memberName || row.memberId }}</span>
            <span v-if="row.memberStudentNo" class="member-cell__meta">{{ row.memberStudentNo }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="题目" min-width="180">
        <template #default="{ row }">{{ problemNames.get(row.problemId) ?? row.problemId }}</template>
      </el-table-column>
      <el-table-column label="OJ 判定" width="120">
        <template #default="{ row }">
          <el-tag :type="verdictMeta(row.technicalVerdict).type" effect="plain">
            {{ verdictMeta(row.technicalVerdict).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="测试" width="90">
        <template #default="{ row }">{{ row.passedTestCases }}/{{ row.totalTestCases }}</template>
      </el-table-column>
      <el-table-column label="资源" width="130">
        <template #default="{ row }">{{ row.timeMs }} ms / {{ Math.round(row.memoryKb / 1024) }} MB</template>
      </el-table-column>
      <el-table-column label="AI" width="90">
        <template #default="{ row }">{{ scoreLabel(row.aiScore) }}</template>
      </el-table-column>
      <el-table-column label="人工" width="90">
        <template #default="{ row }">{{ scoreLabel(row.humanScore) }}</template>
      </el-table-column>
      <el-table-column label="最终分" width="100">
        <template #default="{ row }">
          <strong>{{ scoreLabel(row.finalScore) }}</strong>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="110">
        <template #default="{ row }">
          <el-tooltip
            :content="row.aiReason || 'AI 评分服务调用失败，未产生分数，需人工评分'"
            placement="top"
            :disabled="row.scoreSource !== 'UNAVAILABLE'"
          >
            <el-tag :type="scoreSourceMeta(row.scoreSource).type" effect="plain">
              {{ scoreSourceMeta(row.scoreSource).label }}
            </el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="结果状态" width="110">
        <template #default="{ row }">
          <el-tag :type="resultStatusMeta(row.resultStatus).type" effect="plain">
            {{ resultStatusMeta(row.resultStatus).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="publishedAt" label="发布时间" width="180" />
      <el-table-column label="操作" :width="canReview ? 150 : 90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="openCode(row.submissionId)">
            代码
          </el-button>
          <el-button
            v-if="canReview"
            link
            type="primary"
            :icon="EditPen"
            @click="openReview(row.submissionId)"
          >
            评分
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <SubmissionCodeDialog v-model="codeDialogVisible" :submission-id="codeSubmissionId" />
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.result-notice {
  margin-bottom: 16px;
}

.page__filter {
  margin-bottom: 16px;
}

.ai-failure__text,
.ai-failure__reason {
  margin: 0;
  line-height: 1.6;
}

.ai-failure__reason {
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.85;
  word-break: break-all;
}

.member-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.member-cell__name {
  color: var(--app-ink);
  font-weight: 500;
}

.member-cell__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
