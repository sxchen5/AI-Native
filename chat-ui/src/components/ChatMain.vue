<script setup lang="ts">
import {
  CircleClose,
  DocumentCopy,
  Edit,
  EditPen,
  Loading,
  Microphone,
  Promotion,
  RefreshRight,
  Top,
  Bottom,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import type { ChatMessage } from '../api/types'
import { useChatStore } from '../stores/chat'
import { renderAiMarkdown } from '../utils/markdown'
import { markdownToPlainText } from '../utils/plainText'

const { t } = useI18n()
const router = useRouter()
const chat = useChatStore()

const props = defineProps<{
  /** 内嵌在画布分屏左侧时隐藏会话标题区 */
  hideThreadHead?: boolean
}>()

const bottomAnchor = ref<HTMLElement | null>(null)
const userEdits = reactive<Record<string, string>>({})
const feedback = reactive<Record<string, 'up' | 'down' | null>>({})
const hoveringRow = reactive<Record<string, boolean>>({})
const editingUserId = ref<string | null>(null)

function md(html: string) {
  return renderAiMarkdown(html)
}

function isLastMessage(idx: number) {
  return idx === chat.messages.length - 1
}

function isAssistantStreaming(m: ChatMessage) {
  return m.role === 'ASSISTANT' && m.content.length === 0 && chat.sending
}

function showUserToolbar(idx: number, m: ChatMessage) {
  if (m.role !== 'USER') return false
  if (editingUserId.value === m.id) return true
  return isLastMessage(idx) || !!hoveringRow[m.id]
}

function showAiToolbar(idx: number, m: ChatMessage) {
  if (m.role !== 'ASSISTANT') return false
  if (isAssistantStreaming(m)) return false
  return isLastMessage(idx) || !!hoveringRow[m.id]
}

function onRowHover(messageId: string, inside: boolean) {
  hoveringRow[messageId] = inside
}

function syncUserEditsFromMessages() {
  for (const m of chat.messages) {
    if (m.role === 'USER' && userEdits[m.id] === undefined) {
      userEdits[m.id] = m.content
    }
  }
}

async function scrollToBottom(smooth = true) {
  await nextTick()
  bottomAnchor.value?.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'end' })
}

watch(
  () => chat.messages,
  () => {
    syncUserEditsFromMessages()
    void scrollToBottom(true)
  },
  { deep: true, immediate: true },
)

watch(
  () => chat.activeSessionId,
  () => {
    editingUserId.value = null
    void scrollToBottom(false)
  },
)

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('session.copied'))
  } catch {
    ElMessage.error(t('session.copyFail'))
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void onSend()
  }
}

function findPreviousUserMessage(fromIndex: number): ChatMessage | null {
  for (let i = fromIndex - 1; i >= 0; i--) {
    if (chat.messages[i]!.role === 'USER') return chat.messages[i]!
  }
  return null
}

