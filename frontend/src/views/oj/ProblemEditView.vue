<script setup lang="ts">
/**
 * 创建/编辑 OJ 题目。
 *
 * 页面只编辑公开题面、示例、限制和允许语言；私有测试数据由受控 OJ/存储管理，
 * 普通前端接口不读取也不展示。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import {
  createAssessmentProblem,
  getAssessmentProblem,
  retireAssessmentProblem,
  updateAssessmentProblem,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { languageLabel } from '@/components/ojMeta'
import type {
  AssessmentLanguage,
  AssessmentProblem,
  ProblemExample,
  UpdateAssessmentProblemRequest,
} from '@/api/types'

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

function toExamples(): ProblemExample[] {
  return form.examples
    .filter((item) => item.input.trim() || item.output.trim())
    .map((item) => ({
      input: item.input,
      output: item.output,
      explanation: item.explanation.trim() || null,
    }))
}

async function load(): Promise<void> {
  if (isNew.value) return
  loading.value = true
  try {
    problem.value = await getAssessmentProblem(problemId.value!)
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
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const base = {
      title: form.title.trim(),
      summary: form.summary.trim(),
      description: form.description.trim(),
      inputFormat: form.inputFormat.trim(),
      outputFormat: form.outputFormat.trim(),
      examples: toExamples(),
      difficulty: form.difficulty,
      tags: form.tagsText.split(',').map((item) => item.trim()).filter(Boolean),
      timeLimitMs: form.timeLimitMs,
      memoryLimitKb: form.memoryLimitKb,
      allowedLanguages: form.allowedLanguages,
    }
    if (isNew.value) {
      const created = await createAssessmentProblem(base, crypto.randomUUID())
      ElMessage.success('题目草稿已创建')
      void router.replace({ name: 'oj-problem-detail', params: { problemId: created.id } })
      return
    }

    const payload: UpdateAssessmentProblemRequest = { version: problem.value!.version, ...base }
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

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
</style>
