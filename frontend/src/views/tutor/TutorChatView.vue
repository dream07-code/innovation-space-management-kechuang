<script setup lang="ts">
/**
 * 独立 AI 助教页：对话 + 学习画像两个标签页。
 *
 * 会话与消息都由后端保存（tutor_conversation / tutor_message），前端只记住当前
 * conversationId：不传 id 时后端自动新建会话并回传 id，所以「直接发第一句」和
 * 「点新建再发」走同一条路径。换会话就重新拉消息，不在本地拼历史。
 *
 * 学习画像由后端按本人提交/判题/已发布成绩实时聚合，没有数据时显示空状态。
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  ChatDotRound,
  Delete,
  MagicStick,
  MoreFilled,
  Plus,
  Promotion,
  TrendCharts,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { listAssessmentProblems } from '@/api/modules/oj'
import {
  askTutorChat,
  deleteTutorConversation,
  getTutorConversationMessages,
  listTutorConversations,
  renameTutorConversation,
} from '@/api/modules/tutor'
import { describeError, toApiError } from '@/api/errors'
import { newIdempotencyKey } from '@/api/idempotency'
import { difficultyMetaLabel } from '@/components/ojMeta'
import TutorProfilePanel from '@/components/tutor/TutorProfilePanel.vue'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/datetime'
import type { AssessmentProblem, TutorConversation } from '@/api/types'

interface Bubble {
  role: 'user' | 'assistant'
  content: string
}

interface Segment {
  type: 'text' | 'code'
  content: string
}

const auth = useAuthStore()

const tab = ref<'chat' | 'profile'>('chat')

const conversations = ref<TutorConversation[]>([])
const conversationsLoading = ref(true)
const conversationsError = ref('')
const activeId = ref<string | null>(null)
const boundProblemId = ref<string | null>(null)

const messages = ref<Bubble[]>([])
const messagesLoading = ref(false)
const truncated = ref(false)

const problems = ref<AssessmentProblem[]>([])
const problemsLoading = ref(true)
const problemsError = ref('')
const selectedProblemId = ref<string>('')

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

const activeConversation = computed(
  () => conversations.value.find((item) => item.id === activeId.value) ?? null,
)

/** 新会话用下拉框里选的题；已有会话固定用会话自己绑定的题（换题请新建对话）。 */
const contextProblemId = computed(() =>
  activeId.value ? boundProblemId.value : selectedProblemId.value || null,
)

const contextProblem = computed(
  () => problems.value.find((item) => item.id === contextProblemId.value) ?? null,
)

