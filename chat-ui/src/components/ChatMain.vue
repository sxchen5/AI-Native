<script setup lang="ts">
import {
  CircleClose,
  DocumentCopy,
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

import type { ChatMessage } from '../api/types'
import { useChatStore } from '../stores/chat'
import { renderAiMarkdown } from '../utils/markdown'
import { markdownToPlainText } from '../utils/plainText'

const { t } = useI18n()
const chat = useChatStore()

const bottomAnchor = ref<HTMLElement | null>(null)
/** 用户消息可编辑副本（与列表 id 对齐） */
const userEdits = reactive<Record<string, string>>({})
/** 对 AI 消息的点赞/点踩 */
const feedback = reactive<Record<string, 'up' | 'down' | null>>({})

const canvasOpen = ref(false)
const canvasText = ref('')
const canvasMessageId = ref<string | null>(null)

function md(html: string) {
  return renderAiMarkdown(html)
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
  () => scrollToBottom(false),
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
  await scrollToBottom(false)

  await runStream(text, undefined)
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
  // 去掉当前 AI 及之后消息；后端会截断并重新生成
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

function openCanvas(msg: ChatMessage) {
  canvasMessageId.value = msg.id
  canvasText.value = msg.content
  canvasOpen.value = true
}

function applyCanvas() {
  if (!canvasMessageId.value) return
  const id = canvasMessageId.value
  chat.messages = chat.messages.map((m) =>
    m.id === id ? { ...m, content: canvasText.value } : m,
  )
  canvasOpen.value = false
  ElMessage.success(t('chat.canvasApplied'))
}
</script>

<template>
  <main class="main">
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
        >
          <div class="msg-animate bubble-wrap" :class="m.role === 'USER' ? 'user' : 'ai'">
            <div v-if="m.role === 'ASSISTANT'" class="ai-label">{{ t('chat.assistant') }}</div>

            <div v-if="m.role === 'USER'" class="user-block">
              <el-input
                v-model="userEdits[m.id]"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 12 }"
                resize="none"
                class="user-input"
                :disabled="chat.sending"
              />
              <div class="user-actions">
                <el-button text size="small" @click="copyText(userEdits[m.id] || '')">
                  <el-icon><DocumentCopy /></el-icon>
                  {{ t('chat.copy') }}
                </el-button>
                <el-button text size="small" type="primary" :disabled="chat.sending" @click="resendUserMessage(m.id)">
                  <el-icon><Promotion /></el-icon>
                  {{ t('chat.resend') }}
                </el-button>
              </div>
            </div>

            <div v-else class="ai-content">
              <div v-if="m.content.length === 0 && chat.sending" class="typing">
                <el-icon class="spin"><Loading /></el-icon>
                <span class="dot" /><span class="dot" /><span class="dot" />
              </div>
              <div v-else class="prose-ai" v-html="md(m.content)" />
            </div>

            <div v-if="m.role === 'ASSISTANT'" class="ai-toolbar">
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
              <el-button text size="small" @click="openCanvas(m)">
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

    <el-dialog v-model="canvasOpen" :title="t('chat.canvasTitle')" width="min(720px, 92vw)" destroy-on-close>
      <el-input v-model="canvasText" type="textarea" :rows="16" :placeholder="t('chat.canvasHint')" />
      <template #footer>
        <el-button @click="canvasOpen = false">{{ t('session.cancel') }}</el-button>
        <el-button type="primary" @click="applyCanvas">{{ t('chat.canvasApply') }}</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.main {
  display: grid;
  grid-template-rows: 1fr auto;
  min-width: 0;
  min-height: 0;
  background: var(--bg-chat-panel);
  transition: background 0.35s ease;
}

.msg-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: smooth;
  padding: 20px 16px 12px;
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
  background: var(--bg-input-fill) !important;
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
