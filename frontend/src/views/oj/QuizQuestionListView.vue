<script setup lang="ts">
/**
 * 客观题库列表（仅负责人/管理员，路由已限 STAFF_ROLES）。
 *
 * 学生端没有客观题库入口，任何学生请求客观题接口都会被后端 404。
 * 列表按题型/状态/关键字服务端过滤；RETIRED 需显式选择才展示。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Edit, Plus, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { listQuizQuestions, retireQuizQuestion } from '@/api/modules/quiz'
import { describeError, toApiError } from '@/api/errors'
import { QUIZ_STATUS_META, QUIZ_TYPE_META, quizAnswerPreview } from '@/components/quizMeta'
import QuizQuestionImportDialog from '@/components/quiz/QuizQuestionImportDialog.vue'
import type { PageData, QuizQuestion, QuizQuestionType } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const router = useRouter()

const data = ref<PageData<QuizQuestion> | null>(null)
const loading = ref(false)
const type = ref<QuizQuestionType | ''>('')
const status = ref<QuizQuestion['status'] | ''>('')
const keyword = ref('')
const importVisible = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listQuizQuestions({
      page: 1,
      size: 100,
      type: type.value || undefined,
      status: status.value || undefined,
      keyword: keyword.value.trim() || undefined,
    })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function resetFilters(): void {
  type.value = ''
  status.value = ''
  keyword.value = ''
  void load()
}

async function retire(row: QuizQuestion): Promise<void> {
  try {
    await ElMessageBox.confirm(`停用「${row.stem.slice(0, 24)}…」？已挂入考核的条目判分不受影响。`, '停用客观题', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await retireQuizQuestion(row.id, newIdempotencyKey())
    ElMessage.success('已停用')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>客观题库</span>
        <div class="header-actions">
          <el-button :icon="Upload" @click="importVisible = true">批量导入</el-button>
          <el-button type="primary" :icon="Plus" @click="router.push({ name: 'oj-quiz-new' })">
            创建客观题
          </el-button>
        </div>
      </div>
    </template>

    <el-form inline class="page__filter">
      <el-form-item label="题型">
        <el-radio-group v-model="type" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="SINGLE_CHOICE">单选</el-radio-button>
          <el-radio-button value="MULTI_CHOICE">多选</el-radio-button>
          <el-radio-button value="TRUE_FALSE">判断</el-radio-button>
          <el-radio-button value="FILL_BLANK">填空</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 130px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已停用" value="RETIRED" />
        </el-select>
      </el-form-item>
      <el-form-item label="搜索">
        <el-input
          v-model="keyword"
          data-test="keyword"
          placeholder="题干/标签"
          clearable
          style="width: 220px"
          @keyup.enter="load"
        />
      </el-form-item>
      <el-form-item>
        <el-button data-test="search" type="primary" @click="load">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column label="题型" width="80">
        <template #default="{ row }">
          <el-tag :type="QUIZ_TYPE_META[row.type as QuizQuestionType].type" effect="plain">
            {{ QUIZ_TYPE_META[row.type as QuizQuestionType].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stem" label="题干" min-width="300" show-overflow-tooltip />
      <el-table-column label="答案" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ quizAnswerPreview(row) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="QUIZ_STATUS_META[row.status as QuizQuestion['status']].type" effect="plain">
            {{ QUIZ_STATUS_META[row.status as QuizQuestion['status']].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标签" min-width="140">
        <template #default="{ row }">{{ row.tags.join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :icon="Edit"
            @click="router.push({ name: 'oj-quiz-edit', params: { questionId: row.id } })"
          >
            编辑
          </el-button>
          <el-button
            v-if="row.status !== 'RETIRED'"
            link
            type="danger"
            :data-test="`retire-${row.id}`"
            @click="retire(row)"
          >
            停用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <QuizQuestionImportDialog v-model="importVisible" @imported="load" />
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

.page__filter {
  margin-bottom: 16px;
}
</style>
