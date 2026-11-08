<script setup lang="ts">
/**
 * 周报编辑页：新建草稿 / 编辑 / 查看 + 提交。
 *
 * 契约要点（docs/api/openapi.yaml 周报 tag）：
 *   - 每成员每自然周一份（含草稿），weekStart 必须为周一；
 *   - 编辑需要 version 乐观锁，409 VERSION_CONFLICT 时重取后提示用户；
 *   - 提交 = 请求评分开始：status=SUBMITTED、scoreStatus=QUEUED、入账保底积分，
 *     finalScore 仍为 null（评分中是合法状态，不是 0 分）；
 *   - 仅 editable=true 时可编辑；POST 必须带 Idempotency-Key。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import {
  createWeeklyReport,
  getWeeklyReport,
  submitWeeklyReport,
  updateWeeklyReport,
} from '@/api/modules/reports'
import { describeError, toApiError } from '@/api/errors'
import { REPORT_STATUS_META, SCORE_STATUS_META } from '@/components/statusMeta'
import ReportImageUploader from '@/components/ReportImageUploader.vue'
import type { ReportImage, ReportImageAnchor, WeeklyReport } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'
import { isWeekStart, parseLinkLines } from './weeklyReportForm'

const route = useRoute()
const router = useRouter()

/** 无 reportId 参数即「新建」。 */
const reportId = computed(() => (typeof route.params.reportId === 'string' ? route.params.reportId : null))
const isNew = computed(() => reportId.value === null)

const report = ref<WeeklyReport | null>(null)
const loading = ref(false)
const saving = ref(false)
const submitting = ref(false)

const formRef = ref<FormInstance>()
const form = reactive({
  weekStart: '',
  summary: '',
  achievements: '',
  reflections: '',
  nextWeekPlan: '',
  linksText: '',
  images: [] as ReportImage[],
})

/** 图片可以统一放文末，也可以跟在某段正文之后。 */
const IMAGE_ANCHORS: Array<{ value: ReportImageAnchor; label: string }> = [
  { value: 'summary', label: '放在「总结」后' },
  { value: 'achievements', label: '放在「成果」后' },
  { value: 'reflections', label: '放在「反思」后' },
  { value: 'nextWeekPlan', label: '放在「下周计划」后' },
  { value: 'end', label: '放在文末' },
]

const rules: FormRules = {
  weekStart: [
    { required: true, message: '请选择周', trigger: 'change' },
    {
      // 后端只认周一；组件层已经禁用其他星期，这里挡住手输的日期。
      validator: (_rule, value: string, callback) =>
        isWeekStart(value) ? callback() : callback(new Error('周开始必须是周一')),
      trigger: 'change',
    },
  ],
  linksText: [
    {
      validator: (_rule, value: string, callback) => {
        const { invalidLines, tooMany } = parseLinkLines(value ?? '')
        if (invalidLines.length) {
          return callback(new Error(`第 ${invalidLines.join('、')} 行不是有效链接，需以 http:// 或 https:// 开头`))
        }
        if (tooMany) return callback(new Error('最多填写 10 条链接'))
        return callback()
      },
      trigger: 'blur',
    },
  ],
}

/** 周报只能选周一：其他星期选不出来，避免提交后才收到「内容不合法」。 */
function notMonday(date: Date): boolean {
  return date.getDay() !== 1
}

/** 是否可编辑：草稿永远可以，已提交看 editable 标志。 */
const editable = computed(() => (isNew.value ? true : (report.value?.editable ?? false)))

/** 本周一的日期 YYYY-MM-DD（+08:00）。 */
function thisWeekMonday(): string {
  const d = new Date(Date.now() + 8 * 3600_000)
  const day = d.getUTCDay()
  const diff = day === 0 ? -6 : 1 - day
  d.setUTCDate(d.getUTCDate() + diff)
  return d.toISOString().slice(0, 10)
}

