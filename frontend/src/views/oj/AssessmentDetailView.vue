<script setup lang="ts">
/**
 * OJ 考核详情：展示基本信息与题目集合，并执行发布/关闭状态转换。
 *
 * 发布和关闭是独立写操作，必须使用 Idempotency-Key 和 version；
 * OJ 判定、AI 评分、人工评分与现有积分完全隔离。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, Edit, Lock, Promotion } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  closeAssessment,
  getAssessment,
  listAssessmentProblems,
  publishAssessment,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import {
  assessmentStatusMeta,
  difficultyMetaLabel,
  languageLabel,
  problemStatusMeta,
} from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import type { Assessment, AssessmentProblem } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const assessmentId = computed(() => route.params.assessmentId as string)
const assessment = ref<Assessment | null>(null)
const problems = ref<AssessmentProblem[]>([])
const loading = ref(false)
const actionLoading = ref(false)

const canManage = computed(() => auth.isAdmin || auth.isLeader)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [current, problemPage] = await Promise.all([
      getAssessment(assessmentId.value),
      listAssessmentProblems(),
    ])
    assessment.value = current
    problems.value = problemPage.items.filter((problem) => current.problemIds.includes(problem.id))
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function runCommand(command: 'publish' | 'close'): Promise<void> {
  const current = assessment.value
  if (!current) return
  const confirmed = await ElMessageBox.confirm(
    command === 'publish' ? '发布后学生可以开始提交，题目集合不可再删除。' : '关闭后不再接收新提交，已有结果仍可查看和批阅。',
    command === 'publish' ? '确认发布考核？' : '确认关闭考核？',
    { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  actionLoading.value = true
  try {
    const payload = { version: current.version }
    assessment.value = command === 'publish'
      ? await publishAssessment(current.id, payload, newIdempotencyKey())
      : await closeAssessment(current.id, payload, newIdempotencyKey())
    ElMessage.success(command === 'publish' ? '考核已发布' : '考核已关闭')
    await load()
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('考核状态已被他人更新，已刷新')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    actionLoading.value = false
  }
}

function goProblem(problem: AssessmentProblem): void {
  void router.push({ name: 'oj-problem-detail', params: { problemId: problem.id } })
}

function goSolve(problem: AssessmentProblem): void {
  void router.push({
    name: 'oj-submit-code',
    params: { assessmentId: assessmentId.value, problemId: problem.id },
  })
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <div>
          <span class="assessment-title">{{ assessment?.title ?? '考核详情' }}</span>
          <el-tag
            v-if="assessment"
            class="status-tag"
            :type="assessmentStatusMeta(assessment.status).type"
            effect="plain"
          >
            {{ assessmentStatusMeta(assessment.status).label }}
          </el-tag>
        </div>
        <div v-if="assessment" class="header-actions">
          <el-button
            v-if="canManage && assessment.status === 'DRAFT'"
            :icon="Edit"
            @click="router.push({ name: 'oj-assessment-edit', params: { assessmentId: assessment.id } })"
          >
            编辑
          </el-button>
          <el-button
            v-if="canManage && assessment.status === 'DRAFT'"
            type="primary"
            :icon="Promotion"
            :loading="actionLoading"
            @click="runCommand('publish')"
          >
            发布
          </el-button>
          <el-button
            v-if="canManage && assessment.status === 'PUBLISHED'"
            type="warning"
            :icon="Lock"
            :loading="actionLoading"
            @click="runCommand('close')"
          >
            关闭
          </el-button>
          <el-button :icon="DataAnalysis" @click="router.push({ name: 'oj-assessment-results', params: { assessmentId } })">
            查看结果
          </el-button>
        </div>
      </div>
    </template>

    <el-descriptions v-if="assessment" :column="2" border>
      <el-descriptions-item label="方向">{{ assessment.directionName }}</el-descriptions-item>
      <el-descriptions-item label="允许语言">
        {{ assessment.allowedLanguages.map(languageLabel).join('、') }}
      </el-descriptions-item>
      <el-descriptions-item label="开始时间">{{ assessment.startAt }}</el-descriptions-item>
      <el-descriptions-item label="截止时间">{{ assessment.endAt }}</el-descriptions-item>
      <el-descriptions-item label="说明" :span="2">{{ assessment.description }}</el-descriptions-item>
    </el-descriptions>

    <el-divider content-position="left">题目列表</el-divider>

    <el-table :data="problems" row-key="id">
      <el-table-column prop="title" label="题目" min-width="220" show-overflow-tooltip />
      <el-table-column prop="summary" label="摘要" min-width="240" show-overflow-tooltip />
      <el-table-column label="难度" width="90">
        <template #default="{ row }">
          <el-tag :type="difficultyMetaLabel(row.difficulty).type" effect="plain">
            {{ difficultyMetaLabel(row.difficulty).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="problemStatusMeta(row.status).type" effect="plain">
            {{ problemStatusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="限制" width="150">
        <template #default="{ row }">{{ row.timeLimitMs }} ms / {{ Math.round(row.memoryLimitKb / 1024) }} MB</template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button link type="primary" @click="goProblem(row)">查看</el-button>
          <el-button
            v-if="assessment?.status === 'PUBLISHED' && auth.isStudent"
            link
            type="success"
            @click="goSolve(row)"
          >
            做题
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.assessment-title {
  font-size: 18px;
  font-weight: 600;
}

.status-tag {
  margin-left: 10px;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
