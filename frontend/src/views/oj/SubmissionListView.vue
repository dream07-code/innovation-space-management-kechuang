<script setup lang="ts">
/**
 * OJ 提交记录。
 *
 * 学生只看到本人提交，方向负责人只看到所辖方向，管理员可看全量。
 * 列表只展示 codeHash 安全摘要，不回显代码正文。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import {
  listAssessmentProblems,
  listAssessments,
  listAssessmentSubmissions,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import {
  languageLabel,
  submissionStatusMeta,
  verdictMeta,
} from '@/components/ojMeta'
import type {
  Assessment,
  AssessmentProblem,
  AssessmentSubmission,
  AssessmentSubmissionStatus,
  PageData,
} from '@/api/types'

const router = useRouter()

const data = ref<PageData<AssessmentSubmission> | null>(null)
const assessments = ref<Assessment[]>([])
const problems = ref<AssessmentProblem[]>([])
const loading = ref(false)
const status = ref<AssessmentSubmissionStatus | ''>('')
const language = ref('')

const assessmentNames = computed(() => new Map(assessments.value.map((item) => [item.id, item.title])))
const problemNames = computed(() => new Map(problems.value.map((item) => [item.id, item.title])))
const items = computed(() =>
  (data.value?.items ?? []).filter((item) => {
    if (status.value && item.status !== status.value) return false
    return !language.value || item.language === language.value
  }),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [submissionPage, assessmentPage, problemPage] = await Promise.all([
      listAssessmentSubmissions(),
      listAssessments(),
      listAssessmentProblems(),
    ])
    data.value = submissionPage
    assessments.value = assessmentPage.items
    problems.value = problemPage.items
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function goDetail(row: AssessmentSubmission): void {
  void router.push({ name: 'oj-submission-detail', params: { submissionId: row.id } })
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>提交记录</template>

    <el-form inline class="page__filter">
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 150px">
          <el-option label="已接收" value="RECEIVED" />
          <el-option label="判定中" value="JUDGING" />
          <el-option label="已判定" value="JUDGED" />
          <el-option label="判定失败" value="FAILED" />
          <el-option label="待人工复核" value="NEEDS_REVIEW" />
        </el-select>
      </el-form-item>
      <el-form-item label="语言">
        <el-select v-model="language" clearable placeholder="全部" style="width: 140px">
          <el-option label="Java" value="java" />
          <el-option label="Python" value="python" />
          <el-option label="C++" value="cpp" />
          <el-option label="JavaScript" value="javascript" />
          <el-option label="Go" value="go" />
          <el-option label="Rust" value="rust" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" row-key="id">
      <el-table-column prop="submittedAt" label="提交时间" width="180" />
      <el-table-column prop="memberId" label="成员" width="90" />
      <el-table-column label="考核/题目" min-width="250">
        <template #default="{ row }">
          {{ assessmentNames.get(row.assessmentId) ?? row.assessmentId }} /
          {{ problemNames.get(row.problemId) ?? row.problemId }}
        </template>
      </el-table-column>
      <el-table-column label="语言" width="100">
        <template #default="{ row }">{{ languageLabel(row.language) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="submissionStatusMeta(row.status).type" effect="plain">
            {{ submissionStatusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="OJ 结果" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.judgeResult" :type="verdictMeta(row.judgeResult.verdict).type" effect="plain">
            {{ verdictMeta(row.judgeResult.verdict).label }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="通过用例" width="100">
        <template #default="{ row }">
          {{ row.judgeResult ? `${row.judgeResult.passedTestCases}/${row.judgeResult.totalTestCases}` : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="codeHash" label="代码摘要" min-width="170" show-overflow-tooltip />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="goDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.page__filter {
  margin-bottom: 16px;
}
</style>
