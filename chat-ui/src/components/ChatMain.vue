<script setup lang="ts">
import {
  ArrowDown,
  CircleClose,
  Close,
  DocumentCopy,
  Edit,
  EditPen,
  Loading,
  Microphone,
  Promotion,
} from '@element-plus/icons-vue'
import { FullScreen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import type { ChatMessage } from '../api/types'
import { useChatStore } from '../stores/chat'
import { renderAiMarkdown } from '../utils/markdown'
import { markdownToPlainText } from '../utils/plainText'
import IconThumbDown from './icons/IconThumbDown.vue'
import IconThumbUp from './icons/IconThumbUp.vue'

const { t, tm } = useI18n()
const router = useRouter()
const chat = useChatStore()

const props = defineProps<{
  /** 内嵌在画布分屏左侧时隐藏会话标题区 */
  hideThreadHead?: boolean
}>()

const bottomAnchor = ref<HTMLElement | null>(null)
const msgScrollEl = ref<HTMLElement | null>(null)
const showJumpToBottom = ref(false)
let scrollThumbTimer: ReturnType<typeof setTimeout> | null = null
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
  updateScrollBottomState()
}

function updateScrollBottomState() {
  const el = msgScrollEl.value
  if (!el) {
    showJumpToBottom.value = false
    return
  }
  const gap = 80
  showJumpToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight > gap
}

function onMsgScroll() {
  updateScrollBottomState()
  const el = msgScrollEl.value
  if (!el) return
  el.classList.add('u-scroll--active')
  if (scrollThumbTimer) clearTimeout(scrollThumbTimer)
  scrollThumbTimer = setTimeout(() => {
    el.classList.remove('u-scroll--active')
    scrollThumbTimer = null
  }, 900)
}

function jumpToLatest() {
  void scrollToBottom(true)
}

watch(
  () => chat.messages,
  () => {
    syncUserEditsFromMessages()
    void scrollToBottom(true)
    void nextTick(() => updateScrollBottomState())
  },
  { deep: true, immediate: true },
)

watch(
  () => chat.activeSessionId,
  () => {
    editingUserId.value = null
    void scrollToBottom(false)
    void nextTick(() => updateScrollBottomState())
  },
)

watch(
  () => chat.loadingMessages,
  (v) => {
    if (!v) void nextTick(() => updateScrollBottomState())
  },
)

onMounted(() => {
  window.addEventListener('resize', updateScrollBottomState, { passive: true })
  void nextTick(() => updateScrollBottomState())
})

onBeforeUnmount(() => {
  if (scrollThumbTimer) clearTimeout(scrollThumbTimer)
  msgScrollEl.value?.classList.remove('u-scroll--active')
  window.removeEventListener('resize', updateScrollBottomState)
})

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

const suggestionChips = computed(() => {
  const raw = tm('chat.suggestions') as unknown
  return Array.isArray(raw) ? (raw as string[]) : []
})

const showLanding = computed(
  () =>
    chat.activeSessionId &&
    !chat.loadingMessages &&
    !chat.sending &&
    chat.messages.length === 0 &&
    !props.hideThreadHead,
)

function fillSuggestion(text: string) {
  chat.inputDraft = text
}

function goFullChat() {
  void router.push({ name: 'chat' })
}
</script>

