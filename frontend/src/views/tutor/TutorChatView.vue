<script setup lang="ts">
/**
 * 独立 AI 助教页。
 *
 * 学生不做题也能用：左侧是真实题库（只显示后端返回的题），选中一道就设为上下文；
 * 右侧是对话区，历史只存在 localStorage，不落库、不进后端会话。
 *
 * 与批改报告里的助教抽屉共用同一个模型与提示词口径，但这里不绑定提交：
 * 服务端只拿到「选中的题目 + 问题 + 历史」，不读代码与判题结果。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ChatDotRound, Delete, MagicStick, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { listAssessmentProblems } from '@/api/modules/oj'
import { askTutorChat } from '@/api/modules/tutor'
import { describeError, toApiError } from '@/api/errors'
import { difficultyMetaLabel } from '@/components/ojMeta'
import { useAuthStore } from '@/stores/auth'
import type { AssessmentProblem, SubmissionTutorTurn } from '@/api/types'

interface Bubble {
  role: 'user' | 'assistant'
  content: string
}

interface Segment {
  type: 'text' | 'code'
  content: string
}

/** 本地会话键：只存本机浏览器，服务端不保存任何会话。 */
const STORE_KEY = 'ai-tutor:v1'

const auth = useAuthStore()

const problems = ref<AssessmentProblem[]>([])
const problemsLoading = ref(true)
const problemError = ref('')
const selected = ref<AssessmentProblem | null>(null)

const messages = ref<Bubble[]>([])
const draft = ref('')
const sending = ref(false)
const unavailable = ref('')
const model = ref('')
const scroller = ref<HTMLElement | null>(null)

const presets = [
  '帮我看看这段代码有什么问题',
  '这个知识点怎么理解',
  '推荐一道练习题',
]

const canSend = computed(() => draft.value.trim().length > 0 && !sending.value)

const greeting = computed(() => {
  const name = auth.displayName || '同学'
  if (selected.value) {
    return `你好，${name}。我是 AI 助教，现在在聊《${selected.value.title}》，可以讲知识点、看代码、也可以让我推荐练习。`
  }
  return `你好，${name}。我是 AI 助教，可以讲知识点、看代码片段、推荐练习。先从左侧选一道题，或者直接提问。`
})

/** 模型偶尔给一小段示例代码，只把 ``` 围栏包住的部分按等宽渲染。 */
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

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const node = scroller.value
  if (node) node.scrollTop = node.scrollHeight
}

function persist(): void {
  if (!auth.displayName) return
  try {
    localStorage.setItem(
      STORE_KEY,
      JSON.stringify({
        name: auth.displayName,
        problemId: selected.value?.id ?? null,
        messages: messages.value,
      }),
    )
  } catch {
    // 隐私模式下 localStorage 可能不可写，此时只是不保存历史，不影响对话。
  }
}

/** 恢复本机会话；名字不匹配（换了账号）就丢弃，避免串到别人身上。 */
function restore(): string | null {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return null
    const data = JSON.parse(raw) as {
      name?: string
      problemId?: string | null
      messages?: Bubble[]
    }
    if (data.name !== auth.displayName) return null
    if (Array.isArray(data.messages)) {
      messages.value = data.messages
        .filter(
          (item): item is Bubble =>
            (item?.role === 'user' || item?.role === 'assistant') &&
            typeof item.content === 'string' &&
            item.content.length > 0,
        )
        .slice(-40)
    }
    return typeof data.problemId === 'string' ? data.problemId : null
  } catch {
    return null
  }
}

async function loadProblems(): Promise<void> {
  problemsLoading.value = true
  problemError.value = ''
  try {
    const page = await listAssessmentProblems()
    problems.value = page.items
    const restoredProblemId = restore()
    if (restoredProblemId) {
      const found = problems.value.find((item) => item.id === restoredProblemId)
      if (found) selected.value = found
    }
  } catch (error) {
    problemError.value = describeError(toApiError(error))
  } finally {
    problemsLoading.value = false
  }
}

