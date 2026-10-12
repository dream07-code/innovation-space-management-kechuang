<script setup lang="ts">
/**
 * OJ 代码提交页。
 *
 * 代码只提交给受控后端/OJ 沙箱，不在浏览器执行、不写 localStorage、
 * 不进入日志或审计。响应只接收 codeHash 等安全摘要，不回显完整代码。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import {
  createAssessmentSubmission,
  getAssessment,
  getAssessmentProblem,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { languageLabel } from '@/components/ojMeta'
import type {
  Assessment,
  AssessmentLanguage,
  AssessmentProblem,
  AssessmentSubmission,
} from '@/api/types'

const route = useRoute()
const router = useRouter()

const assessmentId = computed(() => route.params.assessmentId as string)
const problemId = computed(() => route.params.problemId as string)
const assessment = ref<Assessment | null>(null)
const problem = ref<AssessmentProblem | null>(null)
const language = ref<AssessmentLanguage>('python')
const code = ref('')
const loading = ref(false)
const submitting = ref(false)
let idempotencyKey = crypto.randomUUID()

const allowedLanguages = computed<AssessmentLanguage[]>(() => {
  if (!assessment.value || !problem.value) return []
  return assessment.value.allowedLanguages.filter((item) => problem.value!.allowedLanguages.includes(item))
})

const lineCount = computed(() => (code.value ? code.value.split('\n').length : 0))

async function load(): Promise<void> {
  loading.value = true
  try {
    const [currentAssessment, currentProblem] = await Promise.all([
      getAssessment(assessmentId.value),
      getAssessmentProblem(problemId.value),
    ])
    assessment.value = currentAssessment
    problem.value = currentProblem
    language.value = allowedLanguages.value[0] ?? 'python'
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function onCodeChanged(): void {
  idempotencyKey = crypto.randomUUID()
}

async function submit(): Promise<void> {
  if (!assessment.value || !problem.value) return
  if (!code.value.trim()) {
    ElMessage.warning('请输入代码')
    return
  }
  if (code.value.length > 200000) {
    ElMessage.warning('代码长度超过 200000 字符')
    return
  }

  submitting.value = true
  try {
    const submission: AssessmentSubmission = await createAssessmentSubmission(
      assessment.value.id,
      {
        problemId: problem.value.id,
        language: language.value,
        code: code.value,
      },
      idempotencyKey,
    )
    ElMessage.success('代码已提交，OJ 判定结果将以安全摘要展示')
    code.value = ''
    idempotencyKey = crypto.randomUUID()
    void router.push({ name: 'oj-submission-detail', params: { submissionId: submission.id } })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <div>
          <span class="page-title">{{ problem?.title ?? '代码提交' }}</span>
          <span v-if="assessment" class="assessment-name"> · {{ assessment.title }}</span>
        </div>
        <el-tag v-if="assessment?.status !== 'PUBLISHED'" type="warning" effect="plain">
          当前考核不可提交
        </el-tag>
      </div>
    </template>

    <el-alert type="info" :closable="false" show-icon class="submit-notice">
      代码只提交给受控判题沙箱。页面不执行代码、不保存草稿，也不会展示私有测试数据或完整判题日志。
    </el-alert>

    <el-form label-position="top">
      <el-form-item label="语言">
        <el-select v-model="language" style="width: 220px" @change="onCodeChanged">
          <el-option
            v-for="item in allowedLanguages"
            :key="item"
            :label="languageLabel(item)"
            :value="item"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="代码">
        <el-input
          v-model="code"
          type="textarea"
          :rows="22"
          class="code-editor"
          placeholder="请输入完整源代码"
          maxlength="200000"
          @input="onCodeChanged"
        />
      </el-form-item>
      <div class="editor-footer">
        <span>{{ code.length }} 字符 · {{ lineCount }} 行</span>
        <div>
          <el-button @click="router.back()">返回</el-button>
          <el-button
            type="primary"
            :icon="Upload"
            :loading="submitting"
            :disabled="assessment?.status !== 'PUBLISHED'"
            @click="submit"
          >
            提交代码
          </el-button>
        </div>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.assessment-name {
  color: var(--el-text-color-secondary);
}

.submit-notice {
  margin-bottom: 16px;
}

.code-editor :deep(textarea) {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 14px;
  line-height: 1.6;
  tab-size: 4;
}

.editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--el-text-color-secondary);
}
</style>
