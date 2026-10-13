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
  getPracticeTestCases,
  runPractice,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { languageLabel, verdictMeta } from '@/components/ojMeta'
import type {
  Assessment,
  AssessmentLanguage,
  AssessmentProblem,
  AssessmentProblemTestCase,
  AssessmentSubmission,
  OjPracticeRunResult,
} from '@/api/types'

const route = useRoute()
const router = useRouter()

const assessmentId = computed(() => typeof route.params.assessmentId === 'string' ? route.params.assessmentId : null)
const problemId = computed(() => route.params.problemId as string)
const assessment = ref<Assessment | null>(null)
const problem = ref<AssessmentProblem | null>(null)
const practiceTestCases = ref<AssessmentProblemTestCase[]>([])
const practiceResult = ref<OjPracticeRunResult | null>(null)
const language = ref<AssessmentLanguage>('python')
const code = ref('')
const loading = ref(false)
const submitting = ref(false)
const runningPractice = ref(false)
let idempotencyKey = crypto.randomUUID()

const isPractice = computed(() => assessmentId.value === null)

const allowedLanguages = computed<AssessmentLanguage[]>(() => {
  if (!problem.value) return []
  if (!assessment.value) return [...problem.value.allowedLanguages]
  return assessment.value.allowedLanguages.filter((item) => problem.value!.allowedLanguages.includes(item))
})

const lineCount = computed(() => (code.value ? code.value.split('\n').length : 0))

async function load(): Promise<void> {
  loading.value = true
  try {
    if (assessmentId.value) {
      const [currentAssessment, currentProblem] = await Promise.all([
        getAssessment(assessmentId.value),
        getAssessmentProblem(problemId.value),
      ])
      assessment.value = currentAssessment
      problem.value = currentProblem
    } else {
      const [currentProblem, practice] = await Promise.all([
        getAssessmentProblem(problemId.value),
        getPracticeTestCases(problemId.value),
      ])
      problem.value = currentProblem
      practiceTestCases.value = practice.practiceTestCases
    }
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

async function run(): Promise<void> {
  if (!problem.value || !code.value.trim()) {
    ElMessage.warning('请输入代码')
    return
  }
  if (code.value.length > 200000) {
    ElMessage.warning('代码长度超过 200000 字符')
    return
  }
  runningPractice.value = true
  try {
    practiceResult.value = await runPractice(
      problem.value.id,
      { language: language.value, code: code.value },
      crypto.randomUUID(),
    )
    ElMessage.success('练习判题完成')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    runningPractice.value = false
  }
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
        <el-tag v-if="isPractice" type="success" effect="plain">题库练习模式</el-tag>
        <el-tag v-else-if="assessment?.status !== 'PUBLISHED'" type="warning" effect="plain">
          当前考核不可提交
        </el-tag>
      </div>
    </template>

    <el-alert v-if="isPractice" type="success" :closable="false" show-icon class="submit-notice">
      练习只使用本题的题库练习测试点，不写入正式考核提交、结果或积分；下方会展示练习输入和期望输出。
    </el-alert>
    <el-alert v-else type="info" :closable="false" show-icon class="submit-notice">
      代码只提交给受控判题沙箱。页面不执行代码、不保存草稿，也不会展示私有测试数据或完整判题日志。
    </el-alert>

    <template v-if="isPractice">
      <el-divider content-position="left">练习测试点</el-divider>
      <el-table :data="practiceTestCases" max-height="260">
        <el-table-column prop="position" label="#" width="60" />
        <el-table-column label="输入" min-width="220">
          <template #default="{ row }"><pre class="practice-code">{{ row.input }}</pre></template>
        </el-table-column>
        <el-table-column label="期望输出" min-width="180">
          <template #default="{ row }"><pre class="practice-code">{{ row.expectedOutput }}</pre></template>
        </el-table-column>
      </el-table>
      <el-empty v-if="practiceTestCases.length === 0" description="本题暂未配置练习测试点" />
    </template>

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
            v-if="isPractice"
            data-test="practice-run"
            type="primary"
            :icon="Upload"
            :loading="runningPractice"
            @click="run"
          >
            运行练习
          </el-button>
          <el-button
            v-else
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

    <template v-if="isPractice && practiceResult">
      <el-divider content-position="left">练习运行结果</el-divider>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="判定">
          <el-tag :type="verdictMeta(practiceResult.verdict).type" effect="plain">
            {{ verdictMeta(practiceResult.verdict).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="通过用例">
          {{ practiceResult.passedTestCases }} / {{ practiceResult.totalTestCases }}
        </el-descriptions-item>
        <el-descriptions-item label="资源">
          {{ practiceResult.timeMs }} ms / {{ Math.round(practiceResult.memoryKb / 1024) }} MB
        </el-descriptions-item>
        <el-descriptions-item v-if="practiceResult.safeError" label="安全错误分类" :span="3">
          {{ practiceResult.safeError }}
        </el-descriptions-item>
      </el-descriptions>
      <el-table :data="practiceResult.testCaseResults" max-height="360" class="practice-result-table">
        <el-table-column prop="position" label="#" width="60" />
        <el-table-column label="输入" min-width="200">
          <template #default="{ row }"><pre class="practice-code">{{ row.input }}</pre></template>
        </el-table-column>
        <el-table-column label="期望输出" min-width="160">
          <template #default="{ row }"><pre class="practice-code">{{ row.expectedOutput }}</pre></template>
        </el-table-column>
        <el-table-column label="结果" width="120">
          <template #default="{ row }">
            <el-tag :type="verdictMeta(row.verdict).type" effect="plain">
              {{ verdictMeta(row.verdict).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.timeMs }} ms</template>
        </el-table-column>
        <el-table-column label="内存" width="100">
          <template #default="{ row }">{{ Math.round(row.memoryKb / 1024) }} MB</template>
        </el-table-column>
        <el-table-column label="安全错误" min-width="150">
          <template #default="{ row }">{{ row.safeError ?? '—' }}</template>
        </el-table-column>
      </el-table>
    </template>
  </el-card>
</template>

<style scoped>
.practice-code {
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
}

.practice-result-table {
  margin-top: 12px;
}
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
