<script setup lang="ts">
/**
 * 创建/编辑 OJ 考核。
 *
 * 仅学院管理员和获授权方向负责人可用。DRAFT 可修改；版本冲突时重取并提示。
 * 写操作使用 Idempotency-Key，保存后进入详情页执行发布/关闭。
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
import { describeError, toApiError } from '@/api/errors'
import { languageLabel } from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import type {
  Assessment,
  AssessmentLanguage,
  AssessmentProblem,
  UpdateAssessmentRequest,
} from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

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
  problemIds: [] as string[],
})

const rules: FormRules = {
  title: [{ required: true, message: '请输入考核标题', trigger: 'blur' }],
  description: [{ required: true, message: '请输入考核说明', trigger: 'blur' }],
  directionId: [{ required: true, message: '请选择方向', trigger: 'change' }],
  startAt: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endAt: [{ required: true, message: '请选择截止时间', trigger: 'change' }],
  allowedLanguages: [{ required: true, type: 'array', message: '请选择允许语言', trigger: 'change' }],
  problemIds: [{ required: true, type: 'array', message: '请选择题目', trigger: 'change' }],
}

const languageOptions = (['java', 'python', 'cpp', 'javascript', 'go', 'rust'] as AssessmentLanguage[]).map(
  (value) => ({ value, label: languageLabel(value) }),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    await directions.ensureLoaded()
    problems.value = (await listAssessmentProblems()).items.filter((problem) => problem.status !== 'RETIRED')
    if (assessmentId.value) {
      assessment.value = await getAssessment(assessmentId.value)
      form.title = assessment.value.title
      form.description = assessment.value.description
      form.directionId = assessment.value.directionId
      form.startAt = assessment.value.startAt
      form.endAt = assessment.value.endAt
      form.allowedLanguages = [...assessment.value.allowedLanguages]
      form.problemIds = [...assessment.value.problemIds]
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
  if (form.problemIds.length === 0) {
    ElMessage.warning('请至少选择一道题目')
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
      problemIds: form.problemIds,
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
        <el-input v-model="form.title" maxlength="120" show-word-limit />
      </el-form-item>
      <el-form-item label="说明" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="5" maxlength="4000" show-word-limit />
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
      <el-form-item label="题目" prop="problemIds">
        <el-select v-model="form.problemIds" multiple style="width: 100%">
          <el-option
            v-for="problem in problems"
            :key="problem.id"
            :label="`${problem.title}（${problem.difficulty}）`"
            :value="problem.id"
          />
        </el-select>
      </el-form-item>
      <div class="form-actions">
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
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
</style>