<template>
  <main class="main" :class="{ 'main--embedded': props.hideThreadHead }">
    <div v-if="props.hideThreadHead && chat.activeSessionId" class="embed-bar">
      <div class="embed-bar-center">
        <span class="embed-title">{{ chat.activeSession?.title || t('session.defaultTitle') }}</span>
        <span class="embed-sub">{{ t('chat.landDisclaimer') }}</span>
      </div>
      <el-tooltip :content="t('chat.fullChatView')" placement="bottom">
        <el-button text circle size="small" @click="goFullChat">
          <el-icon><FullScreen /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <header v-if="chat.activeSessionId && !props.hideThreadHead" class="thread-head">
      <div class="thread-head-inner">
        <h1 class="thread-title">{{ chat.activeSession?.title || t('session.defaultTitle') }}</h1>
        <p class="thread-sub">{{ t('app.tagline') }}</p>
      </div>
    </header>

    <div ref="msgScrollEl" class="msg-scroll u-scroll" @scroll.passive="onMsgScroll">
      <div v-if="!chat.activeSessionId" class="empty">
        <h2>{{ t('chat.emptyTitle') }}</h2>
        <p>{{ t('chat.emptyHint') }}</p>
      </div>
      <div v-else-if="chat.loadingMessages" class="muted center">{{ t('chat.loadingMessages') }}</div>
      <div v-else-if="showLanding" class="landing">
        <h2 class="land-greeting">{{ t('chat.landGreeting') }}</h2>
        <p class="land-disclaimer">{{ t('chat.landDisclaimer') }}</p>
        <div class="suggestion-grid">
          <button
            v-for="(chip, i) in suggestionChips"
            :key="i"
            type="button"
            class="suggestion-chip"
            @click="fillSuggestion(chip)"
          >
            {{ chip }}
          </button>
        </div>
      </div>
      <div v-else-if="props.hideThreadHead && chat.messages.length === 0" class="embed-empty muted">
        {{ t('chat.embedEmptyHint') }}
      </div>
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
              <div class="user-actions" :class="{ 'user-actions--visible': showUserToolbar(idx, m) }">
                <template v-if="editingUserId === m.id">
                  <el-tooltip :content="t('chat.cancelEdit')" placement="top">
                    <el-button text circle class="icon-act" @click="cancelUserEdit(m)">
                      <el-icon><Close /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip :content="t('chat.copy')" placement="top">
                    <el-button text circle class="icon-act" @click="copyText(userEdits[m.id] || '')">
                      <el-icon><DocumentCopy /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip :content="t('chat.resend')" placement="top">
                    <el-button
                      text
                      circle
                      class="icon-act"
                      type="primary"
                      :disabled="chat.sending"
                      @click="resendUserMessage(m.id)"
                    >
                      <el-icon><Promotion /></el-icon>
                    </el-button>
                  </el-tooltip>
                </template>
                <template v-else>
                  <el-tooltip :content="t('chat.copy')" placement="top">
                    <el-button text circle class="icon-act" @click="copyText(userEdits[m.id] || '')">
                      <el-icon><DocumentCopy /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip :content="t('chat.edit')" placement="top">
                    <el-button text circle class="icon-act" :disabled="chat.sending" @click="startUserEdit(m)">
                      <el-icon><Edit /></el-icon>
                    </el-button>
                  </el-tooltip>
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

            <div
              v-if="m.role === 'ASSISTANT'"
              class="ai-toolbar-slot"
              :class="{ 'ai-toolbar-slot--streaming': isAssistantStreaming(m) }"
            >
              <div class="ai-toolbar" :class="{ 'ai-toolbar--visible': showAiToolbar(idx, m) }">
                <el-tooltip :content="t('chat.copy')" placement="top">
                  <el-button text circle class="icon-act" @click="copyText(m.content)">
                    <el-icon><DocumentCopy /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.like')" placement="top">
                  <el-button
                    text
                    circle
                    class="icon-act"
                    :type="feedback[m.id] === 'up' ? 'primary' : 'default'"
                    @click="setFeedback(m.id, 'up')"
                  >
                    <span class="thumb-wrap" :class="{ muted: feedback[m.id] !== 'up' }">
                      <IconThumbUp />
                    </span>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.dislike')" placement="top">
                  <el-button
                    text
                    circle
                    class="icon-act"
                    :type="feedback[m.id] === 'down' ? 'danger' : 'default'"
                    @click="setFeedback(m.id, 'down')"
                  >
                    <span class="thumb-wrap" :class="{ muted: feedback[m.id] !== 'down' }">
                      <IconThumbDown />
                    </span>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.speak')" placement="top">
                  <el-button text circle class="icon-act" @click="speakAssistant(m.content)">
                    <el-icon><Microphone /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.toCanvas')" placement="top">
                  <el-button text circle class="icon-act" @click="openCanvasPage(m)">
                    <el-icon><EditPen /></el-icon>
                  </el-button>
                </el-tooltip>
              </div>
            </div>
          </div>
        </div>
        <div ref="bottomAnchor" class="anchor" />
      </div>
    </div>

    <footer class="composer">
      <div
        v-if="showJumpToBottom && chat.activeSessionId && chat.messages.length > 0 && !showLanding"
        class="jump-wrap"
      >
        <el-tooltip :content="t('chat.jumpToBottom')" placement="top">
          <el-button class="jump-btn" circle @click="jumpToLatest">
            <el-icon :size="18"><ArrowDown /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
      <div v-if="chat.activeSessionId" class="quick-row">
        <button type="button" class="quick-pill" @click="fillSuggestion(t('chat.quickTone') + '：更专业')">
          {{ t('chat.quickTone') }}
        </button>
        <button type="button" class="quick-pill" @click="fillSuggestion(t('chat.quickLength') + '：精简一半')">
          {{ t('chat.quickLength') }}
        </button>
        <button type="button" class="quick-pill" @click="fillSuggestion(t('chat.quickPolish'))">
          {{ t('chat.quickPolish') }}
        </button>
      </div>
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
  background: var(--bg-chat-surface);
  transition: background 0.35s ease;
}

