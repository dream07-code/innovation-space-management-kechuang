<script setup lang="ts">
/**
 * 创建/编辑 OJ 题目。
 *
 * 页面编辑公开题面、示例、限制、允许语言，以及工作人员专用的两套测试点：
 * 正式考核隐藏测试点与题库练习测试点始终使用不同字段保存。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowUp, Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import {
  createAssessmentProblem,
  getAssessmentProblem,
  getAssessmentProblemTestCases,
  retireAssessmentProblem,
  updateAssessmentProblem,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { languageLabel } from '@/components/ojMeta'
import type {
  AssessmentLanguage,
  AssessmentProblem,
} from '@/api/types'
import {
  buildCreateProblemPayload,
  buildUpdateProblemPayload,
  validateProblemTestCaseForm,
  type ProblemTestCaseRow,
} from './problemPayload'

const route = useRoute()
const router = useRouter()

const problemId = computed(() =>
  typeof route.params.problemId === 'string' ? route.params.problemId : null,
)
const isNew = computed(() => problemId.value === null)
const problem = ref<AssessmentProblem | null>(null)
const loading = ref(false)
const saving = ref(false)
const retiring = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  title: '',
  summary: '',
  description: '',
  inputFormat: '',
  outputFormat: '',
  difficulty: 'EASY' as 'EASY' | 'MEDIUM' | 'HARD',
  tagsText: '',
  timeLimitMs: 1000,
  memoryLimitKb: 131072,
  allowedLanguages: ['java', 'python'] as AssessmentLanguage[],
  examples: [{ input: '', output: '', explanation: '' }] as Array<{
    input: string
    output: string
    explanation: string
  }>,
  assessmentTestCases: [{ input: '', expectedOutput: '' }] as ProblemTestCaseRow[],
  practiceTestCases: [] as ProblemTestCaseRow[],
})

const rules: FormRules = {
  title: [{ required: true, message: '请输入题目标题', trigger: 'blur' }],
  summary: [{ required: true, message: '请输入摘要', trigger: 'blur' }],
  description: [{ required: true, message: '请输入题面', trigger: 'blur' }],
  inputFormat: [{ required: true, message: '请输入输入格式', trigger: 'blur' }],
  outputFormat: [{ required: true, message: '请输入输出格式', trigger: 'blur' }],
  difficulty: [{ required: true, message: '请选择难度', trigger: 'change' }],
  timeLimitMs: [{ required: true, type: 'number', min: 1, message: '请输入时间限制', trigger: 'change' }],
  memoryLimitKb: [{ required: true, type: 'number', min: 1024, message: '请输入内存限制', trigger: 'change' }],
  allowedLanguages: [{ required: true, type: 'array', message: '请选择允许语言', trigger: 'change' }],
}

const languageOptions = (['java', 'python', 'cpp', 'javascript', 'go', 'rust'] as AssessmentLanguage[]).map(
  (value) => ({ value, label: languageLabel(value) }),
)

async function load(): Promise<void> {
  if (isNew.value) return
  loading.value = true
  try {
    const [loadedProblem, testCases] = await Promise.all([
      getAssessmentProblem(problemId.value!),
      getAssessmentProblemTestCases(problemId.value!),
    ])
    problem.value = loadedProblem
    form.title = problem.value.title
    form.summary = problem.value.summary
    form.description = problem.value.description
    form.inputFormat = problem.value.inputFormat
    form.outputFormat = problem.value.outputFormat
    form.difficulty = problem.value.difficulty
    form.tagsText = problem.value.tags.join(', ')
    form.timeLimitMs = problem.value.timeLimitMs
    form.memoryLimitKb = problem.value.memoryLimitKb
    form.allowedLanguages = [...problem.value.allowedLanguages]
    form.examples = problem.value.examples.map((item) => ({
      input: item.input,
      output: item.output,
      explanation: item.explanation ?? '',
    }))
    if (form.examples.length === 0) form.examples.push({ input: '', output: '', explanation: '' })
    form.assessmentTestCases = testCases.assessmentTestCases.map((item) => ({
      input: item.input,
      expectedOutput: item.expectedOutput,
    }))
    if (form.assessmentTestCases.length === 0) {
      form.assessmentTestCases.push({ input: '', expectedOutput: '' })
    }
    form.practiceTestCases = testCases.practiceTestCases.map((item) => ({
      input: item.input,
      expectedOutput: item.expectedOutput,
    }))
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const testCaseError = validateProblemTestCaseForm(
    form.assessmentTestCases,
    form.practiceTestCases,
  )
  if (testCaseError) {
    ElMessage.warning(testCaseError)
    return
  }

  saving.value = true
  try {
    if (isNew.value) {
      const created = await createAssessmentProblem(
        buildCreateProblemPayload(form),
        crypto.randomUUID(),
      )
      ElMessage.success('题目草稿已创建')
      void router.replace({ name: 'oj-problem-detail', params: { problemId: created.id } })
      return
    }

    const payload = buildUpdateProblemPayload(problem.value!.version, form)
    const updated = await updateAssessmentProblem(problem.value!.id, payload)
    ElMessage.success('题目已保存')
    void router.replace({ name: 'oj-problem-detail', params: { problemId: updated.id } })
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('题目已被他人更新，已刷新表单')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    saving.value = false
  }
}

async function retire(): Promise<void> {
  if (!problem.value) return
  retiring.value = true
  try {
    await retireAssessmentProblem(
      problem.value.id,
      { version: problem.value.version },
      crypto.randomUUID(),
    )
    ElMessage.success('题目已退役')
    await load()
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('题目版本已变化，已刷新')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    retiring.value = false
  }
}

function addExample(): void {
  form.examples.push({ input: '', output: '', explanation: '' })
}

function removeExample(index: number): void {
  if (form.examples.length <= 1) return
  form.examples.splice(index, 1)
}

type TestCaseKind = 'assessment' | 'practice'

function testCasesOf(kind: TestCaseKind): ProblemTestCaseRow[] {
  return kind === 'assessment' ? form.assessmentTestCases : form.practiceTestCases
}

function addTestCase(kind: TestCaseKind): void {
  testCasesOf(kind).push({ input: '', expectedOutput: '' })
}

function removeTestCase(kind: TestCaseKind, index: number): void {
  const rows = testCasesOf(kind)
  if (kind === 'assessment' && rows.length <= 1) return
  rows.splice(index, 1)
}

function moveTestCase(kind: TestCaseKind, index: number, offset: -1 | 1): void {
  const rows = testCasesOf(kind)
  const target = index + offset
  if (target < 0 || target >= rows.length) return
  const current = rows[index]
  const other = rows[target]
  if (!current || !other) return
  rows[index] = other
  rows[target] = current
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <span>{{ isNew ? '创建 OJ 题目' : '编辑 OJ 题目' }}</span>
        <el-button
          v-if="!isNew && problem?.status !== 'RETIRED'"
          type="danger"
          plain
          :loading="retiring"
          @click="retire"
        >
          退役题目
        </el-button>
      </div>
    </template>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="problem-form">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="120" show-word-limit />
      </el-form-item>
      <el-form-item label="摘要" prop="summary">
        <el-input v-model="form.summary" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="题面" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="7" maxlength="12000" show-word-limit />
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="输入格式" prop="inputFormat">
            <el-input v-model="form.inputFormat" type="textarea" :rows="3" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="输出格式" prop="outputFormat">
            <el-input v-model="form.outputFormat" type="textarea" :rows="3" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="示例">
        <div class="examples">
          <div v-for="(item, index) in form.examples" :key="index" class="example-card">
            <div class="example-header">
              <span>示例 {{ index + 1 }}</span>
              <el-button
                v-if="form.examples.length > 1"
                link
                type="danger"
                :icon="Delete"
                @click="removeExample(index)"
              >
                删除
              </el-button>
            </div>
            <el-input v-model="item.input" type="textarea" :rows="2" placeholder="输入" />
            <el-input v-model="item.output" type="textarea" :rows="2" placeholder="输出" class="example-output" />
            <el-input v-model="item.explanation" placeholder="说明（可选）" />
          </div>
          <el-button :icon="Plus" @click="addExample">添加示例</el-button>
        </div>
      </el-form-item>

      <el-form-item label="正式考核测试点">
        <div class="test-case-section">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="此测试点在正式考核中对学员隐藏"
            description="正式考核判题只使用本组测试点；题库练习不会读取本组数据。"
          />
          <div
            v-for="(item, index) in form.assessmentTestCases"
            :key="`assessment-${index}`"
            class="test-case-card"
          >
            <div class="test-case-header">
              <strong>隐藏测试点 {{ index + 1 }}</strong>
              <div class="test-case-actions">
                <el-button
                  link
                  :icon="ArrowUp"
                  :disabled="index === 0"
                  aria-label="上移"
                  @click="moveTestCase('assessment', index, -1)"
                />
                <el-button
                  link
                  :icon="ArrowDown"
                  :disabled="index === form.assessmentTestCases.length - 1"
                  aria-label="下移"
                  @click="moveTestCase('assessment', index, 1)"
                />
                <el-button
                  link
                  type="danger"
                  :icon="Delete"
                  :disabled="form.assessmentTestCases.length <= 1"
                  @click="removeTestCase('assessment', index)"
                >
                  删除
                </el-button>
              </div>
            </div>
            <el-input v-model="item.input" type="textarea" :rows="3" placeholder="输入（无输入时留空）" />
            <el-input
              v-model="item.expectedOutput"
              type="textarea"
              :rows="3"
              placeholder="期望输出"
              class="test-case-output"
            />
          </div>
          <el-button
            :icon="Plus"
            :disabled="form.assessmentTestCases.length >= 100"
            @click="addTestCase('assessment')"
          >
            添加正式考核测试点
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="题库练习测试点">
        <div class="test-case-section">
          <el-alert
            type="success"
            :closable="false"
            show-icon
            title="仅用于题库练习"
            description="练习模式会向学员展示本组输入和期望输出；正式考核不会使用本组数据。"
          />
          <div v-if="form.practiceTestCases.length === 0" class="test-case-empty">
            暂无练习测试点。学员仍可提交正式考核代码，但无法在练习模式查看样例测试点。
          </div>
          <div
            v-for="(item, index) in form.practiceTestCases"
            :key="`practice-${index}`"
            class="test-case-card"
          >
            <div class="test-case-header">
              <strong>练习测试点 {{ index + 1 }}</strong>
              <div class="test-case-actions">
                <el-button
                  link
                  :icon="ArrowUp"
                  :disabled="index === 0"
                  aria-label="上移"
                  @click="moveTestCase('practice', index, -1)"
                />
                <el-button
                  link
                  :icon="ArrowDown"
                  :disabled="index === form.practiceTestCases.length - 1"
                  aria-label="下移"
                  @click="moveTestCase('practice', index, 1)"
                />
                <el-button link type="danger" :icon="Delete" @click="removeTestCase('practice', index)">
                  删除
                </el-button>
              </div>
            </div>
            <el-input v-model="item.input" type="textarea" :rows="3" placeholder="输入（无输入时留空）" />
            <el-input
              v-model="item.expectedOutput"
              type="textarea"
              :rows="3"
              placeholder="期望输出"
              class="test-case-output"
            />
          </div>
          <el-button
            :icon="Plus"
            :disabled="form.practiceTestCases.length >= 100"
            @click="addTestCase('practice')"
          >
            添加练习测试点
          </el-button>
        </div>
      </el-form-item>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="难度" prop="difficulty">
            <el-select v-model="form.difficulty" style="width: 100%">
              <el-option label="简单" value="EASY" />
              <el-option label="中等" value="MEDIUM" />
              <el-option label="困难" value="HARD" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="时间限制（ms）" prop="timeLimitMs">
            <el-input-number v-model="form.timeLimitMs" :min="1" :max="60000" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="内存限制（KB）" prop="memoryLimitKb">
            <el-input-number v-model="form.memoryLimitKb" :min="1024" :max="2097152" :step="1024" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="标签（逗号分隔）">
        <el-input v-model="form.tagsText" placeholder="数组, 哈希" />
      </el-form-item>
      <el-form-item label="允许语言" prop="allowedLanguages">
        <el-select v-model="form.allowedLanguages" multiple style="width: 100%">
          <el-option v-for="item in languageOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <div class="form-actions">
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <el-button @click="router.back()">取消</el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.problem-form {
  max-width: 920px;
}

.examples {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 12px;
}

.example-card {
  width: 100%;
  padding: 14px;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: var(--app-surface-soft);
}

.example-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.example-output {
  margin: 8px 0;
}

.test-case-section {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 12px;
}

.test-case-card {
  width: 100%;
  padding: 14px;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: var(--app-surface-soft);
}

.test-case-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.test-case-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.test-case-output {
  margin-top: 8px;
}

.test-case-empty {
  padding: 14px;
  border: 1px dashed var(--app-border);
  border-radius: 7px;
  color: var(--app-text-secondary);
  background: var(--app-surface-soft);
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
</style>
