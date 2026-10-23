<script setup lang="ts">
/**
 * OJ 考核结果（独立成绩单）。
 *
 * OJ 技术判定、AI 评分、人工评分分别展示；人工评分优先。
 * 结果不写入日报/周报积分流水，也不改变现有排行榜或淘汰状态。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { EditPen, View } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

import SubmissionCodeDialog from '@/components/SubmissionCodeDialog.vue'

import {
  getAssessment,
  listAssessmentMemberScores,
  listAssessmentProblems,
  listAssessmentResults,
} from '@/api/modules/oj'
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
  PageData,
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

function openCode(submissionId: string): void {
  codeSubmissionId.value = submissionId
  codeDialogVisible.value = true
}

/** 管理员和方向负责人可进入人工批阅，评完回到当前结果页。 */
const canReview = computed(() => auth.isAdmin || auth.isLeader)

function openReview(submissionId: string): void {
  if (!submissionId) return
  // 用字符串路径跳转，避免 name+params 解析在某些导航时序下拿到空路径
  const redirect = encodeURIComponent(`/oj/assessments/${assessmentId.value}/results`)
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

async function load(): Promise<void> {
  loading.value = true
  try {
    const [currentAssessment, problemPage] = await Promise.all([
      getAssessment(assessmentId.value),
      listAssessmentProblems(),
    ])
    assessment.value = currentAssessment
    problems.value = problemPage.items
    if (viewMode.value === 'member') await loadMemberScores()
    else await loadSubmission()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

/** 切换「按学生 / 按提交」时重新取数，两种视图分页互不影响。 */
async function switchMode(mode: 'submission' | 'member'): Promise<void> {
  viewMode.value = mode
  loading.value = true
  try {
    if (mode === 'member') await loadMemberScores()
    else await loadSubmission()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

/** 从汇总表点进某个成员，回到提交视图并只看他。 */
async function openMemberDetail(row: AssessmentMemberScore): Promise<void> {
  memberFilter.value = {
    id: row.memberId,
    name: row.memberName ?? row.memberStudentNo ?? row.memberId,
  }
  page.value = 1
  await switchMode('submission')
}

function onModeChange(value: string | number | boolean | undefined): void {
  void switchMode(value === 'submission' ? 'submission' : 'member')
}

async function clearMemberFilter(): Promise<void> {
  memberFilter.value = null
  page.value = 1
  await loadSubmission()
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <span>{{ assessment?.title ?? '考核结果' }}</span>
        <el-tag v-if="assessment" :type="assessmentStatusMeta(assessment.status).type" effect="plain">
          {{ assessmentStatusMeta(assessment.status).label }}
        </el-tag>
      </div>
    </template>

    <el-alert type="info" :closable="false" show-icon class="result-notice">
      自动分按通过的测试点比例计算（四舍五入到整数）；人工评分优先，可随时调整。本页不计入现有积分、流水或排行榜。
    </el-alert>

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
      <el-table-column label="完成题数" width="130">
        <template #default="{ row }">
          <el-tag
            :type="row.problemCount > 0 && row.completedCount === row.problemCount ? 'success' : 'info'"
            effect="plain"
          >
            {{ row.completedCount }} / {{ row.problemCount }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="待评分" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.pendingCount" type="warning" effect="plain">
            {{ row.pendingCount }} 题
          </el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="总分" width="140">
        <template #default="{ row }">
          <strong class="score-strong">{{ row.totalScore }}</strong>
          <span class="muted"> / {{ row.maxScore }}</span>
        </template>
      </el-table-column>
      <el-table-column label="平均分" width="110">
        <template #default="{ row }">{{ row.averageScore.toFixed(1) }}</template>
      </el-table-column>
      <el-table-column label="最近提交" width="180">
        <template #default="{ row }">
          {{ row.lastSubmittedAt ? formatDateTime(row.lastSubmittedAt) : '未提交' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="openMemberDetail(row)">
            明细
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
</style>
