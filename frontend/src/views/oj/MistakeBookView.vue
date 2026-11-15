<script setup lang="ts">
/**
 * 错题本。
 *
 * 数据来自两类真实判题记录：正式考核提交与题库练习，后端按题目取最近一次尝试，
 * 最近一次已经通过就不再算错题。列表按上次尝试时间倒序。
 *
 * 错因要调模型，所以不跟列表一起取：先出列表，再按行并发（最多 3 行）拉 AI 错因，
 * 取不到就展示判题结论，不编造原因。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { getMistakeCause, listMistakeBook } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { difficultyMetaLabel, VERDICT_META } from '@/components/ojMeta'
import type { MistakeBookItem, OjVerdict } from '@/api/types'

const router = useRouter()

const loading = ref(false)
const items = ref<MistakeBookItem[]>([])

interface CauseState {
  loading: boolean
  cause?: string
  error?: string
}

const causes = ref<Record<string, CauseState>>({})

const isEmpty = computed(() => !loading.value && items.value.length === 0)

function sourceLabel(item: MistakeBookItem): string {
  return item.source === 'PRACTICE' ? '题库练习' : '考核提交'
}

function verdictLabel(verdict?: string): {
  label: string
  type: 'primary' | 'success' | 'warning' | 'danger' | 'info'
} {
  const meta = VERDICT_META[verdict as OjVerdict]
  return meta ?? { label: verdict ?? '未判定', type: 'info' }
}

function judgeSummary(item: MistakeBookItem): string {
  const passed = item.passedTestCases ?? 0
  const total = item.totalTestCases ?? 0
  return `${verdictLabel(item.verdict).label} · 通过 ${passed}/${total} 个用例`
}

async function loadCauses(rows: MistakeBookItem[]): Promise<void> {
  const queue = [...rows]
  const worker = async (): Promise<void> => {
    for (;;) {
      const item = queue.shift()
      if (!item) return
      const key = item.problemId ?? ''
      causes.value[key] = { loading: true }
      try {
        const result = await getMistakeCause(key)
        causes.value[key] = result.available
          ? { loading: false, cause: result.cause ?? '' }
          : { loading: false, error: result.error ?? '暂时拿不到 AI 错因' }
      } catch (error) {
        causes.value[key] = { loading: false, error: describeError(toApiError(error)) }
      }
    }
  }
  // 模型调用有并发上限，这里限制 3 路，避免把 AI 批改挤满。
  await Promise.all(Array.from({ length: 3 }, worker))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const data = await listMistakeBook()
    items.value = data.items ?? []
    await loadCauses(items.value)
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

/**
 * 重做：回到这道题的做题页，编辑器里带上上次做错的代码。
 *
 * 考核提交回考核提交页（保证成绩口径不变），题库练习回练习页；
 * 代码由做题页按 retry=1 调错题本接口回填，不在列表里传输整段代码。
 */
function redo(item: MistakeBookItem): void {
  if (item.source === 'SUBMISSION' && item.submissionId && item.assessmentId) {
    void router.push({
      name: 'oj-submit-code',
      params: { assessmentId: item.assessmentId, problemId: item.problemId },
      query: { retry: '1' },
    })
    return
  }
  void router.push({
    name: 'oj-practice-code',
    params: { problemId: item.problemId },
    query: { retry: '1' },
  })
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="page__header">
        <span>错题本</span>
        <el-button :icon="RefreshRight" text @click="load">刷新</el-button>
      </div>
    </template>

    <el-alert
      class="page__notice"
      type="info"
      :closable="false"
      show-icon
      title="只收录还没做对的题"
      description="同一道题按最近一次判题结果判断：最近一次通过就不再出现在这里。点「重做」会带回上次写的代码。"
    />

    <el-empty
      v-if="isEmpty"
      data-test="mistake-empty"
      description="还没有错题，继续加油"
    />

    <el-table v-else :data="items" row-key="problemId">
      <el-table-column label="题目" min-width="220">
        <template #default="{ row }">
          <div class="title-cell">
            <el-tooltip :content="row.title" placement="top" :show-after="300">
              <span class="title-text">{{ row.title }}</span>
            </el-tooltip>
            <el-tag size="small" effect="plain" :type="row.source === 'PRACTICE' ? 'info' : 'primary'">
              {{ sourceLabel(row) }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="难度" width="90">
        <template #default="{ row }">
          <el-tag :type="difficultyMetaLabel(row.difficulty).type" effect="plain">
            {{ difficultyMetaLabel(row.difficulty).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上次判定" width="150">
        <template #default="{ row }">
          <el-tag :type="verdictLabel(row.verdict).type" effect="plain" size="small">
            {{ verdictLabel(row.verdict).label }}
          </el-tag>
          <span class="cases"> {{ row.passedTestCases }}/{{ row.totalTestCases }} </span>
        </template>
      </el-table-column>
      <el-table-column prop="lastSubmittedAt" label="上次提交时间" width="180">
        <template #default="{ row }">{{ row.lastSubmittedAt?.replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="AI 错因" min-width="320">
        <template #default="{ row }">
          <div v-if="causes[row.problemId]?.loading" class="cause cause--muted">
            AI 正在分析这次提交…
          </div>
          <div v-else-if="causes[row.problemId]?.cause" class="cause">
            {{ causes[row.problemId]?.cause }}
          </div>
          <div v-else class="cause cause--muted">
            {{ causes[row.problemId]?.error ?? '暂无 AI 错因' }}
            <span class="cause__judge">（{{ judgeSummary(row) }}）</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :icon="RefreshRight"
            :data-test="`redo-${row.problemId}`"
            @click="redo(row)"
          >
            重做
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

.page__notice {
  margin-bottom: 16px;
}

.title-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 6px;
}

.title-text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.cases {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 6px;
}

.cause {
  line-height: 1.6;
}

.cause--muted {
  color: var(--el-text-color-secondary);
}

.cause__judge {
  color: var(--el-text-color-placeholder);
}
</style>
