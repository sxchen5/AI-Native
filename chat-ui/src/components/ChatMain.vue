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
  Picture,
  Promotion,
  TopRight,
} from '@element-plus/icons-vue'
import { FullScreen } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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
import { parseFeedbackVote } from '../utils/messageFeedback'
import type { AttachmentChip } from '../utils/modelContext'
import { parseUserBubbleFromMetadata } from '../utils/modelContext'

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
const hoveringRow = reactive<Record<string, boolean>>({})
const editingUserId = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadBusy = ref(false)
const followUpQuestions = ref<string[]>([])
const docConvertBusyId = ref<string | null>(null)
const pendingAttachments = ref<AttachmentChip[]>([])
const voiceRecording = ref(false)
/** 开始本次语音时输入框已有内容，识别结果追加在其后并随 interim 整体替换 */
let voiceDraftBase = ''
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let speechRec: any = null
const imageInputRef = ref<HTMLInputElement | null>(null)

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
  if (chat.sending && isLastMessage(idx)) return false
  if (editingUserId.value === m.id) return true
  return isLastMessage(idx) || !!hoveringRow[m.id]
}

function showAiToolbar(idx: number, m: ChatMessage) {
  if (m.role !== 'ASSISTANT') return false
  if (isAssistantStreaming(m)) return false
  return isLastMessage(idx) || !!hoveringRow[m.id]
}

function showInlineFollowUps(idx: number, m: ChatMessage) {
  if (props.hideThreadHead) return false
  if (!chat.activeSessionId || chat.loadingMessages || showLanding.value) return false
  if (m.role !== 'ASSISTANT' || docMeta(m)) return false
  if (!isLastMessage(idx) || chat.sending) return false
  return followUpQuestions.value.length > 0
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
  try {
    speechRec?.stop()
  } catch {
    /* ignore */
  }
  speechRec = null
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

function buildModelContextJson(): string | null {
  const atts = pendingAttachments.value.map((a) => ({
    fileName: a.fileName,
    mimeType: a.mimeType,
    extractedText: a.extractedText,
  }))
  const chips = pendingAttachments.value.map((a) => ({
    label: a.label,
    kind: a.kind,
  }))
  const payload: Record<string, unknown> = {}
  if (atts.length) payload.attachments = atts
  if (chips.length) payload.userBubble = { chips }
  if (Object.keys(payload).length === 0) return null
  return JSON.stringify(payload)
}

function buildUserMetadataForSend(): string | null {
  const ctx = buildModelContextJson()
  if (!ctx) return null
  return ctx
}

function userBubbleChips(m: ChatMessage) {
  return parseUserBubbleFromMetadata(m.metadata)?.chips ?? []
}

function removePendingAttachment(i: number) {
  pendingAttachments.value = pendingAttachments.value.filter((_, idx) => idx !== i)
}

function clearPendingContext() {
  pendingAttachments.value = []
}

function toggleVoice() {
  type SpeechRecCtor = new () => { start: () => void; stop: () => void; lang: string; continuous: boolean; interimResults: boolean; onresult: ((ev: unknown) => void) | null; onerror: (() => void) | null; onend: (() => void) | null }
  const w = window as unknown as { SpeechRecognition?: SpeechRecCtor; webkitSpeechRecognition?: SpeechRecCtor }
  const SR = w.SpeechRecognition || w.webkitSpeechRecognition
  if (!SR) {
    ElMessage.warning(t('chat.voiceUnsupported'))
    return
  }
  if (voiceRecording.value) {
    speechRec?.stop()
    return
  }
  voiceDraftBase = chat.inputDraft
  speechRec = new SR()
  speechRec.lang = navigator.language.startsWith('zh') ? 'zh-CN' : 'en-US'
  speechRec.continuous = false
  speechRec.interimResults = true
  speechRec.onresult = (ev: { results: { length: number; [i: number]: { 0: { transcript: string } } } }) => {
    let full = ''
    for (let i = 0; i < ev.results.length; i++) {
      full += ev.results[i]![0]!.transcript
    }
    chat.inputDraft = voiceDraftBase + full
  }
  speechRec.onerror = () => {
    voiceRecording.value = false
    voiceDraftBase = ''
  }
  speechRec.onend = () => {
    voiceRecording.value = false
    speechRec = null
    voiceDraftBase = ''
  }
  voiceRecording.value = true
  speechRec.start()
}

function openImagePicker() {
  imageInputRef.value?.click()
}

async function onImageSelected(e: Event) {
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
    const { fileName, mimeType, text } = await chatApi.extractAttachmentText(file)
    pendingAttachments.value.push({
      label: fileName,
      kind: 'image',
      fileName,
      mimeType,
      extractedText: text,
    })
  } catch (err) {
    ElMessage.error((err as Error).message || t('chat.uploadFail'))
  } finally {
    uploadBusy.value = false
  }
}

