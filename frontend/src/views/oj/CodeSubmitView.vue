<script setup lang="ts">
/**
 * OJ 做题工作台。
 *
 * 桌面端左侧题面、右侧代码和结果；窄屏使用“题目 / 代码 / 结果”标签。
 * 正式考核只接收安全结果摘要，题库练习额外展示独立练习测试点。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CaretRight, RefreshLeft, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import CodeEditor from '@/components/CodeEditor.vue'

import {
  createAssessmentSubmission,
  getAssessment,
  getAssessmentProblem,
  getAssessmentSubmission,
  getPracticeTestCases,
  runPractice,
  runTrial as runTrialProblem,
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
import { newIdempotencyKey } from '@/api/idempotency'
import { languageRuntime, starterCode, starterForProblem } from './languageTemplates'

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
/** 正式考核做题时的试运行结果：随机抽到的 1 个正式测试点。 */
const trialResult = ref<OjPracticeRunResult | null>(null)
const formalSubmission = ref<AssessmentSubmission | null>(null)
const language = ref<AssessmentLanguage>('python')
const code = ref('')
/** 每个语言各自保留编辑中的代码，切换语言不丢失已写内容。 */
const codeByLanguage = ref<Partial<Record<AssessmentLanguage, string>>>({})
const loading = ref(false)
const submitting = ref(false)
const runningPractice = ref(false)
const runningTrial = ref(false)
const isNarrow = ref(false)
const activePane = ref<'problem' | 'code' | 'result'>('problem')
let idempotencyKey = newIdempotencyKey()
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
const runtime = computed(() => languageRuntime(language.value))
/** 函数模式用题目签名生成骨架，标准模式沿用命令行模板。 */
function starterFor(target: AssessmentLanguage): string {
  return problem.value ? starterForProblem(target, problem.value) : starterCode(target)
}
const sampleCase = computed(() => formalSubmission.value?.judgeResult?.sampleCase ?? null)
/** 练习与考核试运行结果结构一致，共用同一套表格。 */
const runResult = computed(() => practiceResult.value ?? trialResult.value)
const resultTitle = computed(() => {
  if (practiceResult.value) return '练习运行结果'
  if (trialResult.value) return '试运行结果（随机 1 个正式测试点）'
  return '正式考核判定'
})

function syncViewport(): void {
  isNarrow.value =
    typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 767px)').matches
  if (isNarrow.value && activePane.value === 'result' && !hasResult.value) {
    activePane.value = 'code'
  }
}

