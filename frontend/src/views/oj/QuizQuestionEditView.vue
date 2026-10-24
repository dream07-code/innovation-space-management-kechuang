<script setup lang="ts">
/**
 * 创建/编辑客观题（仅负责人/管理员）。
 *
 * 四题型共用一表单：选择题维护 2–6 个选项与字母答案，判断题固定对/错，
 * 填空题按行维护备选答案。仅 DRAFT 可编辑；编辑态禁止改题型（后端同样拒绝）。
 * 创建走 Idempotency-Key；保存后返回题库列表。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { createQuizQuestion, getQuizQuestion, updateQuizQuestion } from '@/api/modules/quiz'
import { describeError, toApiError } from '@/api/errors'
import { optionLetter } from '@/components/quizMeta'
import type { QuizQuestion, QuizQuestionType } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const route = useRoute()
const router = useRouter()

const questionId = computed(() =>
  typeof route.params.questionId === 'string' ? route.params.questionId : null,
)
const isNew = computed(() => questionId.value === null)

const question = ref<QuizQuestion | null>(null)
const loading = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const type = ref<QuizQuestionType>('SINGLE_CHOICE')
const stem = ref('')
/** 选择题选项，从 4 行起步，可增删到 2–6 行。 */
const options = ref<string[]>(['', '', '', ''])
const singleAnswer = ref('')
const multiAnswer = ref<string[]>([])
/** 判断题用字符串承载以配合单选控件，提交时转布尔。 */
const trueFalseAnswer = ref<'true' | 'false' | ''>('')
/** 填空题备选答案：一行一个。 */
const fillAnswers = ref('')
const analysis = ref('')
const tagsText = ref('')

const rules: FormRules = {
  stem: [{ required: true, message: '请输入题干', trigger: 'blur' }],
}

const isChoice = computed(() => type.value === 'SINGLE_CHOICE' || type.value === 'MULTI_CHOICE')
const letterCount = computed(() => Math.max(2, options.value.filter((o) => o.trim()).length || 0))
const letters = computed(() => Array.from({ length: letterCount.value }, (_, i) => optionLetter(i)))
const locked = computed(() => !isNew.value && question.value?.status !== 'DRAFT')

/** 切换题型时收敛答案状态，避免跨题型残留。 */
function switchType(next: QuizQuestionType): void {
  type.value = next
  singleAnswer.value = ''
  multiAnswer.value = []
  trueFalseAnswer.value = ''
}

function addOption(): void {
  if (options.value.length < 6) options.value.push('')
}

function removeOption(index: number): void {
  if (options.value.length > 2) options.value.splice(index, 1)
}

function buildPayload(): Record<string, unknown> | null {
  const trimmedOptions = options.value.map((o) => o.trim())
  const nonBlank = trimmedOptions.filter((o) => o.length > 0)
  let answer: string | string[] | boolean | null = null
  if (type.value === 'SINGLE_CHOICE') {
    if (nonBlank.length < 2 || nonBlank.length > 6) {
      ElMessage.warning('选择题需要 2–6 个非空选项')
      return null
    }
    if (!singleAnswer.value || letters.value.indexOf(singleAnswer.value) < 0) {
      ElMessage.warning('请选择答案')
      return null
    }
    answer = singleAnswer.value
  } else if (type.value === 'MULTI_CHOICE') {
    if (nonBlank.length < 2 || nonBlank.length > 6) {
      ElMessage.warning('选择题需要 2–6 个非空选项')
      return null
    }
    if (multiAnswer.value.length === 0) {
      ElMessage.warning('请选择答案')
      return null
    }
    answer = [...multiAnswer.value].sort()
  } else if (type.value === 'TRUE_FALSE') {
    if (!trueFalseAnswer.value) {
      ElMessage.warning('请选择对/错')
      return null
    }
    answer = trueFalseAnswer.value === 'true'
  } else {
    const alternatives = fillAnswers.value
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0)
    if (alternatives.length === 0) {
      ElMessage.warning('请至少填写一个备选答案')
      return null
    }
    answer = alternatives
  }
  return {
    stem: stem.value.trim(),
    options: isChoice.value ? nonBlank : null,
    answer,
    analysis: analysis.value.trim() || null,
    tags: tagsText.value
      .split(/[、,，]/)
      .map((tag) => tag.trim())
      .filter((tag) => tag.length > 0),
  }
}

