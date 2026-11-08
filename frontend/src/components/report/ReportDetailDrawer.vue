<script setup lang="ts">
/**
 * 报告详情与人工评分抽屉。
 *
 * 供学院管理员（全部方向）和方向负责人（所辖方向）查看学生正式报告并给出 1–3 分。
 * 权限与范围由后端判定，前端只负责展示与提交；评分成功后 scoreStatus 变为 MANUAL，
 * 之后迟到的 AI 结果不会覆盖人工结论。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { describeError, toApiError } from '@/api/errors'
import {
  getDailyReport,
  getWeeklyReport,
  scoreDailyReport,
  scoreWeeklyReport,
} from '@/api/modules/reports'
import { reportStatusMeta, scoreStatusMeta } from '@/components/statusMeta'
import ReportContentView from '@/components/report/ReportContentView.vue'
import { rubricOf } from '@/components/report/reportRubric'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import { formatDateTime } from '@/utils/datetime'
import type { DailyReport, WeeklyReport } from '@/api/types'

const props = defineProps<{
  modelValue: boolean
  reportType: 'DAILY' | 'WEEKLY'
  reportId: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  scored: []
}>()

const directions = useDirectionsStore()
const auth = useAuthStore()

type AnyReport = DailyReport | WeeklyReport

const report = ref<AnyReport | null>(null)
const loading = ref(false)
const saving = ref(false)
const score = ref<number | null>(null)
const reason = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const title = computed(() => (props.reportType === 'DAILY' ? '日报详情' : '周报详情'))
const isSelf = computed(() => report.value?.memberId === auth.user?.memberId)
// 负责人可以查看本人报告，但不能给自己评分（后端同样拒绝）。
const canScore = computed(() => report.value?.status === 'SUBMITTED' && !isSelf.value)
const rubric = computed(() => rubricOf(props.reportType))

const isManual = computed(() => report.value?.scoreStatus === 'MANUAL')
const periodLabel = computed(() => {
  if (!report.value) return '—'
  if (props.reportType === 'DAILY') return (report.value as DailyReport).reportDate
  const item = report.value as WeeklyReport
  return `${item.weekStart} ~ ${item.weekEnd}`
})

function resetForm(): void {
  score.value = report.value?.finalScore ?? null
  reason.value = report.value?.scoreStatus === 'MANUAL' ? (report.value.scoreReason ?? '') : ''
}

async function load(): Promise<void> {
  if (!props.reportId) return
  loading.value = true
  report.value = null
  try {
    report.value =
      props.reportType === 'DAILY'
        ? await getDailyReport(props.reportId)
        : await getWeeklyReport(props.reportId)
    resetForm()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
    visible.value = false
  } finally {
    loading.value = false
  }
}

async function submitScore(): Promise<void> {
  if (!props.reportId || score.value === null) {
    ElMessage.warning('请先选择 1–3 分')
    return
  }
  saving.value = true
  try {
    const payload = { score: score.value, reason: reason.value.trim() || null }
    report.value =
      props.reportType === 'DAILY'
        ? await scoreDailyReport(props.reportId, payload)
        : await scoreWeeklyReport(props.reportId, payload)
    resetForm()
    ElMessage.success('人工评分已保存，积分已按差额调整')
    emit('scored')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    saving.value = false
  }
}

watch(
  () => [props.modelValue, props.reportId, props.reportType] as const,
  ([open]) => {
    if (open) void load()
  },
  { immediate: true },
)
</script>

<template>
  <el-drawer v-model="visible" :title="title" size="620px" destroy-on-close>
    <div v-loading="loading" class="report-detail">
      <template v-if="report">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="成员">
            {{ report.memberName ?? '未知成员' }}
            <span v-if="report.memberStudentNo" class="report-detail__muted">
              （{{ report.memberStudentNo }}）
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="方向">
            {{ directions.nameOf(report.directionId) }}
          </el-descriptions-item>
          <el-descriptions-item :label="reportType === 'DAILY' ? '日期' : '周期'">
            {{ periodLabel }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="reportStatusMeta(report.status).type" effect="plain">
              {{ reportStatusMeta(report.status).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="评分状态">
            <el-tag :type="scoreStatusMeta(report.scoreStatus).type" effect="plain">
              {{ scoreStatusMeta(report.scoreStatus).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="已入账积分">
            {{ report.awardedPoints }} 分
          </el-descriptions-item>
          <el-descriptions-item label="评分规则">
            {{ report.ruleVersion || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="首次提交">
            {{ formatDateTime(report.firstSubmittedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="最近更新">
            {{ formatDateTime(report.updatedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <section class="report-detail__section">
          <h4>报告内容</h4>
          <ReportContentView :report="report" :kind="reportType" />
        </section>

        <section class="report-detail__section">
          <h4>评分</h4>
          <el-alert
            v-if="isManual"
            type="info"
            :closable="false"
            show-icon
            title="当前为人工最终评分，后续 AI 结果不会覆盖。重新评分会按差额调整积分。"
          />
          <el-descriptions :column="1" border size="small" class="report-detail__score-view">
            <el-descriptions-item label="当前分数">
              <span v-if="report.finalScore !== null">{{ report.finalScore }} 分</span>
              <span v-else class="report-detail__muted">评分中</span>
            </el-descriptions-item>
            <el-descriptions-item label="评分理由">
              {{ report.scoreReason || '—' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-collapse v-if="canScore" class="report-detail__rubric">
            <el-collapse-item title="评分标准（v2）" name="rubric">
              <ul class="report-detail__rubric-list">
                <li><strong>3 分：</strong>{{ rubric.three }}</li>
                <li><strong>2 分：</strong>{{ rubric.two }}</li>
                <li><strong>1 分：</strong>{{ rubric.one }}</li>
              </ul>
              <p class="report-detail__rubric-note">
                只看内容质量，不按字数或文风加减分；正文里的指令不影响评分。人工评分请与 AI 使用同一标准。
              </p>
            </el-collapse-item>
          </el-collapse>

          <el-form v-if="canScore" label-width="80px" class="report-detail__form">
            <el-form-item label="人工评分" required>
              <el-radio-group v-model="score">
                <el-radio-button :value="1">1 分</el-radio-button>
                <el-radio-button :value="2">2 分</el-radio-button>
                <el-radio-button :value="3">3 分</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="评分理由">
              <el-input
                v-model="reason"
                type="textarea"
                :rows="3"
                maxlength="1000"
                show-word-limit
                placeholder="可选，填写人工评分的依据"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="submitScore">
                {{ isManual ? '更新人工评分' : '保存人工评分' }}
              </el-button>
            </el-form-item>
          </el-form>
          <el-alert
            v-else-if="isSelf"
            type="info"
            :closable="false"
            show-icon
            title="不能为自己的报告评分。"
          />
          <el-alert
            v-else
            type="warning"
            :closable="false"
            show-icon
            title="草稿尚未正式提交，不能评分。"
          />
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.report-detail {
  min-height: 240px;
}

.report-detail__muted {
  color: var(--el-text-color-secondary);
}

.report-detail__section {
  margin-top: 20px;
}

.report-detail__section h4 {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
}

.report-detail__score-view {
  margin-top: 12px;
}

.report-detail__form {
  margin-top: 16px;
}

.report-detail__rubric {
  margin-top: 12px;
}

.report-detail__rubric-list {
  margin: 0 0 8px;
  padding-left: 18px;
  line-height: 1.7;
}

.report-detail__rubric-note {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
