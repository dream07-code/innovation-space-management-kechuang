<script setup lang="ts">
/**
 * OJ 题库列表。
 *
 * 学生只显示 PUBLISHED 题目；管理员和负责人可维护 DRAFT/RETIRED 题目。
 * 普通题目接口不包含私有测试数据。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Edit, Plus, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { listAssessmentProblems } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import {
  difficultyMetaLabel,
  languageLabel,
  problemStatusMeta,
} from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import type { AssessmentProblem, PageData } from '@/api/types'

const router = useRouter()
const auth = useAuthStore()

const data = ref<PageData<AssessmentProblem> | null>(null)
const loading = ref(false)
const status = ref('')
const difficulty = ref('')
const keyword = ref('')

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

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>OJ 题库</span>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="router.push({ name: 'oj-problem-new' })">
          创建题目
        </el-button>
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
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page__filter {
  margin-bottom: 16px;
}
</style>
