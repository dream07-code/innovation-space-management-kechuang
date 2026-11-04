<script setup lang="ts">
/**
 * OJ 考核结果（独立成绩单）。
 *
 * OJ 技术判定、AI 评分、人工评分分别展示；人工评分优先。
 * 结果不写入日报/周报积分流水，也不改变现有排行榜或淘汰状态。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, EditPen, View } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

import SubmissionCodeDialog from '@/components/SubmissionCodeDialog.vue'

import {
  getAssessment,
  listAssessmentMemberScores,
  listAssessmentProblems,
  listAssessmentResults,
  publishAssessmentResults,
} from '@/api/modules/oj'
import { listAnswerSheets, getQuizReviewSummary } from '@/api/modules/quiz'
import { exportMemberScores } from '@/utils/scoreExport' 
import { describeError, toApiError } from '@/api/errors'
import {
  assessmentStatusMeta,
  resultStatusMeta,
  scoreLabel,
  scoreSourceMeta,
  verdictMeta,
} from '@/components/ojMeta'
import type {
  Assessment,
  AssessmentMemberScore,
  AssessmentProblem,
  AssessmentResult,
  AnswerSheetSummary,
  PageData,
  QuizReviewSummary,
} from '@/api/types'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const assessmentId = computed(() => route.params.assessmentId as string)
const assessment = ref<Assessment | null>(null)
const problems = ref<AssessmentProblem[]>([])
const data = ref<PageData<AssessmentResult> | null>(null)
const memberScores = ref<PageData<AssessmentMemberScore> | null>(null)
const loading = ref(false)
const scoreSource = ref('')
/** 结果有两种看法：一次提交一行，或一个人一行（整场考核的总分）。 */
const viewMode = ref<'submission' | 'member'>('member')
const page = ref(1)
const memberPage = ref(1)
const pageSize = ref(20)
/** 按成员看明细时的过滤条件，null 表示看全部提交。 */
const memberFilter = ref<{ id: string; name: string } | null>(null)
const codeDialogVisible = ref(false)
const codeSubmissionId = ref<string | null>(null)
/** 答题卡下钻（仅负责人/管理员）：某学生客观题逐题对错与解析。 */
const sheetVisible = ref(false)
const sheetLoading = ref(false)
const sheetDetail = ref<AnswerSheetSummary | null>(null)

function answerText(value: unknown): string {
  if (typeof value === 'boolean') return value ? '对' : '错'
  if (Array.isArray(value)) return value.join('')
  return value == null ? '—' : String(value)
}

async function openSheet(row: AssessmentMemberScore): Promise<void> {
  sheetVisible.value = true
  sheetLoading.value = true
  sheetDetail.value = null
  try {
    const page = await listAnswerSheets(assessmentId.value, { page: 1, size: 1, memberId: row.memberId })
    sheetDetail.value = page.items[0] ?? null
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    sheetLoading.value = false
  }
}

function openCode(submissionId: string): void {
  codeSubmissionId.value = submissionId
  codeDialogVisible.value = true
}

/** 管理员和方向负责人可进入人工批阅，评完回到当前结果页。 */
const canReview = computed(() => auth.isAdmin || auth.isLeader)

const reviewSummary = ref<QuizReviewSummary | null>(null)
const publishing = ref(false)
const resultsPublished = computed(() => assessment.value?.resultsPublished ?? false)
const pendingQuiz = computed(() => reviewSummary.value?.pendingItems ?? 0)
const canPublish = computed(() => canReview.value && (reviewSummary.value?.canPublish ?? false))

async function loadReviewSummary(): Promise<void> {
  if (!canReview.value) return
  try {
    reviewSummary.value = await getQuizReviewSummary(assessmentId.value)
  } catch {
    reviewSummary.value = null
  }
}

function openQuizReview(): void {
  void router.push({
    name: 'oj-assessment-quiz-review',
    params: { assessmentId: assessmentId.value },
  })
}

