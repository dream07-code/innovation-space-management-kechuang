<script setup lang="ts">
/**
 * AI 出题弹窗。
 *
 * 服务端在请求内完成「模型生成 → 参考解法逐点真跑自测 → 落库发布」，实测约 70 秒，
 * 因此这里做了四件事：
 *   1. 请求超时单独放宽到 3 分钟（见 api/modules/oj.ts 的 AI_GENERATE_TIMEOUT_MS）；
 *   2. 生成期间「生成」按钮置灰 + loading，禁止重复点击（服务端限流每人 10 分钟 6 道）；
 *   3. 展示已等待秒数，避免长请求看起来像卡死；
 *   4. 「取消」和右上角 × 随时可用：先调取消接口打标记再关弹窗，服务端在写库前后
 *      检查标记，命中就不落库（已落库会立刻回滚），所以取消不会在题库里留题。
 *
 * 前提是请求本身不中断：取消后仍等这一次响应回来，万一是「标记到之前已经落库」，
 * 再问学生保留还是删除，避免静默丢题或静默留题。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { MagicStick } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { cancelAiGeneration, deleteAssessmentProblem, generateAiProblem } from '@/api/modules/oj'
import { describeError, toApiError, type ApiError } from '@/api/errors'
import { newIdempotencyKey } from '@/api/idempotency'
import type { AiProblemGeneration } from '@/api/types'

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
/** 本次生成的请求 id，取消时按它打标记。 */
const requestId = ref('')
/** 学生点了取消：之后的响应只用于判断「是否已落库」，不再提示成功。 */
const cancelled = ref(false)

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
  requestId.value = crypto.randomUUID()
  cancelled.value = false
  generating.value = true
  startElapsed()
  try {
    const result = await generateAiProblem({
      topic: trimmedTopic,
      difficulty: difficulty.value,
      category: category.value.trim() || null,
      requestId: requestId.value,
    })
    stopElapsed()
    // 标记打到服务端之前就已经落库：问一句保留还是删除，不静默处理。
    if (cancelled.value) {
      await askKeepGenerated(result)
      return
    }
    ElMessage.success(`已生成《${result.problem.title}》，${result.testCases.length} 个测试点全部自测通过`)
    visible.value = false
    emit('generated', result.problem.id)
  } catch (error) {
    const apiError = toApiError(error)
    // 取消后服务端按标记拒绝落库（409），属于预期结果，不弹错误。
    if (!cancelled.value) ElMessage.error(generationErrorMessage(apiError))
  } finally {
    stopElapsed()
    generating.value = false
  }
}

/** 取消本次生成：先关弹窗，再给服务端打标记；题库里不会多出题目。 */
function cancel(): void {
  if (!generating.value) {
    visible.value = false
    return
  }
  cancelled.value = true
  generating.value = false
  stopElapsed()
  visible.value = false
  if (requestId.value) void cancelAiGeneration(requestId.value).catch(() => undefined)
  ElMessage.info('已取消生成，本次不会保存题目')
}

/** 题目已经落库但学生点过取消：由学生决定保留还是删除。 */
async function askKeepGenerated(result: AiProblemGeneration): Promise<void> {
  const keep = await ElMessageBox.confirm(
    `《${result.problem.title}》已经生成好了，要保留在题库里吗？`,
    '题目已生成',
    {
      type: 'warning',
      confirmButtonText: '保留',
      cancelButtonText: '不保留，删除',
    },
  )
    .then(() => true)
    .catch(() => false)
  if (keep) {
    ElMessage.success('已保留，可在「我的题库」里查看')
    emit('generated', result.problem.id)
    return
  }
  try {
    await deleteAssessmentProblem(
      result.problem.id,
      { version: result.problem.version },
      newIdempotencyKey(),
    )
    ElMessage.info('已删除这次生成的题目')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

/** × 也随时可以关：生成中关掉等价于点取消。 */
function handleBeforeClose(done: () => void): void {
  if (generating.value) {
    cancel()
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
      <span>正在生成并自测，已等待 {{ elapsedSeconds }} 秒；点「取消」可放弃这次生成，不会存题。</span>
    </div>

    <template #footer>
      <el-button data-test="ai-cancel" @click="cancel">取消</el-button>
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
