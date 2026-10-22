<script setup lang="ts">
/**
 * OJ 考核结果（独立成绩单）。
 *
 * OJ 技术判定、AI 评分、人工评分分别展示；人工评分优先。
 * 结果不写入日报/周报积分流水，也不改变现有排行榜或淘汰状态。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

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
const assessmentId = computed(() => route.params.assessmentId as string)
const assessment = ref<Assessment | null>(null)
const problems = ref<AssessmentProblem[]>([])
const data = ref<PageData<AssessmentResult> | null>(null)
const loading = ref(false)
const scoreSource = ref('')

const problemNames = computed(() => new Map(problems.value.map((item) => [item.id, item.title])))
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
          <el-tag :type="scoreSourceMeta(row.scoreSource).type" effect="plain">
            {{ scoreSourceMeta(row.scoreSource).label }}
          </el-tag>
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
    </el-table>
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