const greeting = computed(() => {
  const name = auth.displayName || '同学'
  if (contextProblem.value) {
    return `你好，${name}。我是 AI 助教，现在在聊《${contextProblem.value.title}》，可以讲知识点、看代码、也可以让我推荐练习。`
  }
  return `你好，${name}。我是 AI 助教，可以讲知识点、看代码片段、推荐练习。先选一道题，或者直接提问。`
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

async function loadProblems(): Promise<void> {
  problemsLoading.value = true
  problemsError.value = ''
  try {
    const page = await listAssessmentProblems()
    problems.value = page.items
  } catch (error) {
    problemsError.value = describeError(toApiError(error))
  } finally {
    problemsLoading.value = false
  }
}

/** silent 用于发消息后刷新排序，不打断当前对话（不清空消息、不弹骨架屏）。 */
async function loadConversations(silent = false): Promise<void> {
  if (!silent) conversationsLoading.value = true
  conversationsError.value = ''
  try {
    const page = await listTutorConversations(1, 50)
    conversations.value = page.items
    // 只有显式刷新列表时才因为「会话不在列表里」重置：发消息后的静默刷新可能还没包含
    // 刚新建的会话，这里清空会把刚出现的对话和错误提示一起抹掉。
    if (!silent && activeId.value && !page.items.some((item) => item.id === activeId.value)) {
      resetChat()
    }
  } catch (error) {
    if (!silent) conversationsError.value = describeError(toApiError(error))
  } finally {
    if (!silent) conversationsLoading.value = false
  }
}

function resetChat(): void {
  activeId.value = null
  boundProblemId.value = null
  messages.value = []
  truncated.value = false
  unavailable.value = ''
  model.value = ''
}

function newConversation(): void {
  resetChat()
  selectedProblemId.value = ''
}

async function openConversation(item: TutorConversation): Promise<void> {
  if (activeId.value === item.id) return
  activeId.value = item.id
  boundProblemId.value = item.problemId ?? null
  unavailable.value = ''
  model.value = ''
  messagesLoading.value = true
  try {
    const data = await getTutorConversationMessages(item.id)
    messages.value = (data.items ?? []).map((message) => ({
      role: message.role,
      content: message.content,
    }))
    truncated.value = data.truncated
    boundProblemId.value = data.conversation.problemId ?? null
  } catch (error) {
    const apiError = toApiError(error)
    // 会话可能刚被删除：回到新对话，不留下一个打不开的空白页。
    if (apiError.httpStatus === 404) {
      resetChat()
      ElMessage.info('这个会话已不存在，已为你切换到新对话')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    messagesLoading.value = false
    await scrollToBottom()
  }
}

async function onCommand(command: string, item: TutorConversation): Promise<void> {
  if (command === 'rename') await rename(item)
  if (command === 'delete') await remove(item)
}

async function rename(item: TutorConversation): Promise<void> {
  try {
    const result = await ElMessageBox.prompt('给这个会话起个名字，方便以后找回。', '重命名会话', {
      inputValue: item.title,
      inputValidator: (value) => (value && value.trim().length > 0 ? true : '标题不能为空'),
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    const title = String(result.value ?? '').trim()
    if (!title || title === item.title) return
    await renameTutorConversation(item.id, title)
    await loadConversations(true)
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function remove(item: TutorConversation): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除「${item.title}」后，这段对话的消息会一起删除，无法恢复。`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    await deleteTutorConversation(item.id, newIdempotencyKey())
    if (activeId.value === item.id) resetChat()
    await loadConversations(true)
    ElMessage.success('会话已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function send(text?: string): Promise<void> {
  const question = (text ?? draft.value).trim()
  if (!question || sending.value) return
  unavailable.value = ''
  draft.value = ''
  messages.value.push({ role: 'user', content: question })
  sending.value = true
  await scrollToBottom()

  try {
    const reply = await askTutorChat({
      message: question,
      ...(activeId.value ? { conversationId: activeId.value } : {}),
      ...(!activeId.value && selectedProblemId.value
        ? { problemId: selectedProblemId.value }
        : {}),
    })
    if (!activeId.value) {
      // 首句由后端建会话：拿到 id 后后续提问都带回去，历史由服务端接管。
      activeId.value = reply.conversationId
      boundProblemId.value = selectedProblemId.value || null
    }
    if (reply.available && reply.reply) {
      messages.value.push({ role: 'assistant', content: reply.reply })
      model.value = reply.model ?? ''
    } else {
      unavailable.value = reply.error || 'AI 助教暂不可用，请稍后重试。'
    }
    await loadConversations(true)
  } catch (error) {
    const apiError = toApiError(error)
    unavailable.value = describeError(apiError)
    ElMessage.error(unavailable.value)
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

onMounted(async () => {
  await Promise.all([loadProblems(), loadConversations()])
  await scrollToBottom()
})
</script>

<template>
  <div class="tutor-page">
    <el-tabs v-model="tab" class="tutor-tabs">
      <el-tab-pane name="chat">
        <template #label>
          <span class="tabs__label"><el-icon><ChatDotRound /></el-icon>对话</span>
        </template>

        <div class="chat-layout">
          <aside class="conversations">
            <header class="conversations__head">
              <div>
                <h2>我的会话</h2>
                <span>{{ conversations.length }} 个话题</span>
              </div>
              <el-button type="primary" size="small" :icon="Plus" @click="newConversation">
                新建
              </el-button>
            </header>

            <div v-if="conversationsLoading" class="conversations__loading">
              <el-skeleton :rows="5" animated />
            </div>
            <el-alert
              v-else-if="conversationsError"
              type="warning"
              :closable="false"
              show-icon
              :title="conversationsError"
            />
            <template v-else>
              <ul v-if="conversations.length > 0" class="conversation-list">
                <li
                  v-for="item in conversations"
                  :key="item.id"
                  :class="{ 'is-active': activeId === item.id }"
                  role="button"
                  tabindex="0"
                  @click="openConversation(item)"
                  @keydown.enter.prevent="openConversation(item)"
                >
                  <div class="conversation__main">
                    <strong :title="item.title">{{ item.title }}</strong>
                    <span>
                      {{ formatDateTime(item.updatedAt) }} · {{ item.messageCount }} 条
                    </span>
                  </div>
                  <el-dropdown
                    trigger="click"
                    @command="(command: string) => onCommand(command, item)"
                    @click.stop
                  >
                    <el-button text :icon="MoreFilled" size="small" class="conversation__more" />
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="rename">重命名</el-dropdown-item>
                        <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </li>
              </ul>
              <div v-else class="conversations__empty">
                <p>还没有会话</p>
                <span>直接在右侧提问，会自动新建一个会话。</span>
              </div>
            </template>
          </aside>

          <section class="chat">
            <header class="chat__head">
              <div class="chat__title">
                <el-icon class="chat__icon"><MagicStick /></el-icon>
                <div>
                  <strong>{{ activeConversation?.title || '新对话' }}</strong>
                  <span>{{
                    contextProblem ? `当前题目：${contextProblem.title}` : '未选择题目，可直接问知识点'
                  }}</span>
                </div>
              </div>
              <div class="chat__actions">
                <el-select
                  v-model="selectedProblemId"
                  class="chat__problem"
                  size="small"
                  filterable
                  clearable
                  :disabled="activeId !== null"
                  :loading="problemsLoading"
                  placeholder="关联题目（可不选）"
                >
                  <el-option
                    v-for="problem in problems"
                    :key="problem.id"
                    :label="problem.title"
                    :value="problem.id"
                  />
                </el-select>
                <el-tag v-if="model" effect="plain" size="small">{{ model }}</el-tag>
                <el-button
                  text
                  :icon="Delete"
                  :disabled="messages.length === 0 && !unavailable"
                  @click="newConversation"
                >
                  清空
                </el-button>
              </div>
            </header>

            <p v-if="problemsError" class="chat__notice">{{ problemsError }}</p>

            <div ref="scroller" class="chat__body">
              <div class="greeting">
                <el-icon><ChatDotRound /></el-icon>
                <p>{{ greeting }}</p>
              </div>

              <p v-if="truncated" class="chat__notice">只显示最近 200 条消息，更早的消息未加载。</p>

              <div v-if="messagesLoading" class="chat__loading">
                <el-skeleton :rows="4" animated />
              </div>

              <template v-else>
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
                    <template
                      v-for="(segment, segIndex) in segments(bubble.content)"
                      :key="segIndex"
                    >
                      <pre v-if="segment.type === 'code'" class="bubble__code">{{ segment.content }}</pre>
                      <p v-else-if="segment.content.trim()" class="bubble__para">
                        {{ segment.content.trim() }}
                      </p>
                    </template>
                  </div>
                </div>

                <div v-if="sending" class="bubble bubble--assistant">
                  <span class="bubble__avatar">助教</span>
                  <p class="bubble__text bubble__pending">正在组织回答…</p>
                </div>
              </template>

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
                  contextProblem
                    ? `就《${contextProblem.title}》继续提问，或贴一段代码`
                    : '问一个知识点，或先选一道题'
                "
                @keydown.enter.exact.prevent="send()"
              />
              <div class="chat__send">
                <span class="chat__tip">
                  回答由 AI 生成，只讲思路与方法，不会直接给出可提交的完整代码。对话会保存在你的账号下。
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
      </el-tab-pane>

      <el-tab-pane name="profile" lazy>
        <template #label>
          <span class="tabs__label"><el-icon><TrendCharts /></el-icon>学习画像</span>
        </template>
        <TutorProfilePanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.tutor-page {
  min-height: 0;
}

.tutor-tabs {
  border-radius: 8px;
  background: transparent;
}

.tabs__label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.chat-layout {
  display: grid;
  height: calc(100vh - 220px);
  min-height: 540px;
  gap: 16px;
  grid-template-columns: 280px minmax(0, 1fr);
}

.conversations {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  padding: 14px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}

.conversations__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.conversations__head h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 15px;
}

.conversations__head span {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 11px;
}

.conversations__loading {
  padding-top: 10px;
}

.conversations__empty {
  padding: 24px 4px;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.8;
}

.conversations__empty p {
  margin: 0 0 4px;
  color: var(--app-ink);
  font-size: 13px;
}

.conversation-list {
  overflow-y: auto;
  flex: 1;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.conversation-list li {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 8px 8px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.conversation-list li:hover {
  background: var(--app-surface-soft);
}

.conversation-list li.is-active {
  border-color: var(--app-brand);
  background: rgba(76, 125, 255, 0.07);
}

.conversation__main {
  overflow: hidden;
  flex: 1;
  min-width: 0;
}

.conversation__main strong {
  display: block;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation__main span {
  display: block;
  margin-top: 3px;
  color: var(--app-muted);
  font-size: 10px;
}

.conversation__more {
  visibility: hidden;
}

.conversation-list li:hover .conversation__more,
.conversation-list li.is-active .conversation__more {
  visibility: visible;
}

.chat {
  display: flex;
  overflow: hidden;
  min-width: 0;
  flex-direction: column;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}

.chat__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--app-border);
}

.chat__title {
  display: flex;
  overflow: hidden;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.chat__icon {
  flex: none;
  color: #4c7dff;
  font-size: 20px;
}

.chat__title strong {
  display: block;
  overflow: hidden;
  max-width: 320px;
  color: var(--app-ink);
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__title span {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 11px;
}

.chat__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.chat__problem {
  width: 190px;
}

.chat__notice {
  margin: 8px 16px 0;
  color: var(--app-muted);
  font-size: 11px;
}

.chat__body {
  display: flex;
  overflow-y: auto;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}

.chat__loading {
  padding-top: 4px;
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
  .chat-layout {
    height: auto;
    grid-template-columns: 1fr;
  }

  .conversations {
    max-height: 240px;
  }

  .chat {
    height: 600px;
  }

  .chat__problem {
    width: 130px;
  }
}
</style>
