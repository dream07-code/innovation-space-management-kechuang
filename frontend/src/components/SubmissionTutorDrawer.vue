<script setup lang="ts">
/**
 * AI 助教对话抽屉。
 *
 * 挂在批改报告页：学生就这一次判题结果连续追问，至少覆盖「这道题为什么错」。
 * 上下文（题目、代码、判题结果）由后端按提交 id 组装，前端只发送问题与历史对话，
 * 因此抽屉里不做任何本地推断，也不缓存回答到浏览器存储。
 *
 * AI 不可用（未配置 / 调用失败 / 并发繁忙）时只显示原因，不渲染占位回答。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { ChatDotRound, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { askSubmissionTutor } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import type { SubmissionTutorTurn } from '@/api/types'

interface Recommendation {
  rule: string
  targetDifficulty: string
  reason: string
  /** 题库没有匹配难度的题时为 null/undefined，此时不显示推荐卡。 */
  problem?: { id: string; title: string; difficulty: string } | null
}

const props = defineProps<{
  modelValue: boolean
  submissionId: string
  problemTitle?: string
  recommendation?: Recommendation | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'open-problem': [problemId: string]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

interface Bubble {
  role: 'user' | 'assistant'
  content: string
}

interface Segment {
  type: 'text' | 'code'
  content: string
}

/**
 * 模型偶尔会给一小段示例代码。这里只切出 ``` 围栏包住的片段按等宽渲染，
 * 不做完整 Markdown 解析——答题内容以文字为主，多一层解析只会引入不确定性。
 */
function segments(text: string): Segment[] {
  const out: Segment[] = []
  const fence = /```[A-Za-z]*\n?([\s\S]*?)```/g
  let cursor = 0
  let match: RegExpExecArray | null
  while ((match = fence.exec(text)) !== null) {
    if (match.index > cursor) out.push({ type: 'text', content: text.slice(cursor, match.index) })
    out.push({ type: 'code', content: (match[1] ?? '').replace(/\s+$/, '') })
    cursor = fence.lastIndex
  }
  if (cursor < text.length) out.push({ type: 'text', content: text.slice(cursor) })
  return out.length > 0 ? out : [{ type: 'text', content: text }]
}

const messages = ref<Bubble[]>([])
const draft = ref('')
const sending = ref(false)
const unavailable = ref('')
const model = ref('')
const scroller = ref<HTMLElement | null>(null)

/** 首次打开给几个快捷问题，省得学生对着空输入框不知道问什么。 */
const presets = [
  '这道题为什么错？',
  '错在哪个测试点，具体是哪一行？',
  '这道题涉及什么知识点？',
  '该练什么，给个方向',
]

const canSend = computed(() => draft.value.trim().length > 0 && !sending.value)

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const node = scroller.value
  if (node) node.scrollTop = node.scrollHeight
}

async function send(text?: string): Promise<void> {
  const question = (text ?? draft.value).trim()
  if (!question || sending.value) return
  unavailable.value = ''
  // 历史只带之前的轮次，本次问题单独放 message，避免模型看到重复消息。
  const history: SubmissionTutorTurn[] = messages.value.map((m) => ({
    role: m.role,
    content: m.content,
  }))
  messages.value.push({ role: 'user', content: question })
  draft.value = ''
  sending.value = true
  await scrollToBottom()
  try {
    const reply = await askSubmissionTutor(props.submissionId, {
      message: question,
      history,
    })
    if (reply.available && reply.reply) {
      messages.value.push({ role: 'assistant', content: reply.reply })
      model.value = reply.model ?? ''
    } else {
      unavailable.value = reply.error || 'AI 助教暂不可用，请稍后重试。'
    }
  } catch (error) {
    const apiError = toApiError(error)
    const message = describeError(apiError)
    if (apiError.httpStatus === 409) {
      unavailable.value = '这次提交还没判完，判题结束后再问。'
    } else {
      unavailable.value = message
      ElMessage.error(message)
    }
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function reset(): void {
  messages.value = []
  draft.value = ''
  unavailable.value = ''
  model.value = ''
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) void scrollToBottom()
  },
)
</script>

