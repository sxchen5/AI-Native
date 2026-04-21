<script setup lang="ts">
import {
  ArrowDown,
  CircleClose,
  Close,
  DocumentCopy,
  Download,
  Edit,
  EditPen,
  Loading,
  Microphone,
  Paperclip,
  Promotion,
  TopRight,
} from '@element-plus/icons-vue'
import { FullScreen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import * as chatApi from '../api/chatApi'
import type { ChatMessage, DocumentCardMeta } from '../api/types'
import { useChatStore } from '../stores/chat'
import { renderAiMarkdown } from '../utils/markdown'
import { markdownToPlainText } from '../utils/plainText'
import IconThumbDown from './icons/IconThumbDown.vue'
import IconThumbUp from './icons/IconThumbUp.vue'
import { parseDocumentMeta } from '../utils/documentMeta'

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
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadBusy = ref(false)
const followUpQuestions = ref<string[]>([])
const docConvertBusyId = ref<string | null>(null)

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
    followUpQuestions.value = []
    void scrollToBottom(false)
    void nextTick(() => updateScrollBottomState())
  },
)

watch(
  () => chat.loadingMessages,
  (v) => {
    if (!v) {
      void nextTick(() => updateScrollBottomState())
      void loadFollowUps()
    }
  },
)

watch(
  () => chat.sending,
  (v, prev) => {
    if (prev && !v) void loadFollowUps()
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
  appendAfterUserMessageId: string | undefined,
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
    metadata: null,
  }
  chat.messages = [...chat.messages, assistantPlaceholder]

  return chat
    .sendStream(sid, userText, {
      restartFromUserMessageId: restartFromUserMessageId ?? undefined,
      appendAfterUserMessageId: appendAfterUserMessageId ?? undefined,
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
    metadata: null,
  }
  chat.messages = [...chat.messages, userMsg]
  userEdits[userMsg.id] = text
  editingUserId.value = null
  await scrollToBottom(false)

  await runStream(text, undefined, undefined)
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
  const text = (userEdits[userMsgId] ?? '').trim()
  if (!text) {
    ElMessage.warning(t('chat.emptySend'))
    return
  }
  editingUserId.value = null
  await runStream(text, undefined, userMsgId)
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

function openCanvasEdit(msg: ChatMessage) {
  void router.push({ name: 'canvas', query: { messageId: msg.id } })
}

function openCanvasReadOnly(msg: ChatMessage) {
  void router.push({ name: 'canvas', query: { messageId: msg.id, readOnly: '1' } })
}

async function onConvertToDocument(msg: ChatMessage) {
  const sid = chat.activeSessionId
  if (!sid || chat.sending || docConvertBusyId.value) return
  docConvertBusyId.value = msg.id
  try {
    await chatApi.convertToDocument(sid, msg.id)
    await chat.fetchMessages(sid)
    await chat.fetchSessions()
    ElMessage.success(t('chat.docConverted'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('chat.docConvertFail'))
  } finally {
    docConvertBusyId.value = null
  }
}

function docMeta(m: ChatMessage): DocumentCardMeta | null {
  return parseDocumentMeta(m.metadata ?? undefined)
}

async function loadFollowUps() {
  const sid = chat.activeSessionId
  if (!sid || chat.sending || props.hideThreadHead) {
    followUpQuestions.value = []
    return
  }
  try {
    followUpQuestions.value = await chatApi.fetchFollowUpQuestions(sid)
  } catch {
    followUpQuestions.value = []
  }
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

function openFilePicker() {
  fileInputRef.value?.click()
}

async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning(t('errors.fileTooLarge'))
    return
  }
  uploadBusy.value = true
  try {
    const { fileName, text } = await chatApi.extractAttachmentText(file)
    const prefix = t('chat.filePrefix', { name: fileName })
    const cur = chat.inputDraft
    chat.inputDraft = cur ? `${cur.trimEnd()}\n\n${prefix}${text}` : `${prefix}${text}`
    ElMessage.success(t('chat.uploadOk'))
  } catch (err) {
    ElMessage.error((err as Error).message || t('chat.uploadFail'))
  } finally {
    uploadBusy.value = false
  }
}

function goFullChat() {
  void router.push({ name: 'chat' })
}

function docBodyPreview(body: string) {
  const plain = markdownToPlainText(body || '')
  if (plain.length <= 280) return plain
  return `${plain.slice(0, 277)}…`
}

function downloadDocMarkdown(meta: DocumentCardMeta) {
  const blob = new Blob([meta.markdownBody], { type: 'text/markdown;charset=utf-8' })
  const a = document.createElement('a')
  const safe = (meta.title || 'document').replace(/[/\\?%*:|"<>]/g, '-').slice(0, 80)
  a.href = URL.createObjectURL(blob)
  a.download = `${safe}.md`
  a.click()
  URL.revokeObjectURL(a.href)
}

function askFollowUp(q: string) {
  chat.inputDraft = q
  void onSend()
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
                    <el-button text circle class="msg-toolbar-btn" @click="cancelUserEdit(m)">
                      <el-icon><Close /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip :content="t('chat.copy')" placement="top">
                    <el-button text circle class="msg-toolbar-btn" @click="copyText(userEdits[m.id] || '')">
                      <el-icon><DocumentCopy /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip :content="t('chat.resend')" placement="top">
                    <el-button
                      text
                      circle
                      class="msg-toolbar-btn"
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
                    <el-button text circle class="msg-toolbar-btn" @click="copyText(userEdits[m.id] || '')">
                      <el-icon><DocumentCopy /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip :content="t('chat.edit')" placement="top">
                    <el-button text circle class="msg-toolbar-btn" :disabled="chat.sending" @click="startUserEdit(m)">
                      <el-icon><Edit /></el-icon>
                    </el-button>
                  </el-tooltip>
                </template>
              </div>
            </div>

            <div v-else-if="docMeta(m)" class="doc-card-wrap">
              <button type="button" class="doc-card" @click="docMeta(m)?.frozen ? openCanvasReadOnly(m) : openCanvasEdit(m)">
                <div class="doc-card-grid">
                  <div class="doc-card-left">
                    <div class="doc-card-icon" aria-hidden="true">
                      <el-icon :size="22"><EditPen /></el-icon>
                    </div>
                    <div class="doc-card-title">{{ docMeta(m)!.title }}</div>
                    <div class="doc-card-time">{{ t('chat.docCreated') }} {{ new Date(m.createdAt).toLocaleString() }}</div>
                  </div>
                  <div class="doc-card-preview">
                    <div class="doc-preview-label">{{ t('chat.docPreviewLabel') }}</div>
                    <div class="doc-preview-body">{{ docBodyPreview(docMeta(m)!.markdownBody) }}</div>
                  </div>
                </div>
              </button>
            </div>

            <div v-else class="ai-content">
              <div v-if="isAssistantStreaming(m)" class="typing">
                <el-icon class="spin"><Loading /></el-icon>
                <span class="dot" /><span class="dot" /><span class="dot" />
              </div>
              <div v-else class="prose-ai" v-html="md(m.content)" />
            </div>

            <div
              v-if="m.role === 'ASSISTANT' && docMeta(m)"
              class="ai-toolbar-slot"
            >
              <div class="ai-toolbar" :class="{ 'ai-toolbar--visible': showAiToolbar(idx, m) }">
                <el-tooltip :content="t('chat.copy')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="copyText(docMeta(m)!.markdownBody)">
                    <el-icon><DocumentCopy /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.downloadDoc')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="downloadDocMarkdown(docMeta(m)!)">
                    <el-icon><Download /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="docMeta(m)!.frozen ? t('chat.openDocView') : t('chat.openDocEdit')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="docMeta(m)!.frozen ? openCanvasReadOnly(m) : openCanvasEdit(m)">
                    <el-icon><TopRight /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.speak')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="speakAssistant(docMeta(m)!.markdownBody)">
                    <el-icon><Microphone /></el-icon>
                  </el-button>
                </el-tooltip>
              </div>
            </div>

            <div
              v-else-if="m.role === 'ASSISTANT'"
              class="ai-toolbar-slot"
              :class="{ 'ai-toolbar-slot--streaming': isAssistantStreaming(m) }"
            >
              <div class="ai-toolbar" :class="{ 'ai-toolbar--visible': showAiToolbar(idx, m) }">
                <el-tooltip :content="t('chat.copy')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="copyText(m.content)">
                    <el-icon><DocumentCopy /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.like')" placement="top">
                  <el-button
                    text
                    circle
                    class="msg-toolbar-btn"
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
                    class="msg-toolbar-btn"
                    :type="feedback[m.id] === 'down' ? 'danger' : 'default'"
                    @click="setFeedback(m.id, 'down')"
                  >
                    <span class="thumb-wrap" :class="{ muted: feedback[m.id] !== 'down' }">
                      <IconThumbDown />
                    </span>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.speak')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="speakAssistant(m.content)">
                    <el-icon><Microphone /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('chat.toCanvas')" placement="top">
                  <el-button
                    text
                    circle
                    class="msg-toolbar-btn"
                    :loading="docConvertBusyId === m.id"
                    :disabled="!!docConvertBusyId || chat.sending"
                    @click="onConvertToDocument(m)"
                  >
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

    <div
      v-if="
        followUpQuestions.length > 0 &&
        chat.activeSessionId &&
        !chat.loadingMessages &&
        !showLanding &&
        !props.hideThreadHead
      "
      class="follow-up-bar"
    >
      <div class="follow-up-inner">
        <span class="follow-up-title">{{ t('chat.followUpTitle') }}</span>
        <div class="follow-up-chips">
          <button
            v-for="(q, i) in followUpQuestions"
            :key="i"
            type="button"
            class="follow-chip"
            :disabled="chat.sending"
            @click="askFollowUp(q)"
          >
            <span class="follow-chip-text">{{ q }}</span>
            <span class="follow-chip-arrow" aria-hidden="true">→</span>
          </button>
        </div>
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
      <div class="composer-inner">
        <input
          ref="fileInputRef"
          type="file"
          class="file-input"
          accept=".pdf,.doc,.docx,.txt,.md,.csv,.json,.xml,.html,.htm,.log,.yml,.yaml"
          @change="onFileSelected"
        />
        <el-input
          v-model="chat.inputDraft"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 8 }"
          resize="none"
          class="composer-input"
          :placeholder="t('chat.inputPlaceholder')"
          :disabled="!chat.activeSessionId || uploadBusy"
          @keydown="onKeydown"
        />
        <el-tooltip :content="t('chat.upload')" placement="top">
          <el-button
            class="attach-fab"
            circle
            :disabled="!chat.activeSessionId || chat.sending || uploadBusy"
            :loading="uploadBusy"
            @click="openFilePicker"
          >
            <el-icon class="fab-icon"><Paperclip /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip :content="chat.sending ? t('chat.stop') : t('chat.send')" placement="top">
          <el-button
            class="send-fab"
            type="primary"
            circle
            :disabled="!chat.activeSessionId || (!chat.sending && !chat.inputDraft.trim()) || uploadBusy"
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
  display: flex;
  flex-direction: column;
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
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: smooth;
  padding: 20px 16px 12px;
  background: var(--bg-chat-surface);
}

.follow-up-bar {
  flex-shrink: 0;
  border-top: 1px solid var(--border-subtle);
  background: var(--bg-chat-surface);
  padding: 10px 16px 12px;
}

.follow-up-inner {
  max-width: 880px;
  margin: 0 auto;
}

.follow-up-title {
  display: block;
  font-size: 12px;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.follow-up-chips {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.follow-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--border-subtle);
  background: var(--bg-input-fill);
  color: var(--text-primary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.follow-chip:hover:not(:disabled) {
  border-color: var(--accent-soft);
  box-shadow: 0 4px 14px rgba(59, 108, 255, 0.1);
}

.follow-chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.follow-chip-text {
  flex: 1;
  min-width: 0;
  line-height: 1.45;
}

.follow-chip-arrow {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 16px;
}

.doc-card-wrap {
  width: 100%;
  max-width: min(640px, 100%);
}

.doc-card {
  width: 100%;
  border: 1px solid var(--border-subtle);
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(59, 108, 255, 0.04) 0%, var(--bg-elevated) 40%);
  padding: 0;
  cursor: pointer;
  text-align: left;
  font: inherit;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.doc-card:hover {
  border-color: var(--accent-soft);
  box-shadow: var(--shadow-md);
}

.doc-card-grid {
  display: grid;
  grid-template-columns: 1fr minmax(120px, 38%);
  gap: 0;
  min-height: 120px;
}

.doc-card-left {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.doc-card-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--accent-soft);
  color: var(--accent);
  display: grid;
  place-items: center;
}

.doc-card-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.doc-card-time {
  font-size: 11px;
  color: var(--text-muted);
}

.doc-card-preview {
  border-left: 1px solid var(--border-subtle);
  padding: 12px 12px 12px 14px;
  background: var(--bg-elevated);
  border-radius: 0 14px 14px 0;
  min-width: 0;
}

.doc-preview-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.doc-preview-body {
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-secondary);
  max-height: 88px;
  overflow: hidden;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 640px) {
  .doc-card-grid {
    grid-template-columns: 1fr;
  }
  .doc-card-preview {
    border-left: none;
    border-top: 1px solid var(--border-subtle);
    border-radius: 0 0 14px 14px;
  }
}

.jump-wrap {
  display: flex;
  justify-content: center;
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
.thumb-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  color: currentColor;
}
.thumb-wrap.muted {
  opacity: 0.42;
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

.file-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}

.composer-input :deep(.el-textarea__inner) {
  border-radius: var(--radius-lg, 14px);
  padding: 12px 100px 12px 14px;
  min-height: 88px !important;
  background: var(--bg-elevated) !important;
  color: var(--text-primary);
  border: 1px solid var(--border-subtle);
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

.attach-fab {
  position: absolute;
  right: 54px;
  bottom: 10px;
  width: 40px;
  height: 40px;
  border: 1px solid var(--border-subtle) !important;
  background: var(--bg-elevated) !important;
  color: var(--text-secondary) !important;
  box-shadow: var(--shadow-sm);
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

.msg-toolbar-btn {
  width: 36px !important;
  height: 36px !important;
  min-height: 36px !important;
  padding: 0 !important;
  margin: 0 2px !important;
  border: 1px solid var(--border-subtle) !important;
  background: var(--bg-elevated) !important;
  color: var(--text-secondary) !important;
  box-shadow: none;
}
.msg-toolbar-btn:hover {
  border-color: var(--accent-soft) !important;
  color: var(--text-primary) !important;
}
.msg-toolbar-btn.is-text.el-button--primary {
  border-color: var(--accent-soft) !important;
  color: var(--accent) !important;
  background: var(--bg-elevated) !important;
}
.msg-toolbar-btn.is-text.el-button--danger {
  border-color: rgba(239, 68, 68, 0.25) !important;
  color: #ef4444 !important;
  background: var(--bg-elevated) !important;
}
.msg-toolbar-btn :deep(.el-icon) {
  font-size: 18px;
}
</style>
