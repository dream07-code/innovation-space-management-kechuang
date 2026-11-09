<script setup lang="ts">
/**
 * OJ 考核列表。
 *
 * 学生查看本人方向已发布/已关闭考核；负责人和管理员可创建与维护考核。
 * 列表只展示普通 DTO，不暴露 OJ 私有字段、测试数据或执行参数。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { DataAnalysis, Edit, Plus, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { listAssessments } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { assessmentStatusMeta, languageLabel } from '@/components/ojMeta'
import { formatDateTime } from '@/utils/datetime'
import { useAuthStore } from '@/stores/auth'
import type { Assessment, AssessmentStatus, PageData } from '@/api/types'

const router = useRouter()
const auth = useAuthStore()

const data = ref<PageData<Assessment> | null>(null)
const loading = ref(false)
const status = ref<AssessmentStatus | ''>('')

const canManage = computed(() => auth.isAdmin || auth.isLeader)
const items = computed(() =>
  (data.value?.items ?? []).filter((item) => status.value === '' || item.status === status.value),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listAssessments()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function goDetail(row: Assessment): void {
  void router.push({ name: 'oj-assessment-detail', params: { assessmentId: row.id } })
}

function goEdit(row: Assessment): void {
  void router.push({ name: 'oj-assessment-edit', params: { assessmentId: row.id } })
}

function goResults(row: Assessment): void {
  void router.push({ name: 'oj-assessment-results', params: { assessmentId: row.id } })
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>OJ 考核</span>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="router.push({ name: 'oj-assessment-new' })">
          创建考核
        </el-button>
      </div>
    </template>

    <el-form inline class="page__filter">
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 140px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已关闭" value="CLOSED" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" row-key="id">
      <el-table-column prop="title" label="考核" min-width="220" show-overflow-tooltip />
      <el-table-column prop="directionName" label="方向" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="assessmentStatusMeta(row.status).type" effect="plain">
            {{ assessmentStatusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="200">
        <template #default="{ row }">
          <span class="time-range">{{ formatDateTime(row.startAt) }}</span>
          <span class="time-range time-range--end">至 {{ formatDateTime(row.endAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="允许语言" min-width="170">
        <template #default="{ row }">{{ row.allowedLanguages.map(languageLabel).join('、') }}</template>
      </el-table-column>
      <el-table-column prop="problemIds.length" label="题目" width="70" />
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="goDetail(row)">详情</el-button>
          <el-button v-if="canManage && row.status === 'DRAFT'" link type="primary" :icon="Edit" @click="goEdit(row)">
            编辑
          </el-button>
          <el-button link type="primary" :icon="DataAnalysis" @click="goResults(row)">
            {{ auth.isStudent ? '查看成绩' : '结果' }}
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

/* 起止时间竖排：单行放不下会把右侧操作列挤出可视区。 */
.time-range {
  display: block;
  line-height: 1.6;
  white-space: nowrap;
}

.time-range--end {
  color: var(--el-text-color-secondary);
}
</style>
