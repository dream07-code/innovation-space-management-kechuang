<script setup lang="ts">
/**
 * OJ 题目详情。
 *
 * 只展示公开题面、示例和资源限制；私有测试数据、测试脚本、OJ 执行参数
 * 与第三方错误均不会出现在此页面。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Edit, Promotion } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  getAssessmentProblem,
  listAssessments,
  retireAssessmentProblem,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import {
  difficultyMetaLabel,
  languageLabel,
  problemStatusMeta,
} from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import type { AssessmentProblem } from '@/api/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const problemId = computed(() => route.params.problemId as string)
const problem = ref<AssessmentProblem | null>(null)
const loading = ref(false)
const actionLoading = ref(false)

const canManage = computed(() => auth.isAdmin || auth.isLeader)

async function load(): Promise<void> {
  loading.value = true
  try {
    problem.value = await getAssessmentProblem(problemId.value)
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function startSolve(): Promise<void> {
  actionLoading.value = true
  try {
    const assessments = await listAssessments()
    const target = assessments.items.find(
      (assessment) =>
        assessment.status === 'PUBLISHED' &&
        assessment.problemIds.includes(problemId.value) &&
        (auth.isAdmin || auth.isLeader || assessment.directionId === auth.user?.directionId),
    )
    if (!target) {
      ElMessage.warning('当前没有包含该题的可提交考核')
      return
    }
    void router.push({
      name: 'oj-submit-code',
      params: { assessmentId: target.id, problemId: problemId.value },
    })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    actionLoading.value = false
  }
}

async function retire(): Promise<void> {
  if (!problem.value) return
  const confirmed = await ElMessageBox.confirm(
    '退役后题目不再用于新考核；已经发布考核中的题目不会被物理删除。',
    '确认退役题目？',
    { type: 'warning', confirmButtonText: '确认退役', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  actionLoading.value = true
  try {
    problem.value = await retireAssessmentProblem(
      problem.value.id,
      { version: problem.value.version },
      crypto.randomUUID(),
    )
    ElMessage.success('题目已退役')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('题目版本已变化，已刷新')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    actionLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <div>
          <span class="problem-title">{{ problem?.title ?? '题目详情' }}</span>
          <el-tag
            v-if="problem"
            class="status-tag"
            :type="problemStatusMeta(problem.status).type"
            effect="plain"
          >
            {{ problemStatusMeta(problem.status).label }}
          </el-tag>
        </div>
        <div v-if="problem" class="header-actions">
          <el-button
            v-if="auth.isStudent && problem.status === 'PUBLISHED'"
            type="success"
            :icon="Promotion"
            :loading="actionLoading"
            @click="startSolve"
          >
            开始提交
          </el-button>
          <el-button
            v-if="canManage"
            :icon="Edit"
            @click="router.push({ name: 'oj-problem-edit', params: { problemId: problem.id } })"
          >
            编辑
          </el-button>
          <el-button
            v-if="canManage && problem.status !== 'RETIRED'"
            type="danger"
            plain
            :loading="actionLoading"
            @click="retire"
          >
            退役
          </el-button>
        </div>
      </div>
    </template>

    <template v-if="problem">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="难度">
          <el-tag :type="difficultyMetaLabel(problem.difficulty).type" effect="plain">
            {{ difficultyMetaLabel(problem.difficulty).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="时间限制">{{ problem.timeLimitMs }} ms</el-descriptions-item>
        <el-descriptions-item label="内存限制">{{ Math.round(problem.memoryLimitKb / 1024) }} MB</el-descriptions-item>
        <el-descriptions-item label="允许语言" :span="2">
          {{ problem.allowedLanguages.map(languageLabel).join('、') }}
        </el-descriptions-item>
        <el-descriptions-item label="标签">{{ problem.tags.join('、') || '—' }}</el-descriptions-item>
        <el-descriptions-item label="摘要" :span="3">{{ problem.summary }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">题面</el-divider>
      <div class="problem-text">{{ problem.description }}</div>

      <el-row :gutter="16" class="format-row">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>输入格式</template>
            <div class="problem-text">{{ problem.inputFormat }}</div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>输出格式</template>
            <div class="problem-text">{{ problem.outputFormat }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-divider content-position="left">示例</el-divider>
      <el-card v-for="(item, index) in problem.examples" :key="index" shadow="never" class="example-card">
        <template #header>示例 {{ index + 1 }}{{ item.explanation ? ` · ${item.explanation}` : '' }}</template>
        <el-row :gutter="16">
          <el-col :span="12">
            <div class="code-label">输入</div>
            <pre class="code-block">{{ item.input }}</pre>
          </el-col>
          <el-col :span="12">
            <div class="code-label">输出</div>
            <pre class="code-block">{{ item.output }}</pre>
          </el-col>
        </el-row>
      </el-card>
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

.problem-title {
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

.problem-text {
  color: var(--el-text-color-primary);
  line-height: 1.8;
  white-space: pre-wrap;
}

.format-row {
  margin-top: 16px;
}

.example-card {
  margin-bottom: 12px;
}

.code-label {
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
}

.code-block {
  min-height: 72px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
}
</style>