<template>
  <el-drawer
    v-model="visible"
    :size="460"
    :with-header="false"
    class="tutor-drawer"
    @closed="reset"
  >
    <div class="tutor">
      <header class="tutor__header">
        <div class="tutor__title">
          <el-icon><ChatDotRound /></el-icon>
          <div>
            <strong>AI 助教</strong>
            <span>{{ problemTitle ?? '就这道题继续追问' }}</span>
          </div>
        </div>
        <el-button text @click="reset">清空对话</el-button>
      </header>

      <p class="tutor__hint">
        助教已拿到这道题的题面、你这次的代码和判题结果，可以连续追问。它不会直接给你可提交的完整答案。
      </p>

      <div ref="scroller" class="tutor__body">
        <div v-if="messages.length === 0" class="tutor__presets">
          <span class="tutor__presets-label">可以这样问：</span>
          <div class="tutor__chips">
            <button
              v-for="preset in presets"
              :key="preset"
              type="button"
              class="tutor__chip"
              @click="send(preset)"
            >
              {{ preset }}
            </button>
          </div>
        </div>

        <div
          v-for="(bubble, index) in messages"
          :key="index"
          class="bubble"
          :class="bubble.role === 'user' ? 'bubble--user' : 'bubble--assistant'"
        >
          <span v-if="bubble.role === 'assistant'" class="bubble__avatar">助教</span>
          <div class="bubble__text">
            <template v-for="(segment, index) in segments(bubble.content)" :key="index">
              <pre v-if="segment.type === 'code'" class="bubble__code">{{ segment.content }}</pre>
              <p v-else-if="segment.content.trim()" class="bubble__para">{{ segment.content.trim() }}</p>
            </template>
          </div>
        </div>

        <div v-if="sending" class="bubble bubble--assistant">
          <span class="bubble__avatar">助教</span>
          <p class="bubble__text bubble__text--pending">正在结合判题结果思考…</p>
        </div>

        <el-alert
          v-if="unavailable"
          :title="unavailable"
          type="warning"
          :closable="false"
          show-icon
          class="tutor__alert"
        />
      </div>

      <div v-if="recommendation?.problem" class="tutor__practice">
        <span class="tutor__practice-label">本次推荐练习</span>
        <div class="tutor__practice-row">
          <span>{{ recommendation.problem.title }}</span>
          <el-button
            link
            type="primary"
            @click="emit('open-problem', recommendation.problem!.id)"
          >
            去做这题
          </el-button>
        </div>
      </div>

      <footer class="tutor__footer">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="1000"
          show-word-limit
          placeholder="继续问，例如：为什么第 2 个测试点过不了？"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="tutor__actions">
          <span v-if="model" class="tutor__model">由 {{ model }} 生成</span>
          <el-button type="primary" :icon="Promotion" :loading="sending" :disabled="!canSend" @click="send()">
            发送
          </el-button>
        </div>
      </footer>
    </div>
  </el-drawer>
</template>

<style scoped>
.tutor {
  display: flex;
  height: 100%;
  flex-direction: column;
  gap: 12px;
}

.tutor__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.tutor__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tutor__title strong {
  display: block;
  font-size: 16px;
  color: var(--el-text-color-primary);
}

.tutor__title span {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tutor__hint {
  margin: 0;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.tutor__body {
  flex: 1;
  overflow-y: auto;
  padding-right: 4px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tutor__presets-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tutor__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.tutor__chip {
  padding: 6px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: #fff;
  font-size: 12px;
  color: var(--el-text-color-regular);
  cursor: pointer;
}

.tutor__chip:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.bubble {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.bubble--user {
  justify-content: flex-end;
}

.bubble__avatar {
  flex: none;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 11px;
  line-height: 18px;
}

.bubble__text {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
  max-width: 360px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bubble__para {
  margin: 0;
  white-space: pre-wrap;
}

.bubble__code {
  margin: 0;
  padding: 8px 10px;
  overflow-x: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre;
}

.bubble--user .bubble__text {
  background: var(--el-color-primary);
  color: #fff;
}

.bubble__text--pending {
  color: var(--el-text-color-secondary);
}

.tutor__alert {
  margin-top: 4px;
}

.tutor__practice {
  padding: 10px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
}

.tutor__practice-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tutor__practice-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 13px;
}

.tutor__footer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tutor__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tutor__model {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
</style>