function runStream(
  userText: string,
  restartFromUserMessageId: string | undefined,
  afterDone?: () => void,
) {
  const sid = chat.activeSessionId!
  let assistantId = ''
  let assistantContent = ''

  const assistantPlaceholder: ChatMessage = {
    id: `local-ai-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    createdAt: new Date().toISOString(),
  }
  chat.messages = [...chat.messages, assistantPlaceholder]

  return chat
    .sendStream(sid, userText, {
      restartFromUserMessageId: restartFromUserMessageId ?? undefined,
      onStart(id) {
        assistantId = id
        assistantContent = ''
        chat.messages = chat.messages.map((m) =>
          m.id === assistantPlaceholder.id ? { ...m, id } : m,
        )
      },
      onDelta(chunk) {
        assistantContent += chunk
        chat.messages = chat.messages.map((m) =>
          m.id === assistantId ? { ...m, content: assistantContent } : m,
        )
        void scrollToBottom(true)
      },
      async onDone() {
        await chat.fetchMessages(sid)
        await chat.fetchSessions()
        syncUserEditsFromMessages()
        await scrollToBottom(true)
        afterDone?.()
      },
    })
    .catch((e: unknown) => {
      if ((e as Error).name === 'AbortError') {
        ElMessage.info(t('errors.stopped'))
      } else {
        ElMessage.error((e as Error).message || t('errors.send'))
      }
      return chat.fetchMessages(sid)
    })
}

async function onSend() {
  const sid = chat.activeSessionId
  const text = chat.inputDraft.trim()
  if (!sid || !text || chat.sending) return

  chat.inputDraft = ''

  const userMsg: ChatMessage = {
    id: `local-user-${Date.now()}`,
    role: 'USER',
    content: text,
    createdAt: new Date().toISOString(),
  }
  chat.messages = [...chat.messages, userMsg]
  userEdits[userMsg.id] = text
  editingUserId.value = null
  await scrollToBottom(false)

  await runStream(text, undefined)
}

function startUserEdit(m: ChatMessage) {
  userEdits[m.id] = m.content
  editingUserId.value = m.id
}

function cancelUserEdit(m: ChatMessage) {
  if (editingUserId.value === m.id) {
    editingUserId.value = null
  }
  userEdits[m.id] = m.content
}

async function resendUserMessage(userMsgId: string) {
  const sid = chat.activeSessionId
  if (!sid || chat.sending) return
  const idx = chat.messages.findIndex((m) => m.id === userMsgId)
  if (idx < 0) return
  const text = (userEdits[userMsgId] ?? '').trim()
  if (!text) {
    ElMessage.warning(t('chat.emptySend'))
    return
  }
  editingUserId.value = null
  chat.messages = chat.messages.slice(0, idx + 1)
  await runStream(text, userMsgId)
}

async function regenerateAssistant(assistantIndex: number) {
  const sid = chat.activeSessionId
  if (!sid || chat.sending) return
  const prevUser = findPreviousUserMessage(assistantIndex)
  if (!prevUser) {
    ElMessage.warning(t('chat.noUserBefore'))
    return
  }
  const text = (userEdits[prevUser.id] ?? prevUser.content).trim()
  chat.messages = chat.messages.slice(0, assistantIndex)
  await runStream(text, prevUser.id)
}

function setFeedback(msgId: string, v: 'up' | 'down') {
  feedback[msgId] = feedback[msgId] === v ? null : v
}

function speakAssistant(text: string) {
  const plain = markdownToPlainText(text)
  if (!plain) return
  window.speechSynthesis.cancel()
  const u = new SpeechSynthesisUtterance(plain)
  const loc = navigator.language || 'zh-CN'
  u.lang = loc.startsWith('zh') ? 'zh-CN' : 'en-US'
  window.speechSynthesis.speak(u)
}

function openCanvasPage(msg: ChatMessage) {
  void router.push({ name: 'canvas', query: { messageId: msg.id } })
}
</script>

<template>
  <main class="main" :class="{ 'main--embedded': props.hideThreadHead }">
    <header v-if="chat.activeSessionId && !props.hideThreadHead" class="thread-head">
      <div class="thread-head-inner">
        <h1 class="thread-title">{{ chat.activeSession?.title || t('session.defaultTitle') }}</h1>
        <p class="thread-sub">{{ t('app.tagline') }}</p>
      </div>
    </header>

    <div class="msg-scroll">
      <div v-if="!chat.activeSessionId" class="empty">
        <h2>{{ t('chat.emptyTitle') }}</h2>
        <p>{{ t('chat.emptyHint') }}</p>
      </div>
      <div v-else-if="chat.loadingMessages" class="muted center">{{ t('chat.loadingMessages') }}</div>
      <div v-else class="msg-inner">
        <div
          v-for="(m, idx) in chat.messages"
          :key="m.id"
          class="row"
          :class="m.role === 'USER' ? 'end' : 'start'"
          @mouseenter="onRowHover(m.id, true)"
          @mouseleave="onRowHover(m.id, false)"
        >
          <div class="msg-animate bubble-wrap" :class="m.role === 'USER' ? 'user' : 'ai'">
            <div v-if="m.role === 'ASSISTANT'" class="ai-label">{{ t('chat.assistant') }}</div>

            <div v-if="m.role === 'USER'" class="user-block">
              <template v-if="editingUserId === m.id">
                <el-input
                  v-model="userEdits[m.id]"
                  type="textarea"
                  :autosize="{ minRows: 1, maxRows: 12 }"
                  resize="none"
                  class="user-input"
                  :disabled="chat.sending"
                />
              </template>
              <div v-else class="user-read">
                {{ userEdits[m.id] ?? m.content }}
              </div>
              <div v-if="showUserToolbar(idx, m)" class="user-actions">
                <template v-if="editingUserId === m.id">
                  <el-button text size="small" @click="cancelUserEdit(m)">
                    {{ t('chat.cancelEdit') }}
                  </el-button>
                  <el-button text size="small" @click="copyText(userEdits[m.id] || '')">
                    <el-icon><DocumentCopy /></el-icon>
                    {{ t('chat.copy') }}
                  </el-button>
                  <el-button text size="small" type="primary" :disabled="chat.sending" @click="resendUserMessage(m.id)">
                    <el-icon><Promotion /></el-icon>
                    {{ t('chat.resend') }}
                  </el-button>
                </template>
                <template v-else>
                  <el-button text size="small" :disabled="chat.sending" @click="startUserEdit(m)">
                    <el-icon><Edit /></el-icon>
                    {{ t('chat.edit') }}
                  </el-button>
                  <el-button text size="small" @click="copyText(userEdits[m.id] || '')">
                    <el-icon><DocumentCopy /></el-icon>
                    {{ t('chat.copy') }}
                  </el-button>
                  <el-button text size="small" type="primary" :disabled="chat.sending" @click="resendUserMessage(m.id)">
                    <el-icon><Promotion /></el-icon>
                    {{ t('chat.resend') }}
                  </el-button>
                </template>
              </div>
            </div>

            <div v-else class="ai-content">
              <div v-if="isAssistantStreaming(m)" class="typing">
                <el-icon class="spin"><Loading /></el-icon>
                <span class="dot" /><span class="dot" /><span class="dot" />
              </div>
              <div v-else class="prose-ai" v-html="md(m.content)" />
            </div>

            <div v-if="showAiToolbar(idx, m)" class="ai-toolbar">
              <el-button text size="small" @click="copyText(m.content)">
                <el-icon><DocumentCopy /></el-icon>
                {{ t('chat.copy') }}
              </el-button>
              <el-button text size="small" :disabled="chat.sending" @click="regenerateAssistant(idx)">
                <el-icon><RefreshRight /></el-icon>
                {{ t('chat.regenerate') }}
              </el-button>
              <el-button
                text
                size="small"
                :type="feedback[m.id] === 'up' ? 'primary' : 'default'"
                @click="setFeedback(m.id, 'up')"
              >
                <el-icon><Top /></el-icon>
              </el-button>
              <el-button
                text
                size="small"
                :type="feedback[m.id] === 'down' ? 'danger' : 'default'"
                @click="setFeedback(m.id, 'down')"
              >
                <el-icon><Bottom /></el-icon>
              </el-button>
              <el-button text size="small" @click="speakAssistant(m.content)">
                <el-icon><Microphone /></el-icon>
                {{ t('chat.speak') }}
              </el-button>
              <el-button text size="small" @click="openCanvasPage(m)">
                <el-icon><EditPen /></el-icon>
                {{ t('chat.toCanvas') }}
              </el-button>
            </div>

            <div class="meta">
              <time>{{ new Date(m.createdAt).toLocaleString() }}</time>
            </div>
          </div>
        </div>
        <div ref="bottomAnchor" class="anchor" />
      </div>
    </div>

    <footer class="composer">
      <div class="composer-inner">
        <el-input
          v-model="chat.inputDraft"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 8 }"
          resize="none"
          class="composer-input"
          :placeholder="t('chat.inputPlaceholder')"
          :disabled="!chat.activeSessionId"
          @keydown="onKeydown"
        />
        <el-tooltip :content="chat.sending ? t('chat.stop') : t('chat.send')" placement="top">
          <el-button
            class="send-fab"
            type="primary"
            circle
            :disabled="!chat.activeSessionId || (!chat.sending && !chat.inputDraft.trim())"
            @click="chat.sending ? chat.stopStream() : onSend()"
          >
            <el-icon v-if="chat.sending" class="fab-icon"><CircleClose /></el-icon>
            <el-icon v-else class="fab-icon"><Promotion /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </footer>
  </main>
</template>

<style scoped>
.main {
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-width: 0;
  min-height: 0;
  background: var(--bg-chat-panel);
  transition: background 0.35s ease;
}

.main.main--embedded {
  grid-template-rows: 1fr auto;
}

.thread-head {
  flex-shrink: 0;
  padding: 16px 20px 10px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-chat-panel);
}

.thread-head-inner {
  max-width: 880px;
  margin: 0 auto;
  text-align: center;
}

.thread-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}

.thread-sub {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

.msg-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: smooth;
  padding: 20px 16px 12px;
  min-height: 0;
}

.empty {
  height: 100%;
  display: grid;
  place-content: center;
  text-align: center;
  gap: 10px;
  color: var(--text-secondary);
  padding: 24px;
}
.empty h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
}

.muted.center {
  text-align: center;
  padding: 40px;
  color: var(--text-secondary);
}

.msg-inner {
  max-width: 880px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.row {
  display: flex;
}
.row.start {
  justify-content: flex-start;
}
.row.end {
  justify-content: flex-end;
}

.bubble-wrap {
  max-width: min(720px, 92%);
}

.ai-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.user-block {
  width: 100%;
}

.user-read {
  border-radius: 12px;
  background: var(--bg-input-fill);
  color: var(--text-primary);
  border: 1px solid var(--border-subtle);
  padding: 10px 12px;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.user-input :deep(.el-textarea__inner) {
  border-radius: 12px;
  background: var(--bg-input-fill) !important;
  color: var(--text-primary);
  border: 1px solid var(--border-subtle);
  box-shadow: none;
  font-size: 14px;
  line-height: 1.55;
}
.user-input :deep(.el-textarea__inner::placeholder) {
  color: var(--text-muted);
}
.user-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
  justify-content: flex-end;
}
.user-actions :deep(.el-button) {
  color: var(--text-secondary);
}
.user-actions :deep(.el-button--primary) {
  color: var(--accent);
}

.ai-content {
  width: 100%;
  padding: 4px 0 0;
  line-height: 1.6;
  font-size: 14px;
  color: var(--text-primary);
}

.ai-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 6px;
  margin-top: 8px;
  align-items: center;
}
.ai-toolbar :deep(.el-button) {
  font-size: 12px;
  padding: 4px 6px;
}

.meta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
}

.typing {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 22px;
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.typing .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
  animation: bounce 1.1s infinite ease-in-out;
}
.typing .dot:nth-child(2) {
  animation-delay: 0.12s;
}
.typing .dot:nth-child(3) {
  animation-delay: 0.24s;
}
.typing .dot:nth-child(4) {
  animation-delay: 0.36s;
}
@keyframes bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.5;
  }
  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

.anchor {
  height: 1px;
}

.composer {
  border-top: 1px solid var(--border-subtle);
  background: var(--bg-chat-panel);
  padding: 12px 16px 14px;
  box-shadow: 0 -4px 24px rgba(15, 23, 42, 0.06);
  transition:
    background 0.35s ease,
    border-color 0.35s ease;
}

.composer-inner {
  position: relative;
  max-width: 880px;
  margin: 0 auto;
}

.composer-input :deep(.el-textarea__inner) {
  border-radius: var(--radius-lg, 14px);
  padding: 12px 52px 12px 14px;
  min-height: 88px !important;
  background: #ffffff !important;
  border: 1px solid var(--border-subtle);
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

.send-fab {
  position: absolute;
  right: 10px;
  bottom: 10px;
  width: 40px;
  height: 40px;
  box-shadow: var(--shadow-md);
}
.fab-icon {
  font-size: 18px;
}
</style>