function runStream(
  userText: string,
  restartFromUserMessageId: string | undefined,
  appendAfterUserMessageId: string | undefined,
  modelContextJson: string | null | undefined,
  afterDone?: () => void,
) {
  const sid = chat.activeSessionId!
  let assistantId = ''
  /** 已收到的全文（SSE），打字机从该缓冲逐字显示到气泡 */
  let streamBuffer = ''
  let streamShown = 0
  let streamRaf = 0
  let streamTargetMessageId = ''

  const flushStreamVisual = () => {
    if (!assistantId) return
    if (streamShown < streamBuffer.length) {
      streamShown = streamBuffer.length
      chat.messages = chat.messages.map((m) =>
        m.id === assistantId ? { ...m, content: streamBuffer } : m,
      )
    }
  }

  const pumpTypewriter = () => {
    streamRaf = 0
    const tid = assistantId || streamTargetMessageId
    if (!tid) return
    const perFrame = 3
    if (streamShown < streamBuffer.length) {
      streamShown = Math.min(streamBuffer.length, streamShown + perFrame)
      chat.messages = chat.messages.map((m) =>
        m.id === tid ? { ...m, content: streamBuffer.slice(0, streamShown) } : m,
      )
      void scrollToBottom(false)
    }
    if (streamShown < streamBuffer.length) {
      streamRaf = requestAnimationFrame(pumpTypewriter)
    }
  }

  const schedulePump = () => {
    if (streamRaf) return
    streamRaf = requestAnimationFrame(pumpTypewriter)
  }

  const stopTypewriter = () => {
    if (streamRaf) {
      cancelAnimationFrame(streamRaf)
      streamRaf = 0
    }
  }

  const assistantPlaceholder: ChatMessage = {
    id: `local-ai-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    createdAt: new Date().toISOString(),
    metadata: null,
  }
  streamTargetMessageId = assistantPlaceholder.id
  followUpQuestions.value = []
  chat.messages = [...chat.messages, assistantPlaceholder]

  return chat
    .sendStream(sid, userText, {
      restartFromUserMessageId: restartFromUserMessageId ?? undefined,
      appendAfterUserMessageId: appendAfterUserMessageId ?? undefined,
      modelContextJson: modelContextJson ?? undefined,
      onStart(id) {
        assistantId = id
        streamBuffer = ''
        streamShown = 0
        streamTargetMessageId = id
        chat.messages = chat.messages.map((m) =>
          m.id === assistantPlaceholder.id ? { ...m, id } : m,
        )
      },
      onDelta(chunk) {
        streamBuffer += chunk
        schedulePump()
      },
      async onDone() {
        stopTypewriter()
        flushStreamVisual()
        await chat.fetchMessages(sid)
        await chat.fetchSessions()
        syncUserEditsFromMessages()
        await scrollToBottom(true)
        afterDone?.()
      },
    })
    .catch((e: unknown) => {
      stopTypewriter()
      flushStreamVisual()
      if ((e as Error).name === 'AbortError') {
        ElMessage.info(t('errors.stopped'))
      } else {
        ElMessage.error((e as Error).message || t('errors.send'))
      }
      return chat.fetchMessages(sid)
    })
}

/** 发送一轮用户消息（无会话时先建会话）；`displayText` 为气泡展示与发给模型的用户文案 */
async function sendUserTurn(displayText: string) {
  let sid = chat.activeSessionId
  if (!sid) {
    try {
      const s = await chat.createSession()
      sid = s.id
    } catch (e) {
      ElMessage.error((e as Error).message || t('errors.createSession'))
      return
    }
  }
  const text = displayText.trim()
  const hasCtx = pendingAttachments.value.length > 0
  if (chat.sending) return
  if (!text && !hasCtx) return

  const ctxJson = buildModelContextJson()
  const meta = buildUserMetadataForSend()
  chat.inputDraft = ''
  clearPendingContext()

  const userMsg: ChatMessage = {
    id: `local-user-${Date.now()}`,
    role: 'USER',
    content: text,
    createdAt: new Date().toISOString(),
    metadata: meta,
  }
  chat.messages = [...chat.messages, userMsg]
  userEdits[userMsg.id] = text
  editingUserId.value = null
  await scrollToBottom(false)

  await runStream(text, undefined, undefined, ctxJson)
}

async function onSend() {
  await sendUserTurn(chat.inputDraft)
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
  await runStream(text, undefined, userMsgId, null)
}

function feedbackVote(m: ChatMessage): 'up' | 'down' | null {
  return parseFeedbackVote(m.metadata ?? undefined)
}

async function toggleFeedback(m: ChatMessage, v: 'up' | 'down') {
  const sid = chat.activeSessionId
  if (!sid || chat.sending) return
  const cur = feedbackVote(m)
  const vote = cur === v ? 'clear' : v
  try {
    const updated = await chatApi.setMessageFeedback(sid, m.id, vote)
    chat.patchMessageMetadata(m.id, updated.metadata ?? null)
  } catch (e) {
    ElMessage.error((e as Error).message || t('errors.send'))
  }
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

/** 落地页推荐问题：点击即用该文案发起一轮对话（等同发送） */
async function sendFromSuggestionChip(text: string) {
  if (chat.sending) return
  await sendUserTurn(text)
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
    const { fileName, mimeType, text } = await chatApi.extractAttachmentText(file)
    const isImg = mimeType.startsWith('image/')
    pendingAttachments.value.push({
      label: fileName,
      kind: isImg ? 'image' : 'file',
      fileName,
      mimeType,
      extractedText: text,
    })
    ElMessage.success(t('chat.uploadOk'))
  } catch (err) {
    ElMessage.error((err as Error).message || t('chat.uploadFail'))
  } finally {
    uploadBusy.value = false
  }
}

async function renameSessionTitle() {
  const sid = chat.activeSessionId
  if (!sid) return
  const cur = chat.activeSession?.title || ''
  try {
    const { value } = await ElMessageBox.prompt(t('session.renamePlaceholder'), t('session.renameTitle'), {
      inputValue: cur,
      confirmButtonText: t('session.save'),
      cancelButtonText: t('session.cancel'),
      inputPattern: /\S+/,
      inputErrorMessage: t('session.titleRequired'),
    })
    await chat.renameSession(sid, value)
    await chat.fetchSessions()
    ElMessage.success(t('session.save'))
  } catch {
    /* cancel */
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
      <el-tooltip hide-after="0" :content="t('chat.fullChatView')" placement="bottom">
        <el-button text circle size="small" @click="goFullChat">
          <el-icon><FullScreen /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <header v-if="chat.activeSessionId && !props.hideThreadHead" class="thread-head">
      <div class="thread-head-inner">
        <button type="button" class="thread-title thread-title--btn" @click="renameSessionTitle">
          {{ chat.activeSession?.title || t('session.defaultTitle') }}
        </button>
        <p class="thread-sub">{{ t('app.tagline') }}</p>
      </div>
    </header>

    <div class="main-mid">
    <div
      ref="msgScrollEl"
      class="msg-scroll u-scroll"
      @scroll.passive="onMsgScroll"
    >
      <div v-if="!chat.activeSessionId" class="landing">
        <h2 class="land-greeting">{{ t('chat.landGreeting') }}</h2>
        <p class="land-disclaimer">{{ t('chat.landDisclaimer') }}</p>
        <div class="suggestion-grid">
          <button
            v-for="(chip, i) in suggestionChips"
            :key="i"
            type="button"
            class="suggestion-chip"
            @click="sendFromSuggestionChip(chip)"
          >
            {{ chip }}
          </button>
        </div>
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
            @click="sendFromSuggestionChip(chip)"
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
                <div v-if="userBubbleChips(m).length" class="user-chip-row">
                  <span v-for="(c, ci) in userBubbleChips(m)" :key="ci" class="user-chip">{{ c.label }}</span>
                </div>
                <div v-if="(userEdits[m.id] ?? m.content).trim()" class="user-text-line">
                  {{ userEdits[m.id] ?? m.content }}
                </div>
              </div>
              <div class="user-actions" :class="{ 'user-actions--visible': showUserToolbar(idx, m) }">
                <template v-if="editingUserId === m.id">
                  <el-tooltip hide-after="0" :content="t('chat.cancelEdit')" placement="top">
                    <el-button text circle class="msg-toolbar-btn" @click="cancelUserEdit(m)">
                      <el-icon><Close /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip hide-after="0" :content="t('chat.copy')" placement="top">
                    <el-button text circle class="msg-toolbar-btn" @click="copyText(userEdits[m.id] || '')">
                      <el-icon><DocumentCopy /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip hide-after="0" :content="t('chat.resend')" placement="top">
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
                  <el-tooltip hide-after="0" :content="t('chat.copy')" placement="top">
                    <el-button text circle class="msg-toolbar-btn" @click="copyText(userEdits[m.id] || '')">
                      <el-icon><DocumentCopy /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip hide-after="0" :content="t('chat.edit')" placement="top">
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
              <div v-else class="prose-ai markdown-body" v-html="md(m.content)" />
            </div>

            <div v-if="m.role === 'ASSISTANT' && docMeta(m)" class="ai-toolbar-slot">
              <div class="ai-toolbar" :class="{ 'ai-toolbar--visible': showAiToolbar(idx, m) }">
                <el-tooltip hide-after="0" :content="t('chat.copy')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="copyText(docMeta(m)!.markdownBody)">
                    <el-icon><DocumentCopy /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.downloadDoc')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="downloadDocMarkdown(docMeta(m)!)">
                    <el-icon><Download /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="docMeta(m)!.frozen ? t('chat.openDocView') : t('chat.openDocEdit')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="docMeta(m)!.frozen ? openCanvasReadOnly(m) : openCanvasEdit(m)">
                    <el-icon><TopRight /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.speak')" placement="top">
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
                <el-tooltip hide-after="0" :content="t('chat.copy')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="copyText(m.content)">
                    <el-icon><DocumentCopy /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.like')" placement="top">
                  <el-button
                    text
                    circle
                    class="msg-toolbar-btn"
                    :type="feedbackVote(m) === 'up' ? 'primary' : 'default'"
                    :disabled="chat.sending"
                    @click="toggleFeedback(m, 'up')"
                  >
                    <span class="thumb-wrap" :class="{ muted: feedbackVote(m) !== 'up' }">
                      <IconThumbUp />
                    </span>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.dislike')" placement="top">
                  <el-button
                    text
                    circle
                    class="msg-toolbar-btn"
                    :type="feedbackVote(m) === 'down' ? 'danger' : 'default'"
                    :disabled="chat.sending"
                    @click="toggleFeedback(m, 'down')"
                  >
                    <span class="thumb-wrap" :class="{ muted: feedbackVote(m) !== 'down' }">
                      <IconThumbDown />
                    </span>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.speak')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="speakAssistant(m.content)">
                    <el-icon><Microphone /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.toCanvas')" placement="top">
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

            <div v-if="showInlineFollowUps(idx, m)" class="follow-up-inline">
              <div class="follow-up-inline-chips">
                <button
                  v-for="(q, i) in followUpQuestions"
                  :key="i"
                  type="button"
                  class="follow-chip follow-chip--inline"
                  :disabled="chat.sending"
                  @click="askFollowUp(q)"
                >
                  <span class="follow-chip-text">{{ q }}</span>
                  <span class="follow-chip-arrow" aria-hidden="true">→</span>
                </button>
              </div>
            </div>
          </div>
        </div>
        <div ref="bottomAnchor" class="anchor" />
      </div>
    </div>

    <div
      v-if="showJumpToBottom && chat.activeSessionId && chat.messages.length > 0 && !showLanding"
      class="jump-bar"
    >
      <el-tooltip hide-after="0" :content="t('chat.jumpToBottom')" placement="top">
        <el-button class="jump-btn" circle @click="jumpToLatest">
          <el-icon :size="18"><ArrowDown /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <footer class="composer">
      <div class="composer-inner">
        <input
          ref="fileInputRef"
          type="file"
          class="file-input"
          accept=".pdf,.doc,.docx,.txt,.md,.csv,.json,.xml,.html,.htm,.log,.yml,.yaml"
          @change="onFileSelected"
        />
        <input
          ref="imageInputRef"
          type="file"
          class="file-input"
          accept="image/png,image/jpeg,image/webp,image/gif,image/bmp"
          @change="onImageSelected"
        />
        <div v-if="pendingAttachments.length" class="pending-chips">
          <span v-for="(a, pi) in pendingAttachments" :key="pi" class="pending-chip">
            {{ a.label }}
            <button type="button" class="pending-chip-x" :aria-label="t('chat.removeAttachment')" @click="removePendingAttachment(pi)">
              ×
            </button>
          </span>
        </div>
        <el-input
          v-model="chat.inputDraft"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 8 }"
          resize="none"
          class="composer-input"
          :placeholder="t('chat.inputPlaceholder')"
          :disabled="uploadBusy || voiceRecording"
          @keydown="onKeydown"
        />
        <el-tooltip hide-after="0" :content="t('chat.uploadImage')" placement="top">
          <el-button
            class="image-fab"
            circle
            :disabled="chat.sending || uploadBusy"
            @click="openImagePicker"
          >
            <el-icon class="fab-icon"><Picture /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip hide-after="0" :content="voiceRecording ? t('chat.voiceStop') : t('chat.voiceStart')" placement="top">
          <el-button
            class="mic-fab"
            circle
            :type="voiceRecording ? 'danger' : 'default'"
            :disabled="chat.sending || uploadBusy"
            @click="toggleVoice"
          >
            <el-icon class="fab-icon"><Microphone /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip hide-after="0" :content="t('chat.upload')" placement="top">
          <el-button
            class="attach-fab"
            circle
            :disabled="chat.sending || uploadBusy"
            :loading="uploadBusy"
            @click="openFilePicker"
          >
            <el-icon class="fab-icon"><Paperclip /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip hide-after="0" :content="chat.sending ? t('chat.stop') : t('chat.send')" placement="top">
          <el-button
            class="send-fab"
            type="primary"
            circle
            :disabled="
              (!chat.sending && !chat.inputDraft.trim() && pendingAttachments.length === 0) ||
              uploadBusy ||
              voiceRecording
            "
            @click="chat.sending ? chat.stopStream() : onSend()"
          >
            <el-icon v-if="chat.sending" class="fab-icon"><CircleClose /></el-icon>
            <el-icon v-else class="fab-icon"><Promotion /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </footer>
    </div>
  </main>
</template>

<style scoped>
.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--bg-chat-surface);
}

/* 消息区 + 回到底部条 + 输入区：占满标题下方剩余高度 */
.main-mid {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  position: relative;
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
.thread-title--btn {
  display: inline-block;
  max-width: 100%;
  cursor: pointer;
  background: none;
  border: none;
  font: inherit;
  padding: 2px 8px;
  border-radius: 8px;
  transition: background 0.15s ease;
}
.thread-title--btn:hover {
  background: var(--bg-input-fill);
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

.jump-bar {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 6px 0 8px;
  background: var(--bg-chat-surface);
}

.follow-up-inline {
  margin-top: 10px;
  width: 100%;
  max-width: min(640px, 100%);
}

.follow-up-inline-chips {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.follow-chip {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: auto;
  max-width: 100%;
  box-sizing: border-box;
  text-align: left;
  padding: 5px 12px;
  border-radius: 12px;
  border: none;
  background: var(--bg-input-fill);
  color: var(--text-primary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.follow-chip--inline {
  white-space: nowrap;
}

.follow-chip:hover:not(:disabled) {
  background: var(--bg-elevated);
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.08);
}

.follow-chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.follow-chip-text {
  flex: 0 1 auto;
  min-width: 0;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
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

.jump-btn {
  width: 40px;
  height: 40px;
  background: var(--bg-elevated) !important;
  border: 1px solid var(--border-subtle) !important;
  color: var(--text-primary) !important;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.12);
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
.user-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.user-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-elevated);
  border: 1px solid var(--border-subtle);
}
.user-text-line {
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
  background: var(--bg-chat-surface);
  padding: 12px 16px 14px;
}

.composer-inner {
  position: relative;
  max-width: 880px;
  margin: 0 auto;
}

.pending-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.pending-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 4px 8px 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-input-fill);
  border: 1px solid var(--border-subtle);
}
.pending-chip--voice {
  border-color: var(--accent-soft);
  color: var(--accent);
}
.pending-chip-x {
  border: none;
  background: none;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  padding: 0 4px;
  color: var(--text-muted);
}
.pending-chip-x:hover {
  color: var(--text-primary);
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
  padding: 12px 168px 12px 14px;
  min-height: 88px !important;
  background: var(--bg-elevated) !important;
  color: var(--text-primary);
  border: 1px solid var(--border-subtle);
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

.image-fab {
  position: absolute;
  right: 142px;
  bottom: 10px;
  width: 40px;
  height: 40px;
  border: 1px solid var(--border-subtle) !important;
  background: var(--bg-elevated) !important;
  color: var(--text-secondary) !important;
  box-shadow: var(--shadow-sm);
}
.mic-fab {
  position: absolute;
  right: 98px;
  bottom: 10px;
  width: 40px;
  height: 40px;
  border: 1px solid var(--border-subtle) !important;
  background: var(--bg-elevated) !important;
  color: var(--text-secondary) !important;
  box-shadow: var(--shadow-sm);
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
  width: 32px !important;
  height: 32px !important;
  min-height: 32px !important;
  padding: 0 !important;
  margin: 0 4px !important;
  border: none !important;
  background: transparent !important;
  color: var(--text-secondary) !important;
  box-shadow: none !important;
}
.msg-toolbar-btn:hover {
  color: var(--text-primary) !important;
  background: var(--bg-input-fill) !important;
}
.msg-toolbar-btn.is-text.el-button--primary {
  color: var(--accent) !important;
  background: transparent !important;
}
.msg-toolbar-btn.is-text.el-button--danger {
  color: #ef4444 !important;
  background: transparent !important;
}
.msg-toolbar-btn :deep(.el-icon) {
  font-size: 16px;
}
</style>