async function load(): Promise<void> {
  if (isNew.value) {
    form.weekStart = thisWeekMonday()
    return
  }
  loading.value = true
  try {
    const r = await getWeeklyReport(reportId.value!)
    report.value = r
    form.weekStart = r.weekStart
    form.summary = r.summary
    form.achievements = r.achievements
    form.reflections = r.reflections ?? ''
    form.nextWeekPlan = r.nextWeekPlan ?? ''
    form.linksText = r.links.join('\n')
    form.images = r.images ?? []
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

/** 保存与提交共用的前置校验：不通过就把用户指到具体那一行，不发请求。 */
async function validateForm(): Promise<string[] | null> {
  // el-form 的规则给出的是行内红字；这里再做一遍硬校验，保证无论校验库行为如何都不会把
  // 后端必拒的载荷发出去（单元测试环境里 Element Plus 的规则不执行，只有这层能测到）。
  const { links, invalidLines, tooMany } = parseLinkLines(form.linksText)
  if (invalidLines.length) {
    ElMessage.warning(`第 ${invalidLines.join('、')} 行不是有效链接，需以 http:// 或 https:// 开头`)
    return null
  }
  if (tooMany) {
    ElMessage.warning('最多填写 10 条链接')
    return null
  }
  if (!isWeekStart(form.weekStart)) {
    ElMessage.warning('周开始必须是周一')
    return null
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return null
  return links
}

/** 生成幂等键：一次编辑会话内复用一个。 */
const editKey = newIdempotencyKey()

async function saveDraft(): Promise<void> {
  const links = await validateForm()
  if (!links) return
  saving.value = true
  try {
    const payload = {
      summary: form.summary,
      achievements: form.achievements,
      reflections: form.reflections || null,
      nextWeekPlan: form.nextWeekPlan || null,
      links,
      images: form.images,
    }
    if (isNew.value) {
      const created = await createWeeklyReport({ weekStart: form.weekStart, ...payload }, editKey)
      report.value = created
      ElMessage.success('草稿已保存')
      await router.replace({ name: 'weekly-report-detail', params: { reportId: created.id } })
    } else {
      const updated = await updateWeeklyReport(reportId.value!, {
        version: report.value!.version,
        ...payload,
      })
      report.value = updated
      ElMessage.success('已保存')
    }
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict && !isNew.value) {
      await load()
      ElMessage.warning('报告已被其他操作更新，已为你刷新到最新版本，请确认后再提交')
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
  if (!form.summary.trim() || !form.achievements.trim()) {
    ElMessage.warning('提交前请填写「总结」与「成果」')
    return
  }
  const links = await validateForm()
  if (!links) return
  try {
    await ElMessageBox.confirm(
      '提交后将入账保底积分并开始评分；评分完成前积分可能继续变动。',
      '确认正式提交？',
      { confirmButtonText: '提交', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return // 用户取消
  }

  submitting.value = true
  try {
    const updated = await updateWeeklyReport(reportId.value!, {
      version: report.value!.version,
      summary: form.summary,
      achievements: form.achievements,
      reflections: form.reflections || null,
      nextWeekPlan: form.nextWeekPlan || null,
      links,
      images: form.images,
    })
    report.value = updated

    const submitted = await submitWeeklyReport(
      reportId.value!,
      updated.version,
      newIdempotencyKey(),
    )
    report.value = submitted
    form.summary = submitted.summary
    form.achievements = submitted.achievements
    form.images = submitted.images ?? []
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
        <span>{{ isNew ? '写周报' : `周报 · ${report?.weekStart ?? ''} 至 ${report?.weekEnd ?? ''}` }}</span>
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
      <el-form-item label="周开始（必须是周一）" prop="weekStart" data-test="week-start">
        <el-date-picker
          v-model="form.weekStart"
          type="date"
          format="YYYY-MM-DD"
          value-format="YYYY-MM-DD"
          :clearable="false"
          :disabled="!isNew"
          :disabled-date="notMonday"
          placeholder="选择周一"
        />
        <div class="report__tip">每成员每周最多一份周报（含草稿），只能选周一。</div>
      </el-form-item>

      <el-form-item label="总结（提交必填）" prop="summary">
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="4"
          :disabled="!editable"
          maxlength="10000"
          show-word-limit
          data-test="summary"
        />
      </el-form-item>

      <el-form-item label="成果（提交必填）" prop="achievements">
        <el-input
          v-model="form.achievements"
          type="textarea"
          :rows="3"
          :disabled="!editable"
          maxlength="10000"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="反思">
        <el-input
          v-model="form.reflections"
          type="textarea"
          :rows="2"
          :disabled="!editable"
          maxlength="5000"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="下周计划">
        <el-input
          v-model="form.nextWeekPlan"
          type="textarea"
          :rows="2"
          :disabled="!editable"
          maxlength="5000"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="相关链接（每行一个，需以 http:// 或 https:// 开头）" prop="linksText" data-test="links">
        <el-input
          v-model="form.linksText"
          type="textarea"
          :rows="2"
          :disabled="!editable"
          maxlength="6000"
        />
      </el-form-item>

      <el-form-item label="笔记图片（选填）">
        <ReportImageUploader v-model="form.images" :anchors="IMAGE_ANCHORS" :disabled="!editable" />
      </el-form-item>

      <template v-if="report">
        <el-descriptions :column="3" border size="small">
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

.report__tip {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.report__actions {
  margin-top: 1rem;
  display: flex;
  gap: 0.75rem;
}
</style>
