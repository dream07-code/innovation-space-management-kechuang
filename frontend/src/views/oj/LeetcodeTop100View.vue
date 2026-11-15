<script setup lang="ts">
/**
 * 力扣 Top 100 题单。
 *
 * 题单是前端写死的 100 道题（见 data/leetcodeTop100.ts），不调接口：
 *   - 题库里已有的题标「已收录」，点进去直接做题；
 *   - 题库还没有的题标「AI 生成」，点按钮打开 AI 出题弹窗，
 *     知识点/难度/题型按该题预填，学生点一下生成就能做。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, MagicStick, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import {
  TOP100_CATEGORIES,
  TOP100_PROBLEMS,
  type Top100Difficulty,
  type Top100Problem,
} from '@/data/leetcodeTop100'
import { difficultyMetaLabel } from '@/components/ojMeta'
import AiProblemDialog from '@/views/oj/AiProblemDialog.vue'

const router = useRouter()

const keyword = ref('')
const difficulty = ref<'' | Top100Difficulty>('')
const category = ref('')
const state = ref<'' | 'COLLECTED' | 'AI'>('')

const dialogVisible = ref(false)
const prefill = ref<{ topic: string; difficulty: Top100Difficulty; category: string }>({
  topic: '',
  difficulty: 'MEDIUM',
  category: '',
})

const pendingTitle = ref('')

const rows = computed(() =>
  TOP100_PROBLEMS.filter((item) => {
    if (difficulty.value && item.difficulty !== difficulty.value) return false
    if (category.value && item.category !== category.value) return false
    if (state.value === 'COLLECTED' && !item.problemId) return false
    if (state.value === 'AI' && item.problemId) return false
    const word = keyword.value.trim().toLowerCase()
    if (!word) return true
    return `${item.title} ${item.category} ${item.topic}`.toLowerCase().includes(word)
  }),
)

const collectedCount = computed(() => TOP100_PROBLEMS.filter((item) => item.problemId).length)
const aiCount = computed(() => TOP100_PROBLEMS.length - collectedCount.value)

function openProblem(item: Top100Problem): void {
  void router.push({ name: 'oj-problem-detail', params: { problemId: item.problemId } })
}

function askAi(item: Top100Problem): void {
  prefill.value = {
    topic: item.topic,
    difficulty: item.difficulty,
    category: item.pattern,
  }
  pendingTitle.value = item.title
  dialogVisible.value = true
}

function handleGenerated(problemId: string): void {
  ElMessage.success(`《${pendingTitle.value}》同类题目已生成，开始做题吧`)
  void router.push({ name: 'oj-problem-detail', params: { problemId } })
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <div class="title-block">
          <el-button :icon="ArrowLeft" text @click="router.push({ name: 'oj-problems' })">
            返回题库
          </el-button>
          <span class="title">力扣 Top 100 题单</span>
        </div>
        <div class="stats">
          <el-tag type="success" effect="plain">已收录 {{ collectedCount }} 道</el-tag>
          <el-tag type="warning" effect="plain">AI 生成 {{ aiCount }} 道</el-tag>
        </div>
      </div>
    </template>

    <el-alert
      class="page__notice"
      type="info"
      :closable="false"
      show-icon
      title="题库已收录的直接做题，其余一键生成"
      description="标「AI 生成」的题目会按该题的知识点与难度现场生成一道同类题，生成后自动跳到题目详情页，和普通题一样练习、提交与看批改报告。"
    />

    <el-form inline class="page__filter">
      <el-form-item label="搜索">
        <el-input v-model="keyword" placeholder="题目标题/分类/知识点" clearable style="width: 220px" />
      </el-form-item>
      <el-form-item label="难度">
        <el-select v-model="difficulty" clearable placeholder="全部" style="width: 120px">
          <el-option label="简单" value="EASY" />
          <el-option label="中等" value="MEDIUM" />
          <el-option label="困难" value="HARD" />
        </el-select>
      </el-form-item>
      <el-form-item label="分类">
        <el-select v-model="category" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="item in TOP100_CATEGORIES" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="state" data-test="state-filter">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="COLLECTED">已收录</el-radio-button>
          <el-radio-button value="AI">AI 生成</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item>
        <span class="count" data-test="row-count">共 {{ rows.length }} 道</span>
      </el-form-item>
    </el-form>

    <el-table :data="rows" row-key="no" max-height="640">
      <el-table-column prop="no" label="#" width="60" />
      <el-table-column prop="title" label="题目" min-width="240" show-overflow-tooltip />
      <el-table-column label="难度" width="90">
        <template #default="{ row }">
          <el-tag :type="difficultyMetaLabel(row.difficulty).type" effect="plain">
            {{ difficultyMetaLabel(row.difficulty).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column prop="topic" label="知识点" width="130" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.problemId" type="success" effect="plain">已收录</el-tag>
          <el-tag v-else type="warning" effect="plain">AI 生成</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button
            v-if="row.problemId"
            link
            type="primary"
            :icon="Promotion"
            :data-test="`open-${row.no}`"
            @click="openProblem(row)"
          >
            去做题
          </el-button>
          <el-button
            v-else
            link
            type="warning"
            :icon="MagicStick"
            :data-test="`ai-${row.no}`"
            @click="askAi(row)"
          >
            生成这道题
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <AiProblemDialog
      v-model="dialogVisible"
      :prefill-topic="prefill.topic"
      :prefill-difficulty="prefill.difficulty"
      :prefill-category="prefill.category"
      @generated="handleGenerated"
    />
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.title-block {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.stats {
  display: flex;
  gap: 8px;
}

.page__notice {
  margin-bottom: 16px;
}

.page__filter {
  margin-bottom: 16px;
}

.count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
