<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, EditPen, Plus, Promotion, RefreshRight, Delete } from '@element-plus/icons-vue'

import type { ChatMessage, SessionSummary } from '../api/types'
import * as chatApi from '../api/chatApi'
import { postSseJsonStream } from '../utils/sseJsonStream'

const sessions = ref<SessionSummary[]>([])
const activeSessionId = ref<string | null>(null)
const messages = ref<ChatMessage[]>([])
const input = ref('')
const loadingSessions = ref(false)
const loadingMessages = ref(false)
const sending = ref(false)
const scrollEl = ref<HTMLElement | null>(null)

let abort: AbortController | null = null

const activeSession = computed(() => sessions.value.find((s) => s.id === activeSessionId.value) ?? null)

async function scrollToBottom() {
  await nextTick()
  const el = scrollEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

async function refreshSessions() {
  loadingSessions.value = true
  try {
    sessions.value = await chatApi.listSessions()
    if (!activeSessionId.value && sessions.value.length > 0) {
      activeSessionId.value = sessions.value[0]!.id
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载会话失败')
  } finally {
    loadingSessions.value = false
  }
}

async function loadMessages(sessionId: string) {
  loadingMessages.value = true
  try {
    messages.value = await chatApi.listMessages(sessionId)
    await scrollToBottom()
  } catch (e: any) {
    ElMessage.error(e?.message || '加载消息失败')
  } finally {
    loadingMessages.value = false
  }
}

onMounted(async () => {
  await refreshSessions()
})

watch(
  () => activeSessionId.value,
  async (id) => {
    if (!id) {
      messages.value = []
      return
    }
    await loadMessages(id)
  },
)

async function onNewSession() {
  try {
    const s = await chatApi.createSession('新对话')
    sessions.value = [s, ...sessions.value.filter((x) => x.id !== s.id)]
    activeSessionId.value = s.id
    messages.value = []
  } catch (e: any) {
    ElMessage.error(e?.message || '创建会话失败')
  }
}

async function onSelectSession(id: string) {
  if (sending.value) {
    ElMessage.warning('正在生成回复，请先停止或等待完成')
    return
  }
  activeSessionId.value = id
}

async function onRenameSession(s: SessionSummary) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的会话标题', '重命名', {
      inputValue: s.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '标题不能为空',
    })
    await chatApi.renameSession(s.id, value)
    await refreshSessions()
    activeSessionId.value = s.id
  } catch {
    // 用户取消
  }
}

async function onDeleteSession(s: SessionSummary) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${s.title}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await chatApi.deleteSession(s.id)
    if (activeSessionId.value === s.id) {
      activeSessionId.value = null
    }
    await refreshSessions()
    if (!activeSessionId.value && sessions.value.length > 0) {
      activeSessionId.value = sessions.value[0]!.id
    }
  } catch {
    // 取消
  }
}

function stop() {
  abort?.abort()
  abort = null
  sending.value = false
}

async function onSend() {
  const sid = activeSessionId.value
  const text = input.value.trim()
  if (!sid) {
    ElMessage.warning('请先新建会话')
    return
  }
  if (!text) return
  if (sending.value) return

  input.value = ''
  sending.value = true
  abort = new AbortController()

  // 先乐观展示用户消息；流式结束后再与服务端对齐
  const optimisticUser: ChatMessage = {
    id: `local-${Date.now()}`,
    role: 'USER',
    content: text,
    createdAt: new Date().toISOString(),
  }
  messages.value = [...messages.value, optimisticUser]
  await scrollToBottom()

  let assistantId: string | null = null

  try {
    await postSseJsonStream(
      '/api/chat/stream',
      { sessionId: sid, content: text },
      (evt) => {
        if (evt.type === 'start') {
          assistantId = evt.assistantMessageId
          messages.value = [
            ...messages.value,
            {
              id: assistantId,
              role: 'ASSISTANT',
              content: '',
              createdAt: new Date().toISOString(),
            },
          ]
          void scrollToBottom()
          return
        }
        if (evt.type === 'delta' && evt.text) {
          if (!assistantId) return
          messages.value = messages.value.map((m) =>
            m.id === assistantId ? { ...m, content: m.content + evt.text! } : m,
          )
          void scrollToBottom()
          return
        }
        if (evt.type === 'error') {
          throw new Error(evt.message || '模型输出失败')
        }
      },
      { signal: abort.signal },
    )

    await loadMessages(sid)
    await refreshSessions()
  } catch (e: any) {
    if (e?.name === 'AbortError') {
      ElMessage.info('已停止生成')
    } else {
      ElMessage.error(e?.message || '发送失败')
    }
    // 失败时尽量与服务端对齐，避免 UI 与后端不一致
    try {
      await loadMessages(sid)
    } catch {
      // ignore
    }
  } finally {
    sending.value = false
    abort = null
  }
}

function bubbleClass(role: ChatMessage['role']) {
  if (role === 'USER') return 'bubble user'
  if (role === 'ASSISTANT') return 'bubble assistant'
  return 'bubble system'
}