.embed-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-chat-surface);
  flex-shrink: 0;
}

.embed-bar-center {
  flex: 1;
  min-width: 0;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 8px;
}

.embed-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.embed-sub {
  font-size: 11px;
  color: var(--text-muted);
}

.landing {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 32px 20px 24px;
  gap: 10px;
  min-height: min(52vh, 420px);
}

.land-greeting {
  margin: 0;
  font-size: clamp(22px, 3.2vw, 28px);
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--text-primary);
}

.land-disclaimer {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted);
}

.suggestion-grid {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  max-width: 640px;
}

.suggestion-chip {
  border: 1px solid var(--border-subtle);
  background: var(--bg-input-fill);
  color: var(--text-primary);
  font: inherit;
  font-size: 13px;
  padding: 10px 14px;
  border-radius: 999px;
  cursor: pointer;
  transition:
    background 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.suggestion-chip:hover {
  border-color: var(--accent-soft);
  box-shadow: 0 4px 16px rgba(59, 108, 255, 0.12);
}

.embed-empty {
  flex: 1;
  display: grid;
  place-content: center;
  text-align: center;
  padding: 24px 16px;
  max-width: 320px;
  margin: 0 auto;
  line-height: 1.5;
}

.quick-row {
  max-width: 880px;
  margin: 0 auto;
  padding: 0 16px 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.quick-pill {
  border: 1px solid var(--border-subtle);
  background: var(--bg-elevated);
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: border-color 0.2s ease, color 0.2s ease;
}

.quick-pill:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.thread-head {
  flex-shrink: 0;
  padding: 10px 16px 8px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-chat-surface);
}

.thread-head-inner {
  max-width: 880px;
  margin: 0 auto;
  text-align: center;
}

.thread-title {
  margin: 0;
  font-size: 17px;
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
  background: var(--bg-chat-surface);
}

.jump-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 0 0 8px;
  max-width: 880px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
}
.jump-btn {
  width: 40px;
  height: 40px;
  background: #ffffff !important;
  border: 1px solid var(--border-subtle) !important;
  color: var(--text-primary) !important;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.12);
}
html.dark .jump-btn {
  background: var(--bg-elevated) !important;
  color: var(--text-primary) !important;
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

.user-block {
  width: 100%;
}

.user-read {
  border-radius: 12px;
  background: var(--bg-input-fill);
  color: var(--text-primary);
  border: none;
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
  min-height: 32px;
  align-items: center;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}
.user-actions--visible {
  opacity: 1;
  pointer-events: auto;
}
.user-actions :deep(.el-button) {
  color: var(--text-secondary);
}
.user-actions :deep(.el-button--primary) {
  color: var(--accent);
}
.user-actions .icon-act :deep(.el-icon) {
  font-size: 20px;
}

.ai-content {
  width: 100%;
  padding: 4px 0 0;
  line-height: 1.6;
  font-size: 14px;
  color: var(--text-primary);
}

.ai-toolbar-slot {
  min-height: 40px;
  margin-top: 6px;
}
.ai-toolbar-slot--streaming {
  min-height: 0;
  margin-top: 0;
}

.ai-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  min-height: 36px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
  gap: 0;
}
.ai-toolbar--visible {
  opacity: 1;
  pointer-events: auto;
}
.ai-toolbar :deep(.el-button) {
  font-size: 12px;
  padding: 2px;
  margin: 0 1px;
}

.icon-act {
  width: 38px;
  height: 38px;
  padding: 0;
}
.icon-act :deep(.el-icon) {
  font-size: 20px;
}

.thumb-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  color: currentColor;
}
.thumb-wrap.muted {
  opacity: 0.45;
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
  background: #ffffff;
  padding: 12px 16px 14px;
  transition:
    background 0.35s ease,
    border-color 0.35s ease;
}
html.dark .composer {
  background: var(--bg-chat-surface);
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
  background: var(--bg-elevated) !important;
  color: var(--text-primary);
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
