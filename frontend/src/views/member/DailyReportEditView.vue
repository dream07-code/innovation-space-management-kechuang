<script setup lang="ts">
/**
 * 日报编辑页：新建草稿 / 编辑 / 查看 + 提交。
 *
 * 表单只包含学习内容、收获、遇到的问题。
 * 日期由后端按当天生成；nextPlan/links 仅保留在旧 API 兼容层。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import {
  createDailyReport,
  getDailyReport,
  submitDailyReport,
  updateDailyReport,
} from '@/api/modules/reports'
import { describeError, toApiError } from '@/api/errors'
import { REPORT_STATUS_META, SCORE_STATUS_META } from '@/components/statusMeta'
import {
  buildCreateDailyReportPayload,
  buildUpdateDailyReportPayload,
  validateDailyReportForm,
  type DailyReportFormValues,
} from '@/views/member/dailyReportPayload'
import ReportImageUploader from '@/components/ReportImageUploader.vue'
import type { DailyReport, ReportImageAnchor } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const route = useRoute()
const router = useRouter()

const reportId = computed(() => (typeof route.params.reportId === 'string' ? route.params.reportId : null))
const isNew = computed(() => reportId.value === null)

const report = ref<DailyReport | null>(null)
const loading = ref(false)
const saving = ref(false)
const submitting = ref(false)

const formRef = ref<FormInstance>()
const form = reactive<DailyReportFormValues>({
  learningContent: '',
  gains: '',
  problems: '',
  images: [],
})

/** 图片可以统一放文末，也可以跟在某段正文之后。 */
const IMAGE_ANCHORS: Array<{ value: ReportImageAnchor; label: string }> = [
  { value: 'learningContent', label: '放在「学习内容」后' },
  { value: 'gains', label: '放在「收获」后' },
  { value: 'problems', label: '放在「问题」后' },
  { value: 'end', label: '放在文末' },
]

const rules: FormRules = {
  learningContent: [{ required: true, message: '请输入学习内容', trigger: 'blur' }],
}

const editable = computed(() => (isNew.value ? true : (report.value?.editable ?? false)))

async function validateForm(): Promise<boolean> {
  const errors = validateDailyReportForm(form)
  const firstError = errors.learningContent ?? errors.gains ?? errors.problems
  if (firstError) {
    ElMessage.warning(firstError)
    return false
  }
  return (await formRef.value?.validate().catch(() => false)) ?? false
}

async function load(): Promise<void> {
  if (isNew.value) return
  loading.value = true
  try {
    const loaded = await getDailyReport(reportId.value!)
    report.value = loaded
    form.learningContent = loaded.learningContent
    form.gains = loaded.gains ?? ''
    form.problems = loaded.problems ?? ''
    form.images = loaded.images ?? []
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

const editKey = newIdempotencyKey()

async function saveDraft(): Promise<void> {
  if (!editable.value || !(await validateForm())) return

  saving.value = true
  try {
    if (isNew.value) {
      const created = await createDailyReport(buildCreateDailyReportPayload(form), editKey)
      report.value = created
      ElMessage.success('草稿已保存')
      await router.replace({ name: 'daily-report-detail', params: { reportId: created.id } })
      return
    }

    const updated = await updateDailyReport(
      reportId.value!,
      buildUpdateDailyReportPayload(report.value!.version, form),
    )
    report.value = updated
    ElMessage.success('已保存')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict && !isNew.value) {
      await load()
      ElMessage.warning('报告已被其他操作更新，已为你刷新到最新版本，请确认后再保存')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    saving.value = false
  }
}

async function submit(): Promise<void> {
  if (isNew.value) {
    ElMessage.warning('请先保存草稿再提交')
    return
  }
  if (!(await validateForm())) return

  try {
    await ElMessageBox.confirm(
      '提交后将入账保底积分并开始评分；评分完成前积分可能继续变动。',
      '确认正式提交？',
      { confirmButtonText: '提交', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }

  submitting.value = true
  try {
    const updated = await updateDailyReport(
      reportId.value!,
      buildUpdateDailyReportPayload(report.value!.version, form),
    )
    report.value = updated

    report.value = await submitDailyReport(
      reportId.value!,
      updated.version,
      newIdempotencyKey(),
    )
    form.learningContent = report.value.learningContent
    form.gains = report.value.gains ?? ''
    form.problems = report.value.problems ?? ''
    form.images = report.value.images ?? []
    ElMessage.success('已正式提交，保底积分已入账，评分排队中')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('报告已被其他操作更新，已为你刷新，请确认后再提交')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="report__header">
        <span>{{ isNew ? '写日报' : `日报 · ${report?.reportDate ?? ''}` }}</span>
        <template v-if="report">
          <el-tag :type="REPORT_STATUS_META[report.status].type" effect="plain">
            {{ REPORT_STATUS_META[report.status].label }}
          </el-tag>
          <el-tag :type="SCORE_STATUS_META[report.scoreStatus].type" effect="plain">
            {{ SCORE_STATUS_META[report.scoreStatus].label }}
          </el-tag>
        </template>
      </div>
    </template>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="学习内容（必填）" prop="learningContent">
        <el-input
          v-model="form.learningContent"
          type="textarea"
          :rows="5"
          :disabled="!editable"
          maxlength="10000"
          show-word-limit
          placeholder="今天学习了什么？"
          data-test="learning-content"
        />
      </el-form-item>

      <el-form-item label="收获（选填）">
        <el-input
          v-model="form.gains"
          type="textarea"
          :rows="4"
          :disabled="!editable"
          maxlength="10000"
          show-word-limit
          placeholder="理解了什么，有哪些收获？"
          data-test="gains"
        />
      </el-form-item>

      <el-form-item label="遇到的问题（选填）">
        <el-input
          v-model="form.problems"
          type="textarea"
          :rows="3"
          :disabled="!editable"
          maxlength="5000"
          show-word-limit
          placeholder="还有哪些问题需要继续解决？"
          data-test="problems"
        />
      </el-form-item>

      <el-form-item label="笔记图片（选填）">
        <ReportImageUploader
          :model-value="form.images ?? []"
          :anchors="IMAGE_ANCHORS"
          :disabled="!editable"
          @update:model-value="(next) => (form.images = next)"
        />
      </el-form-item>

      <template v-if="report">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="报告日期">{{ report.reportDate }}</el-descriptions-item>
          <el-descriptions-item label="保底积分">{{ report.awardedPoints }}</el-descriptions-item>
          <el-descriptions-item label="最终评分">
            {{ report.finalScore !== null ? `${report.finalScore} 分` : SCORE_STATUS_META[report.scoreStatus].label }}
          </el-descriptions-item>
          <el-descriptions-item label="评分说明">{{ report.scoreReason ?? '—' }}</el-descriptions-item>
        </el-descriptions>
      </template>

      <div class="report__actions">
        <el-button :loading="saving" :disabled="!editable" data-test="save-draft" @click="saveDraft">
          {{ isNew ? '保存草稿' : '保存修改' }}
        </el-button>
        <el-button
          v-if="!isNew && report?.status !== 'SUBMITTED'"
          type="primary"
          :loading="submitting"
          data-test="submit-report"
          @click="submit"
        >
          正式提交
        </el-button>
        <el-button @click="router.back()">返回</el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.report__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.report__actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}
</style>
