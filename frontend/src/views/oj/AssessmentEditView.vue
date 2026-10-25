<script setup lang="ts">
/**
 * 创建/编辑 OJ 考核（混合试卷）。
 *
 * 仅学院管理员和获授权方向负责人可用。挂题为「混合条目」：程序题与客观题
 * 混排、逐题定分值（1–100）、顺序即 position；DRAFT 可修改；版本冲突时
 * 重取并提示。写操作使用 Idempotency-Key，保存后进入详情页执行发布/关闭。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import {
  createAssessment,
  getAssessment,
  listAssessmentProblems,
  updateAssessment,
} from '@/api/modules/oj'
import { listQuizQuestions } from '@/api/modules/quiz'
import { describeError, toApiError } from '@/api/errors'
import { languageLabel } from '@/components/ojMeta'
import { QUIZ_TYPE_META } from '@/components/quizMeta'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import type {
  Assessment,
  AssessmentLanguage,
  AssessmentProblem,
  QuizQuestion,
  UpdateAssessmentRequest,
} from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

type ItemType = 'PROGRAM' | 'QUIZ'

interface EditableItem {
  type: ItemType
  refId: string
  score: number
  label: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const directions = useDirectionsStore()

const assessmentId = computed(() =>
  typeof route.params.assessmentId === 'string' ? route.params.assessmentId : null,
)
const isNew = computed(() => assessmentId.value === null)

const assessment = ref<Assessment | null>(null)
const problems = ref<AssessmentProblem[]>([])
const quizzes = ref<QuizQuestion[]>([])
const loading = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  title: '',
  description: '',
  directionId: '',
  startAt: '',
  endAt: '',
  allowedLanguages: ['java', 'python'] as AssessmentLanguage[],
  pointsEnabled: false,
})

/** 混合条目列表：数组顺序即试卷 position。 */
const items = ref<EditableItem[]>([])
const selectedProgram = ref('')
const selectedQuiz = ref('')

const DEFAULT_SCORE: Record<ItemType, number> = { PROGRAM: 100, QUIZ: 5 }

const rules: FormRules = {
  title: [{ required: true, message: '请输入考核标题', trigger: 'blur' }],
  description: [{ required: true, message: '请输入考核说明', trigger: 'blur' }],
  directionId: [{ required: true, message: '请选择方向', trigger: 'change' }],
  startAt: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endAt: [{ required: true, message: '请选择截止时间', trigger: 'change' }],
  allowedLanguages: [{ required: true, type: 'array', message: '请选择允许语言', trigger: 'change' }],
}

const languageOptions = (['java', 'python', 'cpp', 'javascript', 'go', 'rust'] as AssessmentLanguage[]).map(
  (value) => ({ value, label: languageLabel(value) }),
)

const totalScore = computed(() => items.value.reduce((sum, item) => sum + item.score, 0))

function programLabel(problem: Pick<AssessmentProblem, 'title' | 'difficulty'>): string {
  return `${problem.title}（${problem.difficulty}）`
}

function quizLabel(question: Pick<QuizQuestion, 'type' | 'stem'>): string {
  return `${QUIZ_TYPE_META[question.type].label} · ${question.stem.slice(0, 30)}`
}

function addItem(type: ItemType): void {
  const refId = type === 'PROGRAM' ? selectedProgram.value : selectedQuiz.value
  if (!refId) {
    ElMessage.warning(type === 'PROGRAM' ? '请先选择程序题' : '请先选择客观题')
    return
  }
  if (items.value.some((item) => item.type === type && item.refId === refId)) {
    ElMessage.warning('该题已在试卷中，一场考核同一题只能挂一次')
    return
  }
  const problem = problems.value.find((p) => p.id === refId)
  const question = quizzes.value.find((q) => q.id === refId)
  const label =
    type === 'PROGRAM'
      ? programLabel(problem ?? { title: refId, difficulty: 'EASY' })
      : quizLabel(question ?? { type: 'FILL_BLANK', stem: refId })
  items.value.push({ type, refId, score: DEFAULT_SCORE[type], label })
  if (type === 'PROGRAM') selectedProgram.value = ''
  else selectedQuiz.value = ''
}

function move(index: number, delta: -1 | 1): void {
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const list = items.value
  ;[list[index], list[target]] = [list[target]!, list[index]!]
}