async function onPublish(): Promise<void> {
  if (!assessment.value) return
  try {
    await ElMessageBox.confirm(
      '发布后学生即可看到分数与评语，阅卷随即锁定，积分按最终成绩结算。确认发布？',
      '发布成绩',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  publishing.value = true
  try {
    await publishAssessmentResults(
      assessmentId.value,
      { version: Number(assessment.value.version) },
      crypto.randomUUID(),
    )
    ElMessage.success('成绩已发布')
    await Promise.all([loadBase(), loadReviewSummary(), loadMemberScores()])
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    publishing.value = false
  }
}

const exporting = ref(false)
async function onExport(): Promise<void> {
  if (!assessment.value) return
  exporting.value = true
  try {
    await exportMemberScores(assessmentId.value, assessment.value.title)
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    exporting.value = false
  }
}

function openReview(submissionId: string): void {
  if (!submissionId) return
  // 评分按钮只在「按提交」视图出现；把当前视图与成员过滤一并带进 redirect，
  // 评完才能回到「只看某学生」的列表，而不是回落到按学生汇总。
  const params = new URLSearchParams({ view: 'submission' })
  if (memberFilter.value) {
    params.set('member', memberFilter.value.id)
    params.set('memberName', memberFilter.value.name)
  }
  const redirect = encodeURIComponent(
    `/oj/assessments/${assessmentId.value}/results?${params.toString()}`,
  )
  void router.push(`/oj/submissions/${submissionId}/review?redirect=${redirect}`)
}

const problemNames = computed(() => new Map(problems.value.map((item) => [item.id, item.title])))
const items = computed(() =>
  (data.value?.items ?? []).filter((item) => !scoreSource.value || item.scoreSource === scoreSource.value),
)
/** 还没有分数的记录：判题内部错误等，需要人工评分。 */
const pendingCount = computed(
  () => items.value.filter((item) => item.scoreSource === 'PENDING').length,
)

async function loadSubmission(): Promise<void> {
  data.value = await listAssessmentResults(assessmentId.value, {
    page: page.value,
    size: pageSize.value,
    memberId: memberFilter.value?.id,
  })
}

async function loadMemberScores(): Promise<void> {
  memberScores.value = await listAssessmentMemberScores(assessmentId.value, {
    page: memberPage.value,
    size: pageSize.value,
  })
}

/**
 * 视图状态唯一来源是路由 query：明细下钻、评分返回、浏览器前进后退
 * 都走同一套驱动。这样左上「返回」在明细视图先回到汇总视图，再点才
 * 离开本页；人工评分评完也能精确回到「只看某学生」的列表。
 */
function applyRouteQuery(force = false): void {
  const view = route.query.view === 'submission' ? 'submission' : 'member'
  const memberId = typeof route.query.member === 'string' ? route.query.member : ''
  const memberName =
    typeof route.query.memberName === 'string' ? route.query.memberName : memberId
  const nextFilter = memberId ? { id: memberId, name: memberName } : null
  const modeChanged = viewMode.value !== view
  const filterChanged = (memberFilter.value?.id ?? null) !== (nextFilter?.id ?? null)
  viewMode.value = view
  memberFilter.value = nextFilter
  if (force || modeChanged || filterChanged) {
    page.value = 1
    if (view === 'submission') void loadSubmission()
    else void loadMemberScores()
  }
}

watch(() => route.query, () => applyRouteQuery(), { deep: true })

async function switchQuery(query: Record<string, string>): Promise<void> {
  await router.push({ query })
}

/** 汇总表点「明细」：以路由 query 记录下钻状态，返回键可回到汇总。 */
async function openMemberDetail(row: AssessmentMemberScore): Promise<void> {
  await switchQuery({
    view: 'submission',
    member: row.memberId,
    memberName: row.memberName ?? row.memberStudentNo ?? row.memberId,
  })
}

function onModeChange(value: string | number | boolean | undefined): void {
  void switchQuery(value === 'submission' ? { view: 'submission' } : {})
}

/** 点「只看 xx ×」：清除成员过滤，停在按提交·全部成员。 */
async function clearMemberFilter(): Promise<void> {
  await switchQuery({ view: 'submission' })
}

onMounted(() => {
  void Promise.all([loadBase(), Promise.resolve()]).then(() => applyRouteQuery(true))
})

/** 考核与题目列表加载（与视图无关），视图取数由 applyRouteQuery 驱动。 */
async function loadBase(): Promise<void> {
  loading.value = true
  try {
    const [currentAssessment, problemPage] = await Promise.all([
      getAssessment(assessmentId.value),
      listAssessmentProblems(),
    ])
    assessment.value = currentAssessment
    problems.value = problemPage.items
    if (canReview.value) void loadReviewSummary()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <span>{{ assessment?.title ?? '考核结果' }}</span>
        <div class="page__header-actions">
          <el-tag v-if="assessment" :type="assessmentStatusMeta(assessment.status).type" effect="plain">
            {{ assessmentStatusMeta(assessment.status).label }}
          </el-tag>
          <el-tag v-if="resultsPublished" type="success" effect="plain" data-test="published-tag">
            成绩已发布
          </el-tag>
          <template v-if="canReview">
            <el-button data-test="open-quiz-review" @click="openQuizReview">填空阅卷</el-button>
            <el-button
              v-if="!resultsPublished"
              type="primary"
              data-test="publish"
              :loading="publishing"
              :disabled="!canPublish"
              @click="onPublish"
            >
              发布成绩{{ !canPublish && pendingQuiz ? `（还有 ${pendingQuiz} 条未批）` : '' }}
            </el-button>
          </template>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" show-icon class="result-notice">
      自动分按通过的测试点比例计算（四舍五入到整数）；人工评分优先，可随时调整。本页不计入现有积分、流水或排行榜。
    </el-alert>

    <el-alert
      v-if="auth.isStudent && !resultsPublished"
      type="info"
      :closable="false"
      show-icon
      class="result-notice"
      data-test="results-pending"
      title="考核已结束，成绩待公布"
      description="老师完成阅卷后会统一发布成绩，届时可查看总分、逐题得分与评语。"
    />

    <el-alert v-if="pendingCount" type="warning" :closable="false" show-icon class="result-notice">
      <p class="pending__text">
        有 {{ pendingCount }} 条提交没有自动分（判题内部错误，无法按测试点计分），需要人工评分。
        <template v-if="canReview">点右侧「评分」查看提交代码并打分。</template>
        <template v-else>请联系方向负责人或学院管理员。</template>
      </p>
    </el-alert>

    <div class="page__toolbar">
      <el-form inline class="page__filter">
        <el-form-item v-if="viewMode === 'submission'" label="分数来源">
          <el-select v-model="scoreSource" clearable placeholder="全部" style="width: 150px">
            <el-option label="按测试点自动计分" value="AUTO" />
            <el-option label="人工调整" value="HUMAN" />
            <el-option label="待评分" value="PENDING" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="viewMode === 'submission' && memberFilter">
          <el-tag closable type="primary" effect="plain" @close="clearMemberFilter">
            只看 {{ memberFilter.name }}，点右侧 × 取消筛选
          </el-tag>
        </el-form-item>
      </el-form>
      <el-radio-group
        :model-value="viewMode"
        class="page__mode"
        @change="onModeChange"
      >
        <el-radio-button value="member">按学生汇总</el-radio-button>
        <el-radio-button value="submission">按提交</el-radio-button>
      </el-radio-group>
      <el-button
        v-if="canReview && viewMode === 'member'"
        class="page__export"
        :icon="Download"
        :loading="exporting"
        data-test="export-scores"
        @click="onExport"
      >
        导出 Excel
      </el-button>
    </div>

    <el-table v-if="viewMode === 'member'" :data="memberScores?.items ?? []" row-key="memberId">
      <el-table-column label="成员" min-width="160">
        <template #default="{ row }">
          <div class="member-cell">
            <span class="member-cell__name">{{ row.memberName || row.memberId }}</span>
            <span v-if="row.memberStudentNo" class="member-cell__meta">
              {{ row.memberStudentNo }}
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="完成条目" width="130">
        <template #default="{ row }">
          <el-tag
            :type="(row.itemCount ?? row.problemCount) > 0 && row.completedCount === (row.itemCount ?? row.problemCount) ? 'success' : 'info'"
            effect="plain"
          >
            {{ row.completedCount }} / {{ row.itemCount ?? row.problemCount }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="客观题状态" width="120">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.sheetStatus !== 'SUBMITTED'"
            content="学生尚未交卷：客观题在交卷后自动判分，考核结束后未交卷的草稿会自动代交"
            placement="top"
          >
            <el-tag type="info" effect="plain">{{ row.sheetStatus === 'DRAFT' ? '未交卷(有草稿)' : '未交卷' }}</el-tag>
          </el-tooltip>
          <el-tag v-else-if="row.pendingCount" type="warning" effect="plain">
            {{ row.pendingCount }} 题待评分
          </el-tag>
          <el-tag v-else type="success" effect="plain">已判分</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总分" width="140">
        <template #default="{ row }">
          <strong class="score-strong">{{ row.totalScore }}</strong>
          <span class="muted"> / {{ row.maxScore }}</span>
        </template>
      </el-table-column>
      <el-table-column label="得分率" width="110">
        <template #default="{ row }">
          {{ row.scoreRate !== undefined && row.scoreRate !== null ? `${row.scoreRate}%` : '—' }}
        </template>
      </el-table-column>
      <el-table-column label="最近提交" width="180">
        <template #default="{ row }">
          {{ row.lastSubmittedAt ? formatDateTime(row.lastSubmittedAt) : '未提交' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" :width="canReview ? 170 : 110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="openMemberDetail(row)">
            明细
          </el-button>
          <el-button
            v-if="canReview"
            link
            type="primary"
            :data-test="`sheet-${row.memberId}`"
            @click="openSheet(row)"
          >
            答题卡
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-table v-else :data="items" row-key="submissionId">
      <el-table-column label="成员" min-width="150">
        <template #default="{ row }">
          <div class="member-cell">
            <span class="member-cell__name">{{ row.memberName || row.memberId }}</span>
            <span v-if="row.memberStudentNo" class="member-cell__meta">{{ row.memberStudentNo }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="题目" min-width="180">
        <template #default="{ row }">{{ problemNames.get(row.problemId) ?? row.problemId }}</template>
      </el-table-column>
      <el-table-column label="OJ 判定" width="120">
        <template #default="{ row }">
          <el-tag :type="verdictMeta(row.technicalVerdict).type" effect="plain">
            {{ verdictMeta(row.technicalVerdict).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="测试" width="90">
        <template #default="{ row }">{{ row.passedTestCases }}/{{ row.totalTestCases }}</template>
      </el-table-column>
      <el-table-column label="资源" width="130">
        <template #default="{ row }">{{ row.timeMs }} ms / {{ Math.round(row.memoryKb / 1024) }} MB</template>
      </el-table-column>
      <el-table-column label="自动分" width="100">
        <template #default="{ row }">{{ scoreLabel(row.autoScore) }}</template>
      </el-table-column>
      <el-table-column label="人工" width="90">
        <template #default="{ row }">{{ scoreLabel(row.humanScore) }}</template>
      </el-table-column>
      <el-table-column label="最终分 / 来源" width="130">
        <template #default="{ row }">
          <div class="final-cell">
            <strong>{{ scoreLabel(row.finalScore) }}</strong>
            <el-tag :type="scoreSourceMeta(row.scoreSource).type" effect="plain" size="small">
              {{ scoreSourceMeta(row.scoreSource).label }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="结果状态" width="110">
        <template #default="{ row }">
          <el-tag :type="resultStatusMeta(row.resultStatus).type" effect="plain">
            {{ resultStatusMeta(row.resultStatus).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" :width="canReview ? 150 : 90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="openCode(row.submissionId)">
            代码
          </el-button>
          <el-button
            v-if="canReview"
            link
            type="primary"
            :icon="EditPen"
            @click="openReview(row.submissionId)"
          >
            评分
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="page__pager">
      <el-pagination
        v-if="viewMode === 'submission'"
        v-model:current-page="page"
        :page-size="pageSize"
        :total="data?.total ?? 0"
        layout="total, prev, pager, next"
        @current-change="() => loadSubmission()"
      />
      <el-pagination
        v-else
        v-model:current-page="memberPage"
        :page-size="pageSize"
        :total="memberScores?.total ?? 0"
        layout="total, prev, pager, next"
        @current-change="() => loadMemberScores()"
      />
    </div>

    <SubmissionCodeDialog v-model="codeDialogVisible" :submission-id="codeSubmissionId" />

    <el-dialog v-model="sheetVisible" :title="`${sheetDetail?.memberName ?? ''} 的答题卡`" width="720px">
      <div v-loading="sheetLoading">
        <el-alert
          v-if="sheetDetail && sheetDetail.status !== 'SUBMITTED'"
          type="warning"
          :closable="false"
          title="该学生尚未交卷"
          description="客观题在交卷时自动判分；草稿答案不生成对错与得分。考核结束后未交卷的草稿会被系统自动代交并判分。"
          class="sheet-notice"
        />
        <el-table v-if="sheetDetail?.items?.length" :data="sheetDetail.items" row-key="position">
          <el-table-column prop="position" label="题号" width="70" />
          <el-table-column label="学生答案" min-width="140">
            <template #default="{ row }">{{ answerText(row.answer) }}</template>
          </el-table-column>
          <el-table-column label="正确答案" min-width="140">
            <template #default="{ row }">{{ answerText(row.correctAnswer) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.reviewed" type="success" effect="plain">已复核</el-tag>
              <el-tag v-else :type="row.correct ? 'success' : 'danger'" effect="plain">
                {{ row.correct ? '对' : '错' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="自动分" width="80">
            <template #default="{ row }">{{ row.autoAwarded ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="人工分" width="80">
            <template #default="{ row }">{{ row.manualScore ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="得分" width="70">
            <template #default="{ row }">{{ row.awarded }}</template>
          </el-table-column>
          <el-table-column label="评语" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reviewComment ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="解析" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.analysis ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <div v-else-if="!sheetLoading" class="sheet-empty">
          {{ sheetDetail && sheetDetail.status !== 'SUBMITTED' ? '学生尚未交卷，暂无判分明细。' : '本场考核没有客观题，或该学生未作答。' }}
        </div>
      </div>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.result-notice {
  margin-bottom: 16px;
}

.page__header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page__toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page__filter {
  margin-bottom: 16px;
}

.page__mode {
  flex: none;
}

.page__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.score-strong {
  color: var(--app-ink);
  font-size: 16px;
}

.muted {
  color: var(--el-text-color-secondary);
}

.final-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.pending__text {
  margin: 0;
  line-height: 1.6;
}

.member-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.member-cell__name {
  color: var(--app-ink);
  font-weight: 500;
}

.member-cell__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.sheet-notice {
  margin-bottom: 12px;
}

.sheet-empty {
  color: var(--el-text-color-secondary);
  padding: 12px 0;
}

.page__export {
  margin-left: 12px;
}
</style>
