<script setup lang="ts">
/**
 * 日报编辑页：新建草稿 / 编辑 / 查看 + 提交。
 *
 * 契约要点（docs/api/openapi.yaml 日报 tag）：
 *   - 每成员每日一份（含草稿），重复创建 409 REPORT_ALREADY_EXISTS；
 *   - 编辑需要 version 乐观锁，409 VERSION_CONFLICT 时重取后提示用户；
 *   - 提交 = 请求评分开始：status=SUBMITTED、scoreStatus=QUEUED、入账保底 1 分，
 *     finalScore 仍为 null（评分中是合法状态，不是 0 分）；
 *   - 仅 editable=true 时可编辑；POST 必须带 Idempotency-Key。
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
import type { DailyReport } from '@/api/types'

const route = useRoute()
const router = useRouter()

/** 无 reportId 参数即「新建」。 */
const reportId = computed(() => (typeof route.params.reportId === 'string' ? route.params.reportId : null))
const isNew = computed(() => reportId.value === null)

const report = ref<DailyReport | null>(null)
const loading = ref(false)
const saving = ref(false)
const submitting = ref(false)

const formRef = ref<FormInstance>()
const form = reactive({
  reportDate: '',
  learningContent: '',
  gains: '',
  problems: '',
  nextPlan: '',
  linksText: '',
})

const rules: FormRules = {
  reportDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
}

/** 是否可编辑：草稿永远可以，已提交看 editable 标志（与截止时间）。 */
const editable = computed(() => (isNew.value ? true : (report.value?.editable ?? false)))

function today(): string {
  const d = new Date(Date.now() + 8 * 3600_000)
  return d.toISOString().slice(0, 10)
}

async function load(): Promise<void> {
  if (isNew.value) {
    form.reportDate = today()
    return
  }
  loading.value = true
  try {
    const r = await getDailyReport(reportId.value!)
    report.value = r
    form.reportDate = r.reportDate
    form.learningContent = r.learningContent
    form.gains = r.gains
    form.problems = r.problems ?? ''
    form.nextPlan = r.nextPlan ?? ''
    form.linksText = r.links.join('\n')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function parseLinks(): string[] {
  return form.linksText
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 生成幂等键：一次编辑会话内复用一个，提交动作每次换新。 */
const editKey = crypto.randomUUID()

async function saveDraft(): Promise<void> {
  saving.value = true
  try {
    const payload = {
      learningContent: form.learningContent,
      gains: form.gains,
      problems: form.problems || null,
      nextPlan: form.nextPlan || null,
      links: parseLinks(),
    }
    if (isNew.value) {
      const valid = await formRef.value?.validate().catch(() => false)
      if (!valid) return
      const created = await createDailyReport({ reportDate: form.reportDate, ...payload }, editKey)
      report.value = created
      ElMessage.success('草稿已保存')
      await router.replace({ name: 'daily-report-detail', params: { reportId: created.id } })
    } else {
      const updated = await updateDailyReport(reportId.value!, {
        version: report.value!.version,
        ...payload,
      })
      report.value = updated
      ElMessage.success('已保存')
    }
  } catch (error) {
    const apiError = toApiError(error)
    // 乐观锁冲突：重取后由用户决定是否重新提交，绝不盲目覆盖。
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
  if (!form.learningContent.trim() || !form.gains.trim()) {
    ElMessage.warning('提交前请填写「学习内容」与「收获」')
    return
  }
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
    // 提交对报告内容的最后一次修改先落盘，再发提交。
    // 若 version 已过期，update 会 409，此时重取并提示。
    const updated = await updateDailyReport(reportId.value!, {
      version: report.value!.version,
      learningContent: form.learningContent,
      gains: form.gains,
      problems: form.problems || null,
      nextPlan: form.nextPlan || null,
      links: parseLinks(),
    })
    report.value = updated

    const submitted = await submitDailyReport(
      reportId.value!,
      updated.version,
      crypto.randomUUID(),
    )
    report.value = submitted
    // 提交后表单重新同步为正式内容。
    form.learningContent = submitted.learningContent
    form.gains = submitted.gains
    ElMessage.success('已正式提交，保底积分已入账，评分排队中')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('报告已被其他操作更新，已为你刷新，请确认后再提交')
    } else if (apiError.isAlreadyExists) {
      ElMessage.error(describeError(apiError))
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
      <el-form-item label="报告日期" prop="reportDate">
        <el-date-picker
          v-model="form.reportDate"
          type="date"
          value-format="YYYY-MM-DD"
          :disabled="!isNew"
          placeholder="选择日期"
        />
        <div class="report__tip">每成员每天最多一份日报（含草稿）。</div>
      </el-form-item>

      <el-form-item label="学习内容（提交必填）" prop="learningContent">
        <el-input
          v-model="form.learningContent"
          type="textarea"
          :rows="4"
          :disabled="!editable"
          data-test="learning-content"
        />
      </el-form-item>

      <el-form-item label="收获（提交必填）" prop="gains">
        <el-input v-model="form.gains" type="textarea" :rows="3" :disabled="!editable" />
      </el-form-item>

      <el-form-item label="遇到的问题">
        <el-input v-model="form.problems" type="textarea" :rows="2" :disabled="!editable" />
      </el-form-item>

      <el-form-item label="下一步计划">
        <el-input v-model="form.nextPlan" type="textarea" :rows="2" :disabled="!editable" />
      </el-form-item>

      <el-form-item label="相关链接（每行一个）">
        <el-input v-model="form.linksText" type="textarea" :rows="2" :disabled="!editable" />
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