function remove(index: number): void {
  items.value.splice(index, 1)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    await directions.ensureLoaded()
    const [problemPage, quizPage] = await Promise.all([
      listAssessmentProblems(),
      listQuizQuestions({ page: 1, size: 100 }),
    ])
    problems.value = problemPage.items.filter((problem) => problem.status !== 'RETIRED')
    quizzes.value = (quizPage.items as QuizQuestion[]).filter((q) => q.status !== 'RETIRED')
    if (assessmentId.value) {
      assessment.value = await getAssessment(assessmentId.value)
      form.title = assessment.value.title
      form.description = assessment.value.description
      form.directionId = assessment.value.directionId
      form.startAt = assessment.value.startAt
      form.endAt = assessment.value.endAt
      form.pointsEnabled = assessment.value.pointsEnabled ?? false
      form.allowedLanguages = [...assessment.value.allowedLanguages]
      // 条目化接口优先；旧数据无 items 时按 problemIds 回填（分值 100 兼容旧口径）。
      const raw = assessment.value.items ?? assessment.value.problemIds.map((refId, index) => ({
        position: index + 1,
        type: 'PROGRAM' as ItemType,
        refId,
        score: 100,
      }))
      items.value = raw.map((item) => {
        const problem = problems.value.find((p) => p.id === item.refId)
        const question = quizzes.value.find((q) => q.id === item.refId)
        return {
          type: item.type,
          refId: item.refId,
          score: item.score,
          label:
            item.type === 'PROGRAM'
              ? programLabel(problem ?? { title: item.refId, difficulty: 'EASY' })
              : quizLabel(question ?? { type: 'FILL_BLANK', stem: item.refId }),
        }
      })
    } else {
      form.directionId = auth.user?.directionId ?? directions.items[0]?.id ?? ''
    }
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (items.value.length === 0) {
    ElMessage.warning('请至少挂一道题目')
    return
  }

  saving.value = true
  try {
    const base = {
      title: form.title.trim(),
      description: form.description.trim(),
      directionId: form.directionId,
      startAt: form.startAt,
      endAt: form.endAt,
      allowedLanguages: form.allowedLanguages,
      pointsEnabled: form.pointsEnabled,
      items: items.value.map(({ type, refId, score }, index) => ({
        position: index + 1,
        type,
        refId,
        score,
      })),
    }
    if (isNew.value) {
      const created = await createAssessment(base, newIdempotencyKey())
      ElMessage.success('考核草稿已创建')
      void router.replace({ name: 'oj-assessment-detail', params: { assessmentId: created.id } })
      return
    }

    const payload: UpdateAssessmentRequest = { version: assessment.value!.version, ...base }
    const updated = await updateAssessment(assessment.value!.id, payload)
    ElMessage.success('考核草稿已保存')
    void router.replace({ name: 'oj-assessment-detail', params: { assessmentId: updated.id } })
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('考核已被他人更新，已刷新表单，请确认后再保存')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>{{ isNew ? '创建 OJ 考核' : '编辑 OJ 考核' }}</template>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="assessment-form">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" data-test="title" maxlength="120" show-word-limit />
      </el-form-item>
      <el-form-item label="说明" prop="description">
        <el-input v-model="form.description" data-test="description" type="textarea" :rows="5" maxlength="4000" show-word-limit />
      </el-form-item>
      <el-form-item label="方向" prop="directionId">
        <el-select v-model="form.directionId" style="width: 240px">
          <el-option v-for="direction in directions.items" :key="direction.id" :label="direction.name" :value="direction.id" />
        </el-select>
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="开始时间" prop="startAt">
            <el-date-picker
              v-model="form.startAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="截止时间" prop="endAt">
            <el-date-picker
              v-model="form.endAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="允许语言" prop="allowedLanguages">
        <el-select v-model="form.allowedLanguages" multiple style="width: 100%">
          <el-option v-for="item in languageOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>

      <el-form-item label="考核积分">
        <div class="points-switch" data-test="points-switch">
          <el-switch v-model="form.pointsEnabled" />
          <span class="points-switch__hint">
            开启后考核关闭时按最终成绩发放积分：第一名 20 分，其余按成绩占第一名的比例折算（并列第一同分，0 分不发）。
          </span>
        </div>
      </el-form-item>

      <el-form-item label="混合条目（程序题 + 客观题，顺序即试卷题号）">
        <div class="item-editor">
          <div class="item-sources">
            <el-select
              v-model="selectedProgram"
              data-test="source-program"
              placeholder="程序题库"
              filterable
              style="width: 300px"
            >
              <el-option
                v-for="problem in problems"
                :key="problem.id"
                :label="programLabel(problem)"
                :value="problem.id"
              />
            </el-select>
            <el-button data-test="add-program" @click="addItem('PROGRAM')">添加程序题</el-button>
            <el-select
              v-model="selectedQuiz"
              data-test="source-quiz"
              placeholder="客观题库"
              filterable
              style="width: 300px"
            >
              <el-option
                v-for="question in quizzes"
                :key="question.id"
                :label="quizLabel(question)"
                :value="question.id"
              />
            </el-select>
            <el-button data-test="add-quiz" @click="addItem('QUIZ')">添加客观题</el-button>
          </div>

          <el-table v-if="items.length" :data="items" class="item-table">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="row.type === 'PROGRAM' ? 'primary' : 'warning'" effect="plain">
                  {{ row.type === 'PROGRAM' ? '程序' : '客观' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="label" label="题目" min-width="260" show-overflow-tooltip />
            <el-table-column label="分值" width="150">
              <template #default="{ $index }">
                <el-input-number
                  v-model="items[$index]!.score"
                  :data-test="`score-${$index}`"
                  :min="1"
                  :max="100"
                  :step="5"
                  controls-position="right"
                  style="width: 120px"
                />
              </template>
            </el-table-column>
            <el-table-column label="排序" width="130">
              <template #default="{ $index }">
                <el-button
                  link
                  :data-test="`move-up-${$index}`"
                  :disabled="$index === 0"
                  @click="move($index, -1)"
                >
                  上移
                </el-button>
                <el-button
                  link
                  :data-test="`move-down-${$index}`"
                  :disabled="$index === items.length - 1"
                  @click="move($index, 1)"
                >
                  下移
                </el-button>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" :data-test="`remove-${$index}`" @click="remove($index)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="item-empty">尚未挂题：从上方两个题库选择题目添加。</div>
          <div class="item-total">合计 {{ totalScore }} 分（每题 1–100）</div>
        </div>
      </el-form-item>

      <div class="form-actions">
        <el-button data-test="save" type="primary" :loading="saving" @click="save">保存草稿</el-button>
        <el-button @click="router.back()">取消</el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.assessment-form {
  max-width: 860px;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

.item-editor {
  width: 100%;
}

.item-sources {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.item-table {
  width: 100%;
}

.item-empty {
  color: var(--el-text-color-secondary);
  padding: 8px 0;
}

.item-total {
  margin-top: 10px;
  font-weight: 600;
}

.points-switch {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.points-switch__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