async function save(): Promise<void> {
  if (locked.value || saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const base = buildPayload()
  if (!base) return
  saving.value = true
  try {
    if (isNew.value) {
      await createQuizQuestion({ type: type.value, ...(base as never) }, newIdempotencyKey())
    } else {
      await updateQuizQuestion(questionId.value as string, base as never)
    }
    ElMessage.success(isNew.value ? '已创建客观题' : '已保存客观题')
    void router.push({ name: 'oj-quiz-bank' })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    saving.value = false
  }
}

async function load(): Promise<void> {
  if (isNew.value) return
  loading.value = true
  try {
    question.value = await getQuizQuestion(questionId.value as string)
    const found = question.value
    type.value = found.type
    stem.value = found.stem
    if (Array.isArray(found.options) && found.type !== 'TRUE_FALSE' && found.type !== 'FILL_BLANK') {
      options.value = found.options.length >= 2 ? [...found.options] : [...found.options, ...Array(2 - found.options.length).fill('')]
    }
    if (found.type === 'SINGLE_CHOICE') singleAnswer.value = found.answer as string
    else if (found.type === 'MULTI_CHOICE') multiAnswer.value = [...(found.answer as string[])]
    else if (found.type === 'TRUE_FALSE') trueFalseAnswer.value = found.answer ? 'true' : 'false'
    else fillAnswers.value = (found.answer as string[]).join('\n')
    analysis.value = found.analysis ?? ''
    tagsText.value = found.tags.join('、')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <span>{{ isNew ? '创建客观题' : '编辑客观题' }}</span>
    </template>

    <el-form ref="formRef" :model="{ stem }" :rules="rules" label-width="90px" style="max-width: 720px">
      <el-form-item label="题型">
        <el-radio-group :model-value="type" :disabled="!isNew" @update:model-value="switchType">
          <el-radio-button value="SINGLE_CHOICE">单选</el-radio-button>
          <el-radio-button value="MULTI_CHOICE">多选</el-radio-button>
          <el-radio-button value="TRUE_FALSE">判断</el-radio-button>
          <el-radio-button value="FILL_BLANK">填空</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="题干" prop="stem">
        <el-input
          v-model="stem"
          data-test="stem"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="题干支持换行；填空位置用____表示"
        />
      </el-form-item>

      <template v-if="isChoice">
        <el-form-item
          v-for="(_, index) in options"
          :key="index"
          :label="`选项 ${optionLetter(index)}`"
        >
          <div class="option-row">
            <el-input
              v-model="options[index]"
              :data-test="`option-${index}`"
              :placeholder="`选项 ${optionLetter(index)} 内容`"
              maxlength="500"
            />
            <el-button
              v-if="options.length > 2"
              link
              type="danger"
              @click="removeOption(index)"
            >
              删除
            </el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="options.length < 6" label=" ">
          <el-button @click="addOption">加一个选项</el-button>
        </el-form-item>
        <el-form-item label="答案">
          <el-radio-group v-if="type === 'SINGLE_CHOICE'" v-model="singleAnswer">
            <el-radio v-for="letter in letters" :key="letter" :value="letter">{{ letter }}</el-radio>
          </el-radio-group>
          <el-checkbox-group v-else v-model="multiAnswer">
            <el-checkbox v-for="letter in letters" :key="letter" :value="letter">{{ letter }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </template>

      <el-form-item v-else-if="type === 'TRUE_FALSE'" label="答案">
        <el-radio-group v-model="trueFalseAnswer">
          <el-radio-button value="true">对</el-radio-button>
          <el-radio-button value="false">错</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-else label="备选答案">
        <el-input
          v-model="fillAnswers"
          data-test="fill-answers"
          type="textarea"
          :rows="3"
          placeholder="一行一个可接受的答案；比对时忽略首尾空格、区分大小写"
        />
      </el-form-item>

      <el-form-item label="解析">
        <el-input
          v-model="analysis"
          type="textarea"
          :rows="2"
          maxlength="1000"
          placeholder="考核结束后随对错明细展示给学生（可空）"
        />
      </el-form-item>
      <el-form-item label="标签">
        <el-input v-model="tagsText" data-test="tags" placeholder="用、或,分隔（可空）" />
      </el-form-item>
      <el-form-item>
        <el-button data-test="save" type="primary" :loading="saving" :disabled="locked" @click="save">
          保存
        </el-button>
        <el-button @click="router.back()">返回</el-button>
      </el-form-item>
    </el-form>
    <el-alert
      v-if="locked"
      class="locked-tip"
      type="info"
      :closable="false"
      title="该题已发布或停用，仅 DRAFT 状态可编辑"
    />
  </el-card>
</template>

<style scoped>
.option-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.locked-tip {
  margin-top: 12px;
  max-width: 720px;
}
</style>
