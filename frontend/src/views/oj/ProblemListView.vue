<script setup lang="ts">
/**
 * OJ 题库列表。
 *
 * 学生只显示 PUBLISHED 题目；管理员和负责人可维护 DRAFT/RETIRED 题目。
 * 普通题目接口不包含私有测试数据。
 *
 * 学生额外有「AI 出题」入口：按知识点生成一道专属题，生成成功后直接进入详情页；
 * 「我的题库」开关走 mine=true，只看自己生成的题。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Delete, Edit, MagicStick, Plus, Search, Trophy, Upload, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  deleteAssessmentProblem,
  importAssessmentProblems,
  listAssessmentProblems,
} from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { readOjImportFile, type OjImportPreview } from '@/utils/ojImport'
import {
  difficultyMetaLabel,
  languageLabel,
  problemStatusMeta,
} from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import type { AssessmentProblem, PageData } from '@/api/types'
import type { AssessmentProblemListQuery } from '@/api/modules/oj'
import { newIdempotencyKey } from '@/api/idempotency'
import AiProblemDialog from '@/views/oj/AiProblemDialog.vue'

const router = useRouter()
const auth = useAuthStore()

const data = ref<PageData<AssessmentProblem> | null>(null)
const loading = ref(false)
const status = ref('')
const difficulty = ref('')
const keyword = ref('')
/** 已提交给服务端的搜索词，避免边输边搜把分页冲掉。 */
const appliedKeyword = ref('')
const page = ref(1)
const PAGE_SIZE = 20
const total = ref(0)
const mineOnly = ref(false)
const aiDialogVisible = ref(false)
const importInput = ref<HTMLInputElement>()
const importVisible = ref(false)
const importing = ref(false)
const importFileName = ref('')
const importPreview = ref<OjImportPreview | null>(null)
const importApiErrors = ref<Array<{ index: number; code: string }>>([])

const canManage = computed(() => auth.isAdmin || auth.isLeader)
const canUseAi = computed(() => auth.isStudent)
/** 分页与筛选都在服务端完成，这里直接展示当前页。 */
const items = computed(() => data.value?.items ?? [])