const hasResult = computed(
  () =>
    practiceResult.value !== null
    || trialResult.value !== null
    || formalSubmission.value?.judgeResult != null,
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
    code.value = starterFor(language.value)
    codeByLanguage.value[language.value] = code.value
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function onCodeChanged(): void {
  idempotencyKey = newIdempotencyKey()
  if (isPractice.value) practiceResult.value = null
  else formalSubmission.value = null
}

/** 切换语言时缓存当前代码，并载入目标语言上次的代码或起始框架。 */
function changeLanguage(next: string): void {
  if (next === language.value) return
  const target = next as AssessmentLanguage
  codeByLanguage.value[language.value] = code.value
  language.value = target
  code.value = codeByLanguage.value[target] ?? starterFor(target)
  codeByLanguage.value[target] = code.value
  onCodeChanged()
}

function applyStarterCode(): void {
  code.value = starterFor(language.value)
  codeByLanguage.value[language.value] = code.value
  onCodeChanged()
}

async function resetToTemplate(): Promise<void> {
  if (code.value.trim() === starterFor(language.value).trim()) return
  try {
    await ElMessageBox.confirm(
      '当前语言的代码会被起始框架替换，替换后无法撤销。',
      '重置为起始代码',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  applyStarterCode()
  ElMessage.success('已恢复该语言的起始代码框架')
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
      newIdempotencyKey(),
    )
    activePane.value = 'result'
    ElMessage.success('练习判题完成')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    runningPractice.value = false
  }
}

/**
 * 正式考核做题时的试运行：随机抽 1 个正式测试点跑当前代码。
 * 不产生提交记录，后端按成员每分钟限 10 次。
 */
async function runTrial(): Promise<void> {
  if (!problem.value) return
  if (!code.value.trim()) {
    ElMessage.warning('请输入代码')
    return
  }
  if (code.value.length > 200000) {
    ElMessage.warning('代码长度超过 200000 字符')
    return
  }

  runningTrial.value = true
  try {
    trialResult.value = await runTrialProblem(problem.value.id, {
      language: language.value,
      code: code.value,
    })
    activePane.value = 'result'
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.httpStatus === 429) {
      ElMessage.warning(
        apiError.retryAfterSeconds
          ? `试运行过于频繁，请 ${apiError.retryAfterSeconds} 秒后再试`
          : '试运行过于频繁，请稍后再试',
      )
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    runningTrial.value = false
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
    trialResult.value = null
    await pollFormalSubmission(submission.id)
    // 提交后保留编辑器里的代码，方便成员对照结果修改后重新提交。
    idempotencyKey = newIdempotencyKey()
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
      代码只提交给受控判题沙箱。页面不执行代码、不保存草稿；判题后会展示一个参考测试点的输入与输出用于定位问题，其余隐藏测试点和完整判题日志不会返回。
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
              <div class="example-label">输入</div>
              <pre class="code-block">{{ item.input }}</pre>
              <div class="example-label">输出</div>
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
          <div class="editor-shell">
            <div class="editor-toolbar">
              <el-select
                :model-value="language"
                size="small"
                class="language-select"
                data-test="language-select"
                @change="changeLanguage"
              >
                <el-option
                  v-for="item in allowedLanguages"
                  :key="item"
                  :label="languageLabel(item)"
                  :value="item"
                />
              </el-select>
              <span v-if="runtime" class="editor-runtime">{{ runtime.runtime }}</span>
              <span class="editor-spacer" />
              <span class="editor-stats">{{ lineCount }} 行 · {{ code.length }} 字符</span>
              <el-button
                link
                size="small"
                :icon="RefreshLeft"
                data-test="reset-template"
                @click="resetToTemplate"
              >
                重置代码
              </el-button>
            </div>
            <CodeEditor
              v-model="code"
              :language="language"
              :height="isNarrow ? '340px' : '460px'"
              @change="onCodeChanged"
            />
            <p v-if="runtime" class="editor-hint">{{ runtime.submitHint }}</p>
          </div>
          <div class="editor-footer">
            <span>
              {{
                isPractice
                  ? '点击“运行练习”使用本题练习测试点即时判题'
                  : '“试运行”随机抽取 1 个正式测试点自查；提交后由受控沙箱判定全部测试点'
              }}
            </span>
            <div>
              <el-button @click="router.back()">返回</el-button>
              <el-button
                v-if="!isPractice"
                data-test="trial-run"
                :icon="CaretRight"
                :loading="runningTrial"
                :disabled="assessment?.status !== 'PUBLISHED'"
                @click="runTrial"
              >
                试运行
              </el-button>
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
        </div>

        <div
          v-if="hasResult"
          class="result-pane"
          :class="{ 'result-pane--hidden-mobile': isNarrow && activePane !== 'result' }"
        >
          <el-divider content-position="left">{{ resultTitle }}</el-divider>
          <template v-if="runResult">
            <el-alert
              v-if="runResult.totalTestCases === 0"
              type="warning"
              :closable="false"
              show-icon
              class="run-empty"
            >
              本题暂未配置可运行的测试点，无法试跑{{
                isPractice ? '，请联系老师在题目里补充练习测试点' : ''
              }}。
            </el-alert>
            <el-descriptions v-if="runResult.totalTestCases > 0" :column="3" border>
              <el-descriptions-item label="判定">
                <el-tag :type="verdictMeta(runResult.verdict).type" effect="plain">
                  {{ verdictMeta(runResult.verdict).label }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="通过用例">
                {{ runResult.passedTestCases }} / {{ runResult.totalTestCases }}
              </el-descriptions-item>
              <el-descriptions-item label="资源">
                {{ runResult.timeMs }} ms / {{ Math.round(runResult.memoryKb / 1024) }} MB
              </el-descriptions-item>
              <el-descriptions-item v-if="runResult.safeError" label="安全错误分类" :span="3">
                {{ runResult.safeError }}
              </el-descriptions-item>
            </el-descriptions>
            <el-table v-if="runResult.testCaseResults.length" :data="runResult.testCaseResults" max-height="300" class="result-table">
              <el-table-column prop="position" label="#" width="55" />
              <el-table-column label="输入" min-width="150">
                <template #default="{ row }"><pre class="code-block">{{ row.input }}</pre></template>
              </el-table-column>
              <el-table-column label="期望输出" min-width="120">
                <template #default="{ row }"><pre class="code-block">{{ row.expectedOutput }}</pre></template>
              </el-table-column>
              <el-table-column label="实际输出" min-width="120">
                <template #default="{ row }"><pre class="code-block">{{ row.actualOutput || '（无输出）' }}</pre></template>
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
            <p
              v-if="isPractice && practiceTestCases.length === 0 && practiceResult"
              class="run-hint"
            >
              本题没有单独配置练习测试点，已随机抽取 1 个正式测试点试跑。
            </p>
            <p v-if="!isPractice && trialResult" class="run-hint">
              每次从正式考核测试点中随机抽取 1 个，只用于自查；其余测试点仍然隐藏，每分钟最多 10 次。
            </p>
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
            <div v-if="sampleCase" class="sample-case">
              <el-divider content-position="left">
                参考测试点 · 第 {{ sampleCase.position }} 个
                <el-tag :type="verdictMeta(sampleCase.verdict).type" effect="plain" size="small">
                  {{ verdictMeta(sampleCase.verdict).label }}
                </el-tag>
              </el-divider>
              <el-descriptions :column="1" border>
                <el-descriptions-item label="输入">
                  <pre class="code-block">{{ sampleCase.input || '（空）' }}</pre>
                </el-descriptions-item>
                <el-descriptions-item label="实际输出">
                  <pre class="code-block">{{ sampleCase.actualOutput || '（程序没有输出）' }}</pre>
                </el-descriptions-item>
                <el-descriptions-item label="期望输出">
                  <pre class="code-block">{{ sampleCase.expectedOutput || '（未设置）' }}</pre>
                </el-descriptions-item>
              </el-descriptions>
              <p class="sample-hint">
                只展示一个测试点用于定位问题，其余正式考核测试点保持隐藏；反复提交可对照修改。
              </p>
            </div>
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

.editor-shell {
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: var(--app-control-radius);
  background: var(--app-surface);
}

.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--app-border);
  background: var(--app-surface-soft);
}

.language-select {
  width: 148px;
}

.editor-runtime {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.editor-spacer {
  flex: 1;
}

.editor-stats {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.editor-hint {
  margin: 0;
  padding: 6px 10px;
  border-top: 1px solid var(--app-border);
  background: var(--app-surface-soft);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.editor-shell .code-editor {
  border: none;
  border-radius: 0;
}

.sample-case {
  margin-top: 12px;
}

.sample-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.run-empty {
  margin-bottom: 12px;
}

.run-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.example-label {
  margin: 6px 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
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
