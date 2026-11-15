<script setup lang="ts">
/**
 * AI 出题弹窗。
 *
 * 服务端在请求内完成「模型生成 → 参考解法逐点真跑自测 → 落库发布」，实测约 70 秒，
 * 因此这里做了三件事：
 *   1. 请求超时单独放宽到 3 分钟（见 api/modules/oj.ts 的 AI_GENERATE_TIMEOUT_MS）；
 *   2. 生成期间按钮置灰 + loading，禁止重复点击（服务端限流每人 10 分钟 6 道）；
 *   3. 展示已等待秒数，避免长请求看起来像卡死。
 *
 * 生成成功后由调用方决定跳转，本组件只负责 emit 新题目 id。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { MagicStick } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { generateAiProblem } from '@/api/modules/oj'
import { describeError, toApiError, type ApiError } from '@/api/errors'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 预填知识点（力扣题单等入口预填）。 */
    prefillTopic?: string
    prefillDifficulty?: 'EASY' | 'MEDIUM' | 'HARD'
    prefillCategory?: string
  }>(),
  { prefillTopic: '', prefillDifficulty: 'MEDIUM', prefillCategory: '' },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  generated: [problemId: string]
}>()

const difficultyOptions = [
  { label: '简单', value: 'EASY' },
  { label: '中等', value: 'MEDIUM' },
  { label: '困难', value: 'HARD' },
] as const

const topic = ref('')
const difficulty = ref<'EASY' | 'MEDIUM' | 'HARD'>('MEDIUM')
const category = ref('')
const generating = ref(false)
const elapsedSeconds = ref(0)

let elapsedTimer: ReturnType<typeof setInterval> | null = null

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const canSubmit = computed(() => !generating.value && topic.value.trim().length > 0)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    topic.value = props.prefillTopic ?? ''
    difficulty.value = props.prefillDifficulty ?? 'MEDIUM'
    category.value = props.prefillCategory ?? ''
  },
  // 组件可能带着 modelValue=true 挂载（题单入口直接打开），首帧也要应用预填值。
  { immediate: true },
)

function startElapsed(): void {
  elapsedSeconds.value = 0
  stopElapsed()
  elapsedTimer = setInterval(() => {
    elapsedSeconds.value += 1
  }, 1000)
}

function stopElapsed(): void {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

onBeforeUnmount(stopElapsed)

/** 503 时后端会给出具体原因（自测未通过 / 模型不可用），比通用文案更有指导性。 */
function generationErrorMessage(error: ApiError): string {
  if (error.code === 'SERVICE_UNAVAILABLE' && /[\u4e00-\u9fa5]/.test(error.message)) {
    return error.message
  }
  return describeError(error)
}

async function submit(): Promise<void> {
  if (generating.value) return
  const trimmedTopic = topic.value.trim()
  if (!trimmedTopic) {
    ElMessage.warning('请先填写知识点')
    return
  }
  generating.value = true
  startElapsed()
  try {
    const result = await generateAiProblem({
      topic: trimmedTopic,
      difficulty: difficulty.value,
      category: category.value.trim() || null,
    })
    stopElapsed()
    ElMessage.success(`已生成《${result.problem.title}》，${result.testCases.length} 个测试点全部自测通过`)
    visible.value = false
    emit('generated', result.problem.id)
  } catch (error) {
    ElMessage.error(generationErrorMessage(toApiError(error)))
  } finally {
    stopElapsed()
    generating.value = false
  }
}

/** 生成中不允许关闭：请求还在跑，关掉会丢掉刚落库的题目。 */
function handleBeforeClose(done: () => void): void {
  if (generating.value) {
    ElMessage.warning('题目正在生成，请等待完成后再关闭')
    return
  }
  done()
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="AI 出题"
    width="560px"
    :close-on-click-modal="false"
    :before-close="handleBeforeClose"
  >
    <el-alert
      class="ai__notice"
      type="info"
      :closable="false"
      show-icon
      title="生成需要约 1 分钟"
      description="服务端会用参考解法把测试点逐点真跑一遍，全部通过才发布到你的题库；每人 10 分钟最多生成 6 道。"
    />

    <el-form label-width="72px" :disabled="generating">
      <el-form-item label="知识点" required>
        <el-input
          v-model="topic"
          data-test="ai-topic"
          maxlength="60"
          show-word-limit
          placeholder="例如：动态规划、二叉树、滑动窗口"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-form-item label="难度">
        <el-radio-group v-model="difficulty" data-test="ai-difficulty">
          <el-radio v-for="item in difficultyOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="题型">
        <el-input
          v-model="category"
          data-test="ai-category"
          maxlength="40"
          placeholder="选填，例如：数组、字符串、链表"
        />
      </el-form-item>
    </el-form>

    <div v-if="generating" class="ai__progress" data-test="ai-progress">
      <el-icon class="is-loading"><MagicStick /></el-icon>
      <span>正在生成并自测，已等待 {{ elapsedSeconds }} 秒，请勿关闭或刷新页面…</span>
    </div>

    <template #footer>
      <el-button :disabled="generating" @click="visible = false">取消</el-button>
      <el-button
        data-test="ai-submit"
        type="primary"
        :icon="MagicStick"
        :loading="generating"
        :disabled="!canSubmit"
        @click="submit"
      >
        {{ generating ? '生成中' : '生成' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ai__notice {
  margin-bottom: 16px;
}

.ai__progress {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-color-primary);
  font-size: 13px;
}
</style>