async function load(): Promise<void> {
  loading.value = true
  try {
    const query: AssessmentProblemListQuery = { page: page.value, size: PAGE_SIZE }
    if (canUseAi.value && mineOnly.value) query.mine = true
    if (canManage.value && status.value) query.status = status.value
    if (difficulty.value) query.difficulty = difficulty.value
    if (appliedKeyword.value) query.keyword = appliedKeyword.value
    const result = await listAssessmentProblems(query)
    data.value = result
    total.value = result.total ?? result.items.length
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

/** 条件变化一律回到第一页，否则会停在一个空页上。 */
function reloadFromFirstPage(): void {
  page.value = 1
  void load()
}

function search(): void {
  appliedKeyword.value = keyword.value.trim()
  reloadFromFirstPage()
}

function clearSearch(): void {
  keyword.value = ''
  appliedKeyword.value = ''
  reloadFromFirstPage()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

watch([status, difficulty, mineOnly], () => {
  reloadFromFirstPage()
})

function goDetail(row: AssessmentProblem): void {
  void router.push({ name: 'oj-problem-detail', params: { problemId: row.id } })
}

function openAiDialog(): void {
  aiDialogVisible.value = true
}

/** 生成成功：服务端已落库发布，直接进详情页开始做题。 */
function handleGenerated(problemId: string): void {
  if (mineOnly.value) {
    void load()
  }
  void router.push({ name: 'oj-problem-detail', params: { problemId } })
}

async function removeProblem(row: AssessmentProblem): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    '删除后题目及其测试点将被彻底移除，无法恢复；被考核引用或已有提交记录的题目无法删除。',
    '确认删除题目？',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return
  try {
    await deleteAssessmentProblem(row.id, { version: row.version }, newIdempotencyKey())
    ElMessage.success('题目已删除')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

function chooseImportFile(): void {
  importInput.value?.click()
}

async function handleImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importFileName.value = file.name
  importApiErrors.value = []
  try {
    importPreview.value = await readOjImportFile(file)
    importVisible.value = true
    if (importPreview.value.errors.length > 0) {
      ElMessage.warning('文件未通过本地校验，请查看预览错误')
    }
  } catch {
    importPreview.value = { payload: null, errors: [{ index: 0, code: 'INVALID_JSON' }] }
    importVisible.value = true
    ElMessage.warning('文件读取失败')
  } finally {
    input.value = ''
  }
}

function importErrorMessage(code: string): string {
  switch (code) {
    case 'INVALID_JSON':
      return 'JSON 格式错误'
    case 'INVALID_IMPORT':
      return '导入结构必须是 problems 数组'
    case 'INVALID_PROBLEM':
      return '题目字段或测试点不合法'
    case 'DUPLICATE_TITLE':
      return '存在重复标题'
    case 'TOO_MANY_PROBLEMS':
      return '单次最多导入 100 道题'
    case 'REQUEST_TOO_LARGE':
      return '请求不能超过 5 MB'
    default:
      return '导入校验失败'
  }
}

async function confirmImport(): Promise<void> {
  const payload = importPreview.value?.payload
  if (!payload) return
  importing.value = true
  importApiErrors.value = []
  try {
    await importAssessmentProblems(payload, newIdempotencyKey())
    ElMessage.success(`已导入 ${payload.problems.length} 道题目`)
    importVisible.value = false
    importPreview.value = null
    importFileName.value = ''
    await load()
  } catch (error) {
    const apiError = toApiError(error)
    const body = apiError.body as { data?: { errors?: Array<{ index: number; code: string }> } } | null
    importApiErrors.value = body?.data?.errors ?? []
    importPreview.value = null
    ElMessage.error(importApiErrors.value[0] ? importErrorMessage(importApiErrors.value[0].code) : describeError(apiError))
  } finally {
    importing.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>OJ 题库</span>
        <div class="header-actions">
          <input
            ref="importInput"
            type="file"
            accept=".json,application/json"
            data-test="import-input"
            class="import-input"
            @change="handleImportFile"
          />
          <el-button v-if="canManage" :icon="Upload" @click="chooseImportFile">批量导入</el-button>
          <el-button v-if="canManage" type="primary" :icon="Plus" @click="router.push({ name: 'oj-problem-new' })">
            创建题目
          </el-button>
        </div>
      </div>
    </template>

    <el-alert
      class="page__notice"
      type="info"
      :closable="false"
      show-icon
      title="本题库采用函数传参（力扣风格）判题"
      description="学生只需实现题目指定的函数，无需处理标准输入输出；历史命令行读写题目仍可正常练习与判题。"
    />

    <el-form inline class="page__filter">
      <el-form-item v-if="canManage" label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 130px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已退役" value="RETIRED" />
        </el-select>
      </el-form-item>
      <el-form-item label="难度">
        <el-select v-model="difficulty" clearable placeholder="全部" style="width: 120px">
          <el-option label="简单" value="EASY" />
          <el-option label="中等" value="MEDIUM" />
          <el-option label="困难" value="HARD" />
        </el-select>
      </el-form-item>
      <el-form-item label="搜索">
        <el-input
          v-model="keyword"
          data-test="problem-keyword"
          placeholder="标题/摘要/标签/题面，回车搜索"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="clearSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button data-test="problem-search" :icon="Search" @click="search">搜索</el-button>
      </el-form-item>
      <el-form-item v-if="canUseAi">
        <el-radio-group v-model="mineOnly" data-test="mine-toggle">
          <el-radio-button :value="false">全部题目</el-radio-button>
          <el-radio-button :value="true">我的题库</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="canUseAi">
        <el-button
          data-test="top100-entry"
          :icon="Trophy"
          @click="router.push({ name: 'oj-top100' })"
        >
          力扣 Top 100
        </el-button>
      </el-form-item>
      <el-form-item v-if="canUseAi">
        <el-button
          data-test="ai-generate"
          type="primary"
          :icon="MagicStick"
          @click="openAiDialog"
        >
          AI 出题
        </el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" row-key="id">
      <el-table-column prop="title" label="题目" min-width="230">
        <template #default="{ row }">
          <div class="problem-title-cell">
            <el-tooltip :content="row.title" placement="top" :show-after="300">
              <span class="problem-title-text">{{ row.title }}</span>
            </el-tooltip>
            <el-tag
              v-if="row.source === 'AI'"
              class="problem-source"
              size="small"
              type="success"
              effect="plain"
            >
              AI 生成
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="summary" label="摘要" min-width="250" show-overflow-tooltip />
      <el-table-column label="难度" width="90">
        <template #default="{ row }">
          <el-tag :type="difficultyMetaLabel(row.difficulty).type" effect="plain">
            {{ difficultyMetaLabel(row.difficulty).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="problemStatusMeta(row.status).type" effect="plain">
            {{ problemStatusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标签" min-width="160">
        <template #default="{ row }">{{ row.tags.join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="语言" min-width="170">
        <template #default="{ row }">{{ row.allowedLanguages.map(languageLabel).join('、') }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="goDetail(row)">查看</el-button>
          <el-button
            v-if="canManage"
            link
            type="primary"
            :icon="Edit"
            @click="router.push({ name: 'oj-problem-edit', params: { problemId: row.id } })"
          >
            编辑
          </el-button>
          <el-button
            v-if="canManage"
            link
            type="danger"
            :icon="Delete"
            :data-test="`delete-${row.id}`"
            @click="removeProblem(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        data-test="problem-pager"
        layout="total, prev, pager, next, jumper"
        :page-size="PAGE_SIZE"
        :total="total"
        :background="true"
        @current-change="changePage"
      />
    </div>

    <AiProblemDialog v-model="aiDialogVisible" @generated="handleGenerated" />

    <el-dialog v-model="importVisible" title="导入预览" width="640px">
      <div class="import-summary">
        <div>文件：{{ importFileName || '未选择' }}</div>
        <div v-if="importPreview?.payload">
          待导入：{{ importPreview.payload.problems.length }} 道题目
        </div>
        <div v-else class="import-errors">文件未通过校验，不会提交任何题目。</div>
      </div>
      <el-table v-if="importPreview?.payload" :data="importPreview.payload.problems" max-height="320">
        <el-table-column type="index" label="#" width="55" />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column label="正式考核测试点" width="130">
          <template #default="{ row }">{{ row.assessmentTestCases?.length ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="练习测试点" width="110">
          <template #default="{ row }">{{ row.practiceTestCases?.length ?? 0 }}</template>
        </el-table-column>
      </el-table>
      <div v-if="importPreview?.errors.length" class="import-errors">
        <div v-for="item in importPreview.errors" :key="`${item.index}-${item.code}`">
          第 {{ item.index + 1 }} 题：{{ importErrorMessage(item.code) }}
        </div>
      </div>
      <div v-if="importApiErrors.length" class="import-errors">
        <div v-for="item in importApiErrors" :key="`${item.index}-${item.code}`">
          第 {{ item.index + 1 }} 题：{{ importErrorMessage(item.code) }}
        </div>
      </div>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button
          data-test="confirm-import"
          type="primary"
          :loading="importing"
          :disabled="!importPreview?.payload"
          @click="confirmImport"
        >
          确认导入
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.import-input {
  display: none;
}

.import-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}

.import-errors {
  margin-top: 12px;
  color: var(--el-color-danger);
  line-height: 1.7;
}

.page__filter {
  margin-bottom: 16px;
}

.page__notice {
  margin-bottom: 16px;
}

.problem-source {
  flex: none;
  margin-left: 6px;
}

/*
 * 标题过长时省略号只吃掉标题，不能把「AI 生成」标签一起挤掉：
 * 用 flex + min-width:0，标签固定不收缩。
 */
.problem-title-cell {
  display: flex;
  align-items: center;
  min-width: 0;
}

.problem-title-text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
