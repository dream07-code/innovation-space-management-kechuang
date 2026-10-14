<script setup lang="ts">
/**
 * OJ 做题工作台。
 *
 * 桌面端左侧题面、右侧代码和结果；窄屏使用“题目 / 代码 / 结果”标签。
 * 正式考核只接收安全结果摘要，题库练习额外展示独立练习测试点。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import {
  createAssessmentSubmission,
  getAssessment,
  getAssessmentProblem,
  getAssessmentSubmission,
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

const assessmentId = computed(() =>
  typeof route.params.assessmentId === 'string' ? route.params.assessmentId : null,
)
const problemId = computed(() => route.params.problemId as string)
const assessment = ref<Assessment | null>(null)
const problem = ref<AssessmentProblem | null>(null)
const practiceTestCases = ref<AssessmentProblemTestCase[]>([])
const practiceResult = ref<OjPracticeRunResult | null>(null)
const formalSubmission = ref<AssessmentSubmission | null>(null)
const language = ref<AssessmentLanguage>('python')
const code = ref('')
const loading = ref(false)
const submitting = ref(false)
const runningPractice = ref(false)
const isNarrow = ref(false)
const activePane = ref<'problem' | 'code' | 'result'>('problem')
let idempotencyKey = crypto.randomUUID()
let viewportQuery: MediaQueryList | null = null

const isPractice = computed(() => assessmentId.value === null)
const terminalStatuses = new Set(['JUDGED', 'FAILED', 'NEEDS_REVIEW'])

const allowedLanguages = computed<AssessmentLanguage[]>(() => {
  if (!problem.value) return []
  if (!assessment.value) return [...problem.value.allowedLanguages]
  return assessment.value.allowedLanguages.filter((item) =>
    problem.value!.allowedLanguages.includes(item),
  )
})

const lineCount = computed(() => (code.value ? code.value.split('\n').length : 0))

function syncViewport(): void {
  isNarrow.value =
    typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 767px)').matches
  if (isNarrow.value && activePane.value === 'result' && !hasResult.value) {
    activePane.value = 'code'
  }
}

const hasResult = computed(
  () => practiceResult.value !== null || formalSubmission.value?.judgeResult != null,
)

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
  if (isPractice.value) practiceResult.value = null
  else formalSubmission.value = null
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
    activePane.value = 'result'
    ElMessage.success('练习判题完成')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    runningPractice.value = false
  }
}

async function pollFormalSubmission(submissionId: string): Promise<void> {
  for (let attempt = 0; attempt < 30; attempt++) {
    formalSubmission.value = await getAssessmentSubmission(submissionId)
    if (terminalStatuses.has(formalSubmission.value.status)) return
    await new Promise((resolve) => setTimeout(resolve, 200))
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
    formalSubmission.value = submission
    await pollFormalSubmission(submission.id)
    code.value = ''
    idempotencyKey = crypto.randomUUID()
    activePane.value = 'result'
    ElMessage.success('代码已提交，结果已更新')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    submitting.value = false
  }
}

function goSubmissionDetail(): void {
  if (formalSubmission.value) {
    void router.push({
      name: 'oj-submission-detail',
      params: { submissionId: formalSubmission.value.id },
    })
  }
}

onMounted(() => {
  if (typeof window.matchMedia === 'function') {
    viewportQuery = window.matchMedia('(max-width: 767px)')
    viewportQuery.addEventListener?.('change', syncViewport)
  }
  syncViewport()
  void load()
})

onBeforeUnmount(() => viewportQuery?.removeEventListener?.('change', syncViewport))
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
      练习只使用本题的题库练习测试点，不写入正式考核提交、结果或积分；结果会展示练习输入和期望输出。
    </el-alert>
    <el-alert v-else type="info" :closable="false" show-icon class="submit-notice">
      代码只提交给受控判题沙箱。页面不执行代码、不保存草稿，也不会展示正式考核隐藏测试点或完整判题日志。
    </el-alert>

    <el-tabs v-if="isNarrow" v-model="activePane" class="workspace-tabs">
      <el-tab-pane label="题目" name="problem" />
      <el-tab-pane label="代码" name="code" />
      <el-tab-pane label="结果" name="result" :disabled="!hasResult" />
    </el-tabs>

    <div class="workspace" :class="[`workspace--${activePane}`, { 'workspace--narrow': isNarrow }]">
      <section class="workspace__problem">
        <template v-if="problem">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="摘要">{{ problem.summary }}</el-descriptions-item>
            <el-descriptions-item label="时间限制">{{ problem.timeLimitMs }} ms</el-descriptions-item>
            <el-descriptions-item label="内存限制">{{ Math.round(problem.memoryLimitKb / 1024) }} MB</el-descriptions-item>
          </el-descriptions>
          <div class="problem-section">
            <h3>题面</h3>
            <div class="problem-text">{{ problem.description }}</div>
          </div>
          <el-row :gutter="12" class="problem-section">
            <el-col :span="12">
              <h3>输入格式</h3>
              <div class="problem-text">{{ problem.inputFormat }}</div>
            </el-col>
            <el-col :span="12">
              <h3>输出格式</h3>
              <div class="problem-text">{{ problem.outputFormat }}</div>
            </el-col>
          </el-row>
          <div v-if="problem.examples.length" class="problem-section">
            <h3>示例</h3>
            <article v-for="(item, index) in problem.examples" :key="index" class="example-card">
              <div class="example-title">示例 {{ index + 1 }}</div>
              <pre class="code-block">{{ item.input }}</pre>
              <pre class="code-block">{{ item.output }}</pre>
            </article>
          </div>
          <div v-if="isPractice" class="problem-section">
            <h3>练习测试点</h3>
            <el-table :data="practiceTestCases" max-height="240">
              <el-table-column prop="position" label="#" width="55" />
              <el-table-column label="输入" min-width="150">
                <template #default="{ row }"><pre class="code-block">{{ row.input }}</pre></template>
              </el-table-column>
              <el-table-column label="期望输出" min-width="120">
                <template #default="{ row }"><pre class="code-block">{{ row.expectedOutput }}</pre></template>
              </el-table-column>
            </el-table>
            <el-empty v-if="practiceTestCases.length === 0" description="本题暂未配置练习测试点" />
          </div>
        </template>
      </section>

      <section class="workspace__editor">
        <div class="editor-form" :class="{ 'editor-form--hidden-mobile': isNarrow && activePane === 'result' }">
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
                  data-test="formal-submit"
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
        </div>

        <div
          v-if="hasResult"
          class="result-pane"
          :class="{ 'result-pane--hidden-mobile': isNarrow && activePane !== 'result' }"
        >
          <el-divider content-position="left">{{ isPractice ? '练习运行结果' : '正式考核判定' }}</el-divider>
          <template v-if="isPractice && practiceResult">
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
            <el-table :data="practiceResult.testCaseResults" max-height="300" class="result-table">
              <el-table-column prop="position" label="#" width="55" />
              <el-table-column label="输入" min-width="150">
                <template #default="{ row }"><pre class="code-block">{{ row.input }}</pre></template>
              </el-table-column>
              <el-table-column label="期望输出" min-width="120">
                <template #default="{ row }"><pre class="code-block">{{ row.expectedOutput }}</pre></template>
              </el-table-column>
              <el-table-column label="结果" width="110">
                <template #default="{ row }">
                  <el-tag :type="verdictMeta(row.verdict).type" effect="plain">
                    {{ verdictMeta(row.verdict).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="85">
                <template #default="{ row }">{{ row.timeMs }} ms</template>
              </el-table-column>
              <el-table-column label="内存" width="90">
                <template #default="{ row }">{{ Math.round(row.memoryKb / 1024) }} MB</template>
              </el-table-column>
              <el-table-column label="安全错误" min-width="130">
                <template #default="{ row }">{{ row.safeError ?? '—' }}</template>
              </el-table-column>
            </el-table>
          </template>
          <template v-else-if="formalSubmission">
            <el-descriptions :column="3" border>
              <el-descriptions-item label="状态">{{ formalSubmission.status }}</el-descriptions-item>
              <el-descriptions-item v-if="formalSubmission.judgeResult" label="判定">
                <el-tag :type="verdictMeta(formalSubmission.judgeResult.verdict).type" effect="plain">
                  {{ verdictMeta(formalSubmission.judgeResult.verdict).label }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item v-if="formalSubmission.judgeResult" label="通过用例">
                {{ formalSubmission.judgeResult.passedTestCases }} / {{ formalSubmission.judgeResult.totalTestCases }}
              </el-descriptions-item>
            </el-descriptions>
            <el-table
              v-if="formalSubmission.judgeResult?.testCaseResults?.length"
              :data="formalSubmission.judgeResult.testCaseResults"
              max-height="300"
              class="result-table"
            >
              <el-table-column prop="position" label="#" width="55" />
              <el-table-column label="结果" width="110">
                <template #default="{ row }">
                  <el-tag :type="verdictMeta(row.verdict).type" effect="plain">
                    {{ verdictMeta(row.verdict).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="85">
                <template #default="{ row }">{{ row.timeMs }} ms</template>
              </el-table-column>
              <el-table-column label="内存" width="90">
                <template #default="{ row }">{{ Math.round(row.memoryKb / 1024) }} MB</template>
              </el-table-column>
              <el-table-column label="安全错误" min-width="150">
                <template #default="{ row }">{{ row.safeError ?? '—' }}</template>
              </el-table-column>
            </el-table>
            <div class="result-actions">
              <el-button @click="goSubmissionDetail">查看提交详情</el-button>
            </div>
          </template>
        </div>
      </section>
    </div>
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

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.workspace__problem,
.workspace__editor {
  min-width: 0;
}

.workspace__editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.problem-section {
  margin-top: 14px;
}

.problem-section h3,
.example-title {
  margin: 0 0 8px;
  color: var(--app-ink);
  font-size: 13px;
}

.problem-text {
  color: var(--app-text-primary);
  line-height: 1.7;
  white-space: pre-wrap;
}

.example-card {
  padding: 10px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: var(--app-surface-soft);
}

.code-block {
  margin: 0 0 8px;
  padding: 8px;
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
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

.result-table {
  margin-top: 12px;
}

.result-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.workspace-tabs {
  margin-bottom: 12px;
}

@media (max-width: 767px) {
  .workspace--narrow {
    display: block;
  }

  .workspace--narrow .workspace__problem,
  .workspace--narrow .workspace__editor {
    display: none;
  }

  .workspace--narrow.workspace--problem .workspace__problem,
  .workspace--narrow.workspace--code .workspace__editor,
  .workspace--narrow.workspace--result .workspace__editor {
    display: block;
  }

  .workspace--narrow .editor-form--hidden-mobile,
  .workspace--narrow .result-pane--hidden-mobile {
    display: none;
  }

  .workspace--narrow.workspace--result .workspace__editor {
    display: block;
  }
}
</style>