function selectProblem(problem: AssessmentProblem): void {
  if (selected.value?.id === problem.id) return
  const hadConversation = messages.value.length > 0
  selected.value = problem
  // 历史里讨论的是上一道题，切题后继续会让模型上下文自相矛盾，因此开新对话。
  if (hadConversation) {
    messages.value = []
    unavailable.value = ''
    ElMessage.info(`已切换到《${problem.title}》，开始新的对话`)
  }
}

function clearConversation(): void {
  messages.value = []
  unavailable.value = ''
  model.value = ''
  persist()
}

async function send(text?: string): Promise<void> {
  const question = (text ?? draft.value).trim()
  if (!question || sending.value) return
  unavailable.value = ''

  // 历史只带之前的轮次，本次问题单独放 message，避免模型看到重复消息。
  const history: SubmissionTutorTurn[] = messages.value.map((item) => ({
    role: item.role,
    content: item.content,
  }))
  messages.value.push({ role: 'user', content: question })
  draft.value = ''
  sending.value = true
  persist()
  await scrollToBottom()

  try {
    const reply = await askTutorChat({
      message: question,
      problemId: selected.value?.id,
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
    unavailable.value = describeError(apiError)
    ElMessage.error(unavailable.value)
  } finally {
    sending.value = false
    persist()
    await scrollToBottom()
  }
}

watch([messages, selected], persist, { deep: true })

onMounted(async () => {
  await loadProblems()
  await scrollToBottom()
})
</script>

<template>
  <div class="tutor-page">
    <aside class="tutor-page__aside">
      <header class="aside__head">
        <h2>题库</h2>
        <span>{{ problems.length }} 道题</span>
      </header>
      <p class="aside__hint">选一道题，助教会结合题面回答</p>

      <div v-if="problemsLoading" class="aside__loading">
        <el-skeleton :rows="6" animated />
      </div>
      <el-alert
        v-else-if="problemError"
        type="warning"
        :closable="false"
        show-icon
        :title="problemError"
      />
      <template v-else>
        <ul v-if="problems.length > 0" class="problem-list">
          <li
            v-for="problem in problems"
            :key="problem.id"
            :class="{ 'is-active': selected?.id === problem.id }"
            role="button"
            tabindex="0"
            @click="selectProblem(problem)"
            @keydown.enter.prevent="selectProblem(problem)"
          >
            <div class="problem-list__main">
              <strong>{{ problem.title }}</strong>
              <el-tag
                :type="difficultyMetaLabel(problem.difficulty).type"
                effect="plain"
                size="small"
              >
                {{ difficultyMetaLabel(problem.difficulty).label }}
              </el-tag>
            </div>
            <span v-if="selected?.id === problem.id" class="problem-list__current">当前上下文</span>
          </li>
        </ul>
        <el-empty v-else description="题库暂无题目" :image-size="60" />
      </template>
    </aside>

    <section class="tutor-page__chat">
      <header class="chat__head">
        <div class="chat__title">
          <el-icon class="chat__icon"><MagicStick /></el-icon>
          <div>
            <strong>AI 助教</strong>
            <span>{{ selected ? `当前题目：${selected.title}` : '未选择题目，可直接问知识点' }}</span>
          </div>
        </div>
        <div class="chat__actions">
          <el-tag v-if="model" effect="plain" size="small">{{ model }}</el-tag>
          <el-button
            text
            :icon="Delete"
            :disabled="messages.length === 0 && !unavailable"
            @click="clearConversation"
          >
            清空对话
          </el-button>
        </div>
      </header>

      <div ref="scroller" class="chat__body">
        <div class="greeting">
          <el-icon><ChatDotRound /></el-icon>
          <p>{{ greeting }}</p>
        </div>

        <div v-if="messages.length === 0" class="presets">
          <span class="presets__label">可以这样问：</span>
          <div class="presets__chips">
            <button
              v-for="preset in presets"
              :key="preset"
              type="button"
              class="presets__chip"
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
            <template v-for="(segment, segIndex) in segments(bubble.content)" :key="segIndex">
              <pre v-if="segment.type === 'code'" class="bubble__code">{{ segment.content }}</pre>
              <p v-else-if="segment.content.trim()" class="bubble__para">{{ segment.content.trim() }}</p>
            </template>
          </div>
        </div>

        <div v-if="sending" class="bubble bubble--assistant">
          <span class="bubble__avatar">助教</span>
          <p class="bubble__text bubble__pending">正在组织回答…</p>
        </div>

        <el-alert
          v-if="unavailable"
          :title="unavailable"
          type="warning"
          :closable="false"
          show-icon
          class="chat__alert"
        />
      </div>

      <footer class="chat__footer">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="1000"
          show-word-limit
          :placeholder="
            selected
              ? `就《${selected.title}》继续提问，或贴一段代码`
              : '问一个知识点，或先在左侧选一道题'
          "
          @keydown.enter.exact.prevent="send()"
        />
        <div class="chat__send">
          <span class="chat__tip">
            回答由 AI 生成，只讲思路与方法，不会直接给出可提交的完整代码。
          </span>
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="sending"
            :disabled="!canSend"
            @click="send()"
          >
            发送
          </el-button>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.tutor-page {
  display: grid;
  height: calc(100vh - 168px);
  min-height: 560px;
  gap: 16px;
  grid-template-columns: 300px minmax(0, 1fr);
}

.tutor-page__aside {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  padding: 16px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}

.aside__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.aside__head h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 15px;
}

.aside__head span {
  color: var(--app-muted);
  font-size: 11px;
}

.aside__hint {
  margin: 6px 0 12px;
  color: var(--app-muted);
  font-size: 11px;
}

.aside__loading {
  padding-top: 6px;
}

.problem-list {
  overflow-y: auto;
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
}

.problem-list li {
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.problem-list li:hover {
  background: var(--app-surface-soft);
}

.problem-list li.is-active {
  border-color: var(--app-brand);
  background: rgba(76, 125, 255, 0.07);
}

.problem-list__main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.problem-list__main strong {
  overflow: hidden;
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.problem-list__current {
  display: block;
  margin-top: 4px;
  color: var(--app-brand);
  font-size: 10px;
}

.tutor-page__chat {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}

.chat__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--app-border);
}

.chat__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.chat__icon {
  color: #4c7dff;
  font-size: 20px;
}

.chat__title strong {
  display: block;
  color: var(--app-ink);
  font-size: 15px;
}

.chat__title span {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 11px;
}

.chat__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat__body {
  overflow-y: auto;
  flex: 1;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.greeting {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid rgba(76, 125, 255, 0.18);
  border-radius: 8px;
  background: rgba(76, 125, 255, 0.05);
  color: var(--app-ink);
  font-size: 13px;
  line-height: 1.7;
}

.greeting p {
  margin: 0;
}

.presets__label {
  color: var(--app-muted);
  font-size: 12px;
}

.presets__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.presets__chip {
  padding: 6px 12px;
  border: 1px solid var(--app-border);
  border-radius: 999px;
  background: #fff;
  color: var(--app-ink);
  font-size: 12px;
  cursor: pointer;
}

.presets__chip:hover {
  border-color: var(--app-brand);
  color: var(--app-brand);
}

.bubble {
  display: flex;
  align-items: flex-start;
  gap: 8px;
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
  display: flex;
  max-width: 720px;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--app-ink);
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
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

.bubble__pending {
  color: var(--app-muted);
}

.chat__alert {
  margin-top: 4px;
}

.chat__footer {
  padding: 12px 16px 14px;
  border-top: 1px solid var(--app-border);
}

.chat__send {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.chat__tip {
  color: var(--app-muted);
  font-size: 11px;
}

@media (max-width: 960px) {
  .tutor-page {
    height: auto;
    grid-template-columns: 1fr;
  }

  .tutor-page__aside {
    max-height: 260px;
  }

  .tutor-page__chat {
    height: 620px;
  }
}
</style>