const streamingAssistant = computed(() => {
  if (!sending.value) return null
  const last = messages.value.filter((m) => m.role === 'ASSISTANT').at(-1)
  if (!last) return null
  return last.content.length === 0 ? last : null
})
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="logo">
          <el-icon><ChatDotRound /></el-icon>
        </div>
        <div class="brand-text">
          <div class="title">豆包风格助手</div>
          <div class="sub">Vue3 + Spring AI 2.0 SSE</div>
        </div>
      </div>

      <el-button class="new-btn" type="primary" :icon="Plus" @click="onNewSession">新建对话</el-button>

      <el-scrollbar class="session-scroll">
        <div v-if="loadingSessions" class="muted pad">加载中…</div>
        <div v-else-if="sessions.length === 0" class="muted pad">暂无会话，点击上方新建</div>
        <div v-else class="session-list">
          <div
            v-for="s in sessions"
            :key="s.id"
            class="session-item"
            :class="{ active: s.id === activeSessionId }"
            @click="onSelectSession(s.id)"
          >
            <div class="session-title">{{ s.title }}</div>
            <div class="session-actions" @click.stop>
              <el-button text :icon="EditPen" @click="onRenameSession(s)" />
              <el-button text type="danger" :icon="Delete" @click="onDeleteSession(s)" />
            </div>
          </div>
        </div>
      </el-scrollbar>
    </aside>

    <main class="main">
      <header class="main-header">
        <div class="main-title">
          {{ activeSession?.title || '请选择会话' }}
        </div>
        <div class="main-actions">
          <el-button :icon="RefreshRight" @click="refreshSessions">刷新</el-button>
        </div>
      </header>

      <div ref="scrollEl" class="messages">
        <div v-if="!activeSessionId" class="empty">
          <div class="empty-title">开始对话</div>
          <div class="empty-sub">左侧新建会话，右侧输入问题；支持 SSE 打字机输出。</div>
        </div>

        <div v-else-if="loadingMessages" class="muted pad">加载消息…</div>

        <div v-else class="msg-col">
          <div v-for="m in messages" :key="m.id" class="row" :class="m.role === 'USER' ? 'right' : 'left'">
            <div :class="bubbleClass(m.role)">
              <div v-if="m.role === 'ASSISTANT' && m.content.length === 0 && streamingAssistant?.id === m.id" class="typing">
                <span /><span /><span />
              </div>
              <div v-else class="text">{{ m.content }}</div>
            </div>
          </div>
        </div>
      </div>

      <footer class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入消息，Shift+Enter 换行；Enter 发送"
          :disabled="!activeSessionId || sending"
          @keydown.enter.exact.prevent="onSend"
        />
        <div class="composer-actions">
          <el-button v-if="sending" @click="stop">停止</el-button>
          <el-button type="primary" :icon="Promotion" :loading="sending" :disabled="!activeSessionId" @click="onSend">
            发送
          </el-button>
        </div>
      </footer>
    </main>
  </div>
</template>

<style scoped>
.shell {
  height: 100%;
  display: grid;
  grid-template-columns: 300px 1fr;
}

.sidebar {
  background: #fff;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  min-width: 280px;
}

.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 16px 14px 10px;
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2b6cff, #7aa6ff);
  display: grid;
  place-items: center;
  color: #fff;
}

.brand-text .title {
  font-weight: 700;
  line-height: 1.1;
}
.brand-text .sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--muted);
}

.new-btn {
  margin: 0 12px 10px;
  width: calc(100% - 24px);
  border-radius: 10px;
}

.session-scroll {
  flex: 1;
  padding: 6px 8px 10px;
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-item {
  border: 1px solid transparent;
  border-radius: 12px;
  padding: 10px 10px;
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  background: #f7f8fb;
}
.session-item:hover {
  border-color: #e3e7ef;
}
.session-item.active {
  border-color: #cfe0ff;
  background: #eef4ff;
}

.session-title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 170px;
}

.session-actions {
  display: flex;
  gap: 2px;
}

.main {
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-width: 0;
}

.main-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  background: #fff;
}
.main-title {
  font-weight: 650;
}

.messages {
  overflow: auto;
  padding: 16px;
}

.empty {
  height: 100%;
  display: grid;
  place-content: center;
  text-align: center;
  gap: 8px;
  color: var(--muted);
}
.empty-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text);
}

.msg-col {
  max-width: 920px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.row {
  display: flex;
}
.row.left {
  justify-content: flex-start;
}
.row.right {
  justify-content: flex-end;
}

.bubble {
  max-width: min(720px, 92%);
  padding: 12px 12px;
  border-radius: 14px;
  line-height: 1.55;
  font-size: 14px;
  border: 1px solid transparent;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.user {
  background: var(--bubble-user);
  color: #fff;
  border-radius: 14px 14px 4px 14px;
}
.bubble.assistant {
  background: var(--bubble-assistant);
  color: var(--text);
  border-color: #e9eaee;
  border-radius: 14px 14px 14px 4px;
}
.bubble.system {
  background: #fff7ed;
  border-color: #fed7aa;
}

.composer {
  border-top: 1px solid var(--border);
  background: #fff;
  padding: 12px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: end;
}

.composer-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.muted {
  color: var(--muted);
}
.pad {
  padding: 10px 8px;
}

.typing {
  display: flex;
  gap: 6px;
  align-items: center;
  height: 18px;
}
.typing span {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #9aa3af;
  animation: bounce 1.1s infinite ease-in-out;
}
.typing span:nth-child(2) {
  animation-delay: 0.15s;
}
.typing span:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.55;
  }
  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}
</style>
