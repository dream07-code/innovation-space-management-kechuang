<script setup lang="ts">
/**
 * OJ 题库列表。
 *
 * 学生只显示 PUBLISHED 题目；管理员和负责人可维护 DRAFT/RETIRED 题目。
 * 普通题目接口不包含私有测试数据。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Edit, Plus, Upload, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { importAssessmentProblems, listAssessmentProblems } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { readOjImportFile, type OjImportPreview } from '@/utils/ojImport'
import {
  difficultyMetaLabel,
  languageLabel,
  problemStatusMeta,
} from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import type { AssessmentProblem, PageData } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const router = useRouter()
const auth = useAuthStore()

const data = ref<PageData<AssessmentProblem> | null>(null)
const loading = ref(false)
const status = ref('')
const difficulty = ref('')
const keyword = ref('')
const importInput = ref<HTMLInputElement>()
const importVisible = ref(false)
const importing = ref(false)
const importFileName = ref('')
const importPreview = ref<OjImportPreview | null>(null)
const importApiErrors = ref<Array<{ index: number; code: string }>>([])

const canManage = computed(() => auth.isAdmin || auth.isLeader)
const items = computed(() =>
  (data.value?.items ?? []).filter((problem) => {
    if (status.value && problem.status !== status.value) return false
    if (difficulty.value && problem.difficulty !== difficulty.value) return false
    const keywordValue = keyword.value.trim().toLowerCase()
    if (!keywordValue) return true
    return `${problem.title} ${problem.summary} ${problem.tags.join(' ')}`.toLowerCase().includes(keywordValue)
  }),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listAssessmentProblems()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function goDetail(row: AssessmentProblem): void {
  void router.push({ name: 'oj-problem-detail', params: { problemId: row.id } })
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

    <el-form inline class="page__filter">
      <el-form-item label="状态">
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
        <el-input v-model="keyword" placeholder="标题/摘要/标签" clearable style="width: 220px" />
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" row-key="id">
      <el-table-column prop="title" label="题目" min-width="210" show-overflow-tooltip />
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
      <el-table-column label="操作" width="150">
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
        </template>
      </el-table-column>
    </el-table>

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
</style>
