<script setup lang="ts">
import {
  ArrowDown,
  CircleClose,
  Close,
  DArrowLeft,
  Download,
  Edit,
  EditPen,
  Loading,
  Microphone,
  VideoPause,
  Paperclip,
  Picture,
  Promotion,
  TopRight,
} from '@element-plus/icons-vue'
import { FullScreen } from '@element-plus/icons-vue'
import { ElInput, ElMessage, ElMessageBox } from 'element-plus'
import type { CSSProperties } from 'vue'
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
  watchEffect,
} from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import * as chatApi from '../api/chatApi'
import type { ChatMessage, DocumentCardMeta } from '../api/types'
import { useChatStore } from '../stores/chat'
import { extractChatMarkdownHeadingToc, type ChatHeadingTocItem } from '../utils/chatMarkdownHeadings'
import { renderAiMarkdown } from '../utils/markdown'
import { markdownToPlainText } from '../utils/plainText'
import IconCopyLayers from './icons/IconCopyLayers.vue'
import IconThumbDown from './icons/IconThumbDown.vue'
import IconThumbUp from './icons/IconThumbUp.vue'
import { parseDocumentMeta } from '../utils/documentMeta'
import { parseFeedbackVote } from '../utils/messageFeedback'
import type { AttachmentChip } from '../utils/modelContext'
import { parseUserBubbleFromMetadata } from '../utils/modelContext'

const { t, locale } = useI18n()
const router = useRouter()
const chat = useChatStore()

/** SSE 已结束但打字机仍在追赶缓冲时为 true；与 chat.sending 一起表示「助手回复尚未完全展示」 */
const streamAnimating = ref(false)
const assistantReplyBusy = computed(() => chat.sending || streamAnimating.value)
/** 为 true 时消息更新自动滚到底部；用户向上滚动离开底部附近后设为 false，避免无法回看 */
const scrollStickToEnd = ref(true)

const props = defineProps<{
  /** 内嵌在画布分屏左侧时隐藏会话标题区 */
  hideThreadHead?: boolean
}>()

const bottomAnchor = ref<HTMLElement | null>(null)
const msgScrollEl = ref<HTMLElement | null>(null)
/** 当前用于右侧 Markdown 目录的助手消息 id（按滚动区域可见度选取） */
const activeOutlineMessageId = ref<string | null>(null)
/** 当前视区内对应的标题锚点 id（与正文 h* 的 id 一致） */
const activeOutlineHeadingId = ref<string | null>(null)
/** 点击目录后短时间内不随几何重算覆盖（避免 smooth scroll 过程跳动） */
const outlineGeomLockUntil = ref(0)
const outlineLockMessageId = ref<string | null>(null)
const outlineLockHeadingId = ref<string | null>(null)
const OUTLINE_GEOM_LOCK_MS = 1200
let outlineScrollRaf = 0
let outlineResizeObs: ResizeObserver | null = null
/** 右侧 Markdown 目录面板是否展开（可手动关闭，有关闭条时再显示） */
const outlinePanelVisible = ref(true)
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
const composerInputRef = ref<InstanceType<typeof ElInput> | null>(null)
let composerScrollTimer: ReturnType<typeof setTimeout> | null = null

/** 当前朗读绑定的消息 id；再次点击同一消息的朗读按钮则取消 */
const ttsMessageId = ref<string | null>(null)

const showLanding = computed(
  () =>
    !!chat.activeSessionId &&
    !chat.loadingMessages &&
    !chat.sending &&
    chat.messages.length === 0 &&
    !props.hideThreadHead,
)

const landingSuggestions = ref<string[]>([])
const loadingLandingSuggestions = ref(false)
/** 变更时触发落地推荐芯片入场动画 */
const landingChipsAnimateKey = ref(0)
let landingFetchGen = 0

/** 无会话或当前会话尚无消息时展示落地推荐（画布内嵌不请求） */
const showSuggestionLanding = computed(
  () => !props.hideThreadHead && (!chat.activeSessionId || showLanding.value),
)

async function refreshLandingSuggestions() {
  if (!showSuggestionLanding.value) return
  const gen = ++landingFetchGen
  loadingLandingSuggestions.value = true
  try {
    const qs = await chatApi.fetchLandingSuggestions()
    if (gen !== landingFetchGen) return
    landingSuggestions.value = qs
    if (qs.length) landingChipsAnimateKey.value++
  } catch {
    if (gen !== landingFetchGen) return
    landingSuggestions.value = []
  } finally {
    if (gen === landingFetchGen) loadingLandingSuggestions.value = false
  }
}

watch(
  showSuggestionLanding,
  (show) => {
    if (show) void refreshLandingSuggestions()
  },
  { immediate: true },
)

/** 画布内嵌：输入区更矮；全屏略高 */
const composerAutosize = computed(() =>
  props.hideThreadHead ? { minRows: 2, maxRows: 8 } : { minRows: 4, maxRows: 10 },
)

function isLastMessage(idx: number) {
  return idx === chat.messages.length - 1
}

function isAssistantStreaming(m: ChatMessage) {
  return m.role === 'ASSISTANT' && m.content.length === 0 && chat.sending
}

function showUserToolbar(idx: number, m: ChatMessage) {
  if (m.role !== 'USER') return false
  if (assistantReplyBusy.value && isLastMessage(idx)) return false
  if (editingUserId.value === m.id) return true
  return isLastMessage(idx) || !!hoveringRow[m.id]
}

function showAiToolbar(idx: number, m: ChatMessage) {
  if (m.role !== 'ASSISTANT') return false
  if (isAssistantStreaming(m)) return false
  if (assistantReplyBusy.value && isLastMessage(idx)) return false
  return isLastMessage(idx) || !!hoveringRow[m.id]
}

function showInlineFollowUps(idx: number, m: ChatMessage) {
  if (props.hideThreadHead) return false
  if (!chat.activeSessionId || chat.loadingMessages || showLanding.value) return false
  if (m.role !== 'ASSISTANT' || docMeta(m)) return false
  if (!isLastMessage(idx) || assistantReplyBusy.value) return false
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

function isScrollNearBottom(el: HTMLElement, gap = 100) {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= gap
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
  const el = msgScrollEl.value
  if (el && !isScrollNearBottom(el)) {
    scrollStickToEnd.value = false
  }
  updateScrollBottomState()
  updateActiveOutlineFromScroll()
  if (!el) return
  el.classList.add('u-scroll--active')
  if (scrollThumbTimer) clearTimeout(scrollThumbTimer)
  scrollThumbTimer = setTimeout(() => {
    el.classList.remove('u-scroll--active')
    scrollThumbTimer = null
  }, 900)
}

function jumpToLatest() {
  scrollStickToEnd.value = true
  void scrollToBottom(true)
}

function getComposerTextarea(): HTMLTextAreaElement | null {
  const inst = composerInputRef.value as unknown as { $el?: HTMLElement } | null
  const root = inst?.$el
  if (!root) return null
  return root.querySelector('textarea')
}

function onComposerTextareaScroll() {
  const ta = getComposerTextarea()
  if (!ta) return
  ta.classList.add('u-scroll--active')
  if (composerScrollTimer) clearTimeout(composerScrollTimer)
  composerScrollTimer = setTimeout(() => {
    ta.classList.remove('u-scroll--active')
    composerScrollTimer = null
  }, 900)
}

function bindComposerTextareaScroll() {
  const ta = getComposerTextarea()
  if (!ta) return
  ta.classList.add('u-scroll')
  ta.addEventListener('scroll', onComposerTextareaScroll, { passive: true })
}

function unbindComposerTextareaScroll() {
  if (composerScrollTimer) {
    clearTimeout(composerScrollTimer)
    composerScrollTimer = null
  }
  const ta = getComposerTextarea()
  if (ta) {
    ta.removeEventListener('scroll', onComposerTextareaScroll)
    ta.classList.remove('u-scroll', 'u-scroll--active')
  }
}

watch(
  () => chat.messages,
  () => {
    syncUserEditsFromMessages()
    if (scrollStickToEnd.value) {
      void scrollToBottom(true)
    }
    void nextTick(() => {
      updateScrollBottomState()
      updateActiveOutlineFromScroll()
    })
  },
  { deep: true, immediate: true },
)

watch(
  () => chat.activeSessionId,
  () => {
    scrollStickToEnd.value = true
    editingUserId.value = null
    followUpQuestions.value = []
    activeOutlineMessageId.value = null
    activeOutlineHeadingId.value = null
    outlineGeomLockUntil.value = 0
    outlineLockMessageId.value = null
    outlineLockHeadingId.value = null
    outlinePanelVisible.value = true
    void scrollToBottom(false)
    void nextTick(() => updateScrollBottomState())
  },
)

watch(
  () => chat.loadingMessages,
  (v) => {
    if (!v) {
      void nextTick(() => {
        updateScrollBottomState()
        updateActiveOutlineFromScroll()
      })
      void loadFollowUps()
    }
  },
)

watch(
  () => assistantReplyBusy.value,
  (v, prev) => {
    if (prev && !v) void loadFollowUps()
  },
)

watch(
  () => chat.inputDraft,
  () => {
    void nextTick(() => {
      unbindComposerTextareaScroll()
      bindComposerTextareaScroll()
    })
  },
)

watch(
  () => props.hideThreadHead,
  () => {
    void nextTick(() => {
      unbindComposerTextareaScroll()
      bindComposerTextareaScroll()
    })
  },
)

onMounted(() => {
  window.addEventListener('resize', updateScrollBottomState, { passive: true })
  void nextTick(() => {
    updateScrollBottomState()
    updateActiveOutlineFromScroll()
    bindComposerTextareaScroll()
  })
})

watchEffect((onCleanup) => {
  const el = msgScrollEl.value
  if (!el || props.hideThreadHead) {
    outlineResizeObs?.disconnect()
    outlineResizeObs = null
    return
  }
  outlineResizeObs = new ResizeObserver(() => {
    updateActiveOutlineFromScroll()
  })
  outlineResizeObs.observe(el)
  onCleanup(() => {
    outlineResizeObs?.disconnect()
    outlineResizeObs = null
  })
})

onBeforeUnmount(() => {
  unbindComposerTextareaScroll()
  if (scrollThumbTimer) clearTimeout(scrollThumbTimer)
  msgScrollEl.value?.classList.remove('u-scroll--active')
  window.removeEventListener('resize', updateScrollBottomState)
  try {
    window.speechSynthesis.cancel()
  } catch {
    /* ignore */
  }
  ttsMessageId.value = null
  stopVoiceCapture()
  if (outlineScrollRaf) {
    cancelAnimationFrame(outlineScrollRaf)
    outlineScrollRaf = 0
  }
  outlineResizeObs?.disconnect()
  outlineResizeObs = null
})

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('session.copied'))
  } catch {
    ElMessage.error(t('session.copyFail'))
  }
}

function onKeydown(e: Event) {
  const ke = e as KeyboardEvent
  if (ke.key === 'Enter' && !ke.shiftKey) {
    ke.preventDefault()
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

/** 结束浏览器语音识别：立即更新 UI，与消息朗读的「暂停」图标区分开 */
function stopVoiceCapture() {
  const r = speechRec
  speechRec = null
  voiceRecording.value = false
  if (!r) return
  try {
    // abort 更快结束且不等待最终结果；部分环境仅支持 stop
    const anyR = r as { abort?: () => void; stop: () => void }
    if (typeof anyR.abort === 'function') {
      anyR.abort()
    } else {
      anyR.stop()
    }
  } catch {
    try {
      r.stop()
    } catch {
      /* ignore */
    }
  }
}

function toggleVoice() {
  type SpeechRecCtor = new () => {
    start: () => void
    stop: () => void
    abort?: () => void
    lang: string
    continuous: boolean
    interimResults: boolean
    onresult: ((ev: unknown) => void) | null
    onerror: (() => void) | null
    onend: (() => void) | null
  }
  const w = window as unknown as { SpeechRecognition?: SpeechRecCtor; webkitSpeechRecognition?: SpeechRecCtor }
  const SR = w.SpeechRecognition || w.webkitSpeechRecognition
  if (!SR) {
    ElMessage.warning(t('chat.voiceUnsupported'))
    return
  }
  if (voiceRecording.value || speechRec) {
    stopVoiceCapture()
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
    speechRec = null
    voiceDraftBase = ''
  }
  speechRec.onend = () => {
    voiceRecording.value = false
    speechRec = null
    voiceDraftBase = ''
  }
  voiceRecording.value = true
  try {
    speechRec.start()
  } catch {
    voiceRecording.value = false
    speechRec = null
    voiceDraftBase = ''
    ElMessage.warning(t('chat.voiceUnsupported'))
  }
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
  scrollStickToEnd.value = true
  streamAnimating.value = true
  let assistantId = ''
  let streamTargetMessageId = ''
  /** SSE 已收全文；界面用打字机从该缓冲快速追赶展示 */
  let streamBuffer = ''
  let streamShown = 0
  let streamRaf = 0

  const flushStreamVisual = async () => {
    if (!assistantId) return
    if (streamShown < streamBuffer.length) {
      streamShown = streamBuffer.length
      chat.messages = chat.messages.map((m) =>
        m.id === assistantId ? { ...m, content: streamBuffer } : m,
      )
      await nextTick()
    }
  }

  function waitTypewriterCatchUp(): Promise<void> {
    const deadline = performance.now() + 30_000
    return new Promise((resolve) => {
      const step = () => {
        if (streamShown >= streamBuffer.length) {
          resolve()
          return
        }
        if (performance.now() > deadline) {
          resolve()
          return
        }
        /** 流已结束：可加大步进，尽快对齐全文 */
        const remain = streamBuffer.length - streamShown
        const burst = Math.min(remain, Math.max(24, Math.ceil(remain / 5)))
        streamShown = Math.min(streamBuffer.length, streamShown + burst)
        const tid = assistantId || streamTargetMessageId
        if (tid) {
          chat.messages = chat.messages.map((m) =>
            m.id === tid ? { ...m, content: streamBuffer.slice(0, streamShown) } : m,
          )
        }
        requestAnimationFrame(step)
      }
      step()
    })
  }

  /** SSE 未结束前：固定小步进，避免积压一大段时一帧跳上百字（看起来像整段输出） */
  const TYPEWRITER_CHARS_PER_FRAME = 3

  const pumpTypewriter = () => {
    streamRaf = 0
    const tid = assistantId || streamTargetMessageId
    if (!tid) return
    if (streamShown < streamBuffer.length) {
      streamShown = Math.min(streamBuffer.length, streamShown + TYPEWRITER_CHARS_PER_FRAME)
      chat.messages = chat.messages.map((m) =>
        m.id === tid ? { ...m, content: streamBuffer.slice(0, streamShown) } : m,
      )
      const el = msgScrollEl.value
      if (scrollStickToEnd.value && el && isScrollNearBottom(el)) {
        void scrollToBottom(false)
      }
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
  const appendAfter = appendAfterUserMessageId != null && appendAfterUserMessageId !== ''
  let nextMsgs = [...chat.messages]
  if (appendAfter) {
    const localUserId = `local-user-${Date.now()}`
    nextMsgs.push({
      id: localUserId,
      role: 'USER',
      content: userText,
      createdAt: new Date().toISOString(),
      metadata: null,
    })
    userEdits[localUserId] = userText
  }
  nextMsgs.push(assistantPlaceholder)
  chat.messages = nextMsgs

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
        if (!chunk) return
        streamBuffer += chunk
        schedulePump()
      },
      async onDone() {
        await waitTypewriterCatchUp()
        stopTypewriter()
        await flushStreamVisual()
        if (appendAfter) {
          await chat.fetchMessages(sid, { silent: true })
        }
        await chat.fetchSessions()
        syncUserEditsFromMessages()
        if (scrollStickToEnd.value) {
          await scrollToBottom(true)
        }
        await nextTick()
        streamAnimating.value = false
        afterDone?.()
      },
    })
    .catch(async (e: unknown) => {
      await waitTypewriterCatchUp()
      stopTypewriter()
      await flushStreamVisual()
      streamAnimating.value = false
      if ((e as Error).name === 'AbortError') {
        ElMessage.info(t('errors.stopped'))
      } else {
        ElMessage.error((e as Error).message || t('errors.send'))
      }
      return chat.fetchMessages(sid, { silent: true })
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
  if (assistantReplyBusy.value) return
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
  if (!sid || assistantReplyBusy.value) return
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
  if (!sid || assistantReplyBusy.value) return
  const cur = feedbackVote(m)
  const vote = cur === v ? 'clear' : v
  try {
    const updated = await chatApi.setMessageFeedback(sid, m.id, vote)
    chat.patchMessageMetadata(m.id, updated.metadata ?? null)
  } catch (e) {
    ElMessage.error((e as Error).message || t('errors.send'))
  }
}

function isTtsBusyForMessage(messageId: string) {
  return (
    ttsMessageId.value === messageId &&
    (window.speechSynthesis.speaking || window.speechSynthesis.pending)
  )
}

function speakTooltip(messageId: string) {
  return isTtsBusyForMessage(messageId) ? t('chat.speakStop') : t('chat.speak')
}

function speakAssistant(text: string, messageId: string) {
  const plain = markdownToPlainText(text)
  if (!plain) return
  if (isTtsBusyForMessage(messageId)) {
    window.speechSynthesis.cancel()
    ttsMessageId.value = null
    return
  }
  window.speechSynthesis.cancel()
  ttsMessageId.value = messageId
  const u = new SpeechSynthesisUtterance(plain)
  const loc = navigator.language || 'zh-CN'
  u.lang = loc.startsWith('zh') ? 'zh-CN' : 'en-US'
  const finish = () => {
    if (ttsMessageId.value === messageId) {
      ttsMessageId.value = null
    }
  }
  u.onend = finish
  u.onerror = finish
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
  if (!sid || assistantReplyBusy.value || docConvertBusyId.value) return
  docConvertBusyId.value = msg.id
  try {
    await chatApi.convertToDocument(sid, msg.id)
    await chat.fetchMessages(sid, { silent: true })
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

function mdMessage(m: ChatMessage) {
  void locale.value
  return renderAiMarkdown(m.content, m.id)
}

/** 流式中最后一条助手：Markdown + 轻量代码块（无 hljs），与打字机切片同步 */
function mdMessageStreaming(m: ChatMessage) {
  void locale.value
  return renderAiMarkdown(m.content, m.id, { lightCodeBlocks: true })
}

/** 每条助手气泡的 Markdown 标题目录（无标题的消息不在 Map 中） */
const assistantOutlineByMessageId = computed(() => {
  const map = new Map<string, ChatHeadingTocItem[]>()
  for (const m of chat.messages) {
    if (m.role !== 'ASSISTANT' || docMeta(m)) continue
    const text = (m.content || '').trim()
    if (!text) continue
    const items = extractChatMarkdownHeadingToc(text, m.id)
    if (items.length) map.set(m.id, items)
  }
  return map
})

const outlineItems = computed<ChatHeadingTocItem[]>(() => {
  if (props.hideThreadHead) return []
  const id = activeOutlineMessageId.value
  if (!id) return []
  return assistantOutlineByMessageId.value.get(id) ?? []
})

const hasOutlineContent = computed(() => !props.hideThreadHead && outlineItems.value.length > 0)
const showOutlinePanel = computed(() => hasOutlineContent.value && outlinePanelVisible.value)

watch(hasOutlineContent, (v, prev) => {
  if (v && !prev) outlinePanelVisible.value = true
})

function flashHeading(el: HTMLElement) {
  el.classList.remove('chat-md-heading-flash')
  void el.offsetWidth
  el.classList.add('chat-md-heading-flash')
  window.setTimeout(() => el.classList.remove('chat-md-heading-flash'), 900)
}

function findHeadingInBubble(body: Element, it: ChatHeadingTocItem): HTMLElement | null {
  const byId = body.querySelector(`#${CSS.escape(it.id)}`) as HTMLElement | null
  if (byId) return byId
  const list = Array.from(body.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6'))
  const byIndex = list[it.headingIndex]
  if (byIndex) return byIndex
  return list.find((h) => h.id === it.id) ?? null
}

async function scrollToHeading(it: ChatHeadingTocItem) {
  const lockUntil = performance.now() + OUTLINE_GEOM_LOCK_MS
  outlineGeomLockUntil.value = lockUntil
  outlineLockMessageId.value = it.messageId
  outlineLockHeadingId.value = it.id
  activeOutlineMessageId.value = it.messageId
  activeOutlineHeadingId.value = it.id

  await nextTick()
  const scrollEl = msgScrollEl.value
  if (!scrollEl) return
  const mid = it.messageId
  const row =
    mid && mid.length > 0
      ? (Array.from(scrollEl.querySelectorAll<HTMLElement>('.row[data-message-id]')).find(
          (r) => r.dataset.messageId === mid,
        ) ?? null)
      : null
  const body = row?.querySelector('.prose-ai.markdown-body') ?? null
  if (!body) return
  const el = findHeadingInBubble(body, it)
  if (!el) return
  /** 与画布预览一致：先让标题进入视口，再在滚动容器内微调并闪烁 */
  el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' })
  const pad = 12
  const snapAndFlash = () => {
    const maxTop = Math.max(0, scrollEl.scrollHeight - scrollEl.clientHeight)
    const top =
      scrollEl.scrollTop +
      (el.getBoundingClientRect().top - scrollEl.getBoundingClientRect().top) -
      pad
    scrollEl.scrollTop = Math.max(0, Math.min(top, maxTop))
    flashHeading(el)
  }
  window.setTimeout(snapAndFlash, 320)
  window.setTimeout(snapAndFlash, 720)
}

function updateActiveOutlineFromScroll() {
  if (outlineScrollRaf) cancelAnimationFrame(outlineScrollRaf)
  outlineScrollRaf = requestAnimationFrame(() => {
    outlineScrollRaf = 0
    const scrollEl = msgScrollEl.value
    if (!scrollEl || props.hideThreadHead) return

    const now = performance.now()
    if (
      now < outlineGeomLockUntil.value &&
      outlineLockMessageId.value &&
      outlineLockHeadingId.value
    ) {
      activeOutlineMessageId.value = outlineLockMessageId.value
      activeOutlineHeadingId.value = outlineLockHeadingId.value
      return
    }

    const rows = scrollEl.querySelectorAll<HTMLElement>('.row[data-outline-candidate="1"]')
    if (!rows.length) {
      activeOutlineMessageId.value = null
      activeOutlineHeadingId.value = null
      return
    }
    const top = scrollEl.scrollTop
    const bottom = top + scrollEl.clientHeight
    const anchorY = top + Math.min(72, scrollEl.clientHeight * 0.12)
    let bestId: string | null = null
    let bestScore = -1
    let bestRow: HTMLElement | null = null
    for (const row of rows) {
      const off = row.offsetTop
      const h = row.offsetHeight
      const overlap = Math.max(0, Math.min(bottom, off + h) - Math.max(top, off))
      if (overlap > bestScore) {
        bestScore = overlap
        bestId = row.dataset.messageId ?? null
        bestRow = row
      }
    }
    if (bestScore <= 0 || !bestId || !bestRow) {
      activeOutlineMessageId.value = null
      activeOutlineHeadingId.value = null
      return
    }

    const items = assistantOutlineByMessageId.value.get(bestId) ?? []
    const body = bestRow.querySelector('.prose-ai.markdown-body')
    let headingId: string | null = null
    if (body && items.length) {
      const headings = Array.from(body.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6'))
      let domIdx = -1
      for (let i = headings.length - 1; i >= 0; i--) {
        const h = headings[i]!
        const contentY =
          h.getBoundingClientRect().top - scrollEl.getBoundingClientRect().top + scrollEl.scrollTop
        if (contentY <= anchorY + 1) {
          domIdx = i
          break
        }
      }
      if (domIdx < 0) {
        const h0 = headings[0]
        headingId = h0?.id?.trim() ? h0.id : items[0]?.id ?? null
      } else {
        const h = headings[domIdx]!
        headingId =
          h.id?.trim()
            ? h.id
            : items[Math.min(domIdx, items.length - 1)]?.id ?? null
      }
    }
    if (!headingId && items.length) headingId = items[0]!.id

    activeOutlineMessageId.value = bestId
    activeOutlineHeadingId.value = headingId
  })
}

function isOutlineRowCandidate(m: ChatMessage) {
  return m.role === 'ASSISTANT' && assistantOutlineByMessageId.value.has(m.id)
}

async function loadFollowUps() {
  const sid = chat.activeSessionId
  if (!sid || assistantReplyBusy.value || props.hideThreadHead) {
    followUpQuestions.value = []
    return
  }
  try {
    followUpQuestions.value = await chatApi.fetchFollowUpQuestions(sid)
  } catch {
    followUpQuestions.value = []
  }
}

const suggestionChips = computed(() => landingSuggestions.value)

/** 落地页推荐问题：点击即用该文案发起一轮对话（等同发送） */
async function sendFromSuggestionChip(text: string) {
  if (assistantReplyBusy.value) return
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
  const sid = chat.activeSessionId
  if (sid) {
    void router.push({ name: 'chat', params: { sessionId: sid } })
  } else {
    void router.push({ name: 'chat' })
  }
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
    <div class="scroll-layout">
    <div class="chat-column">
    <div
      ref="msgScrollEl"
      class="msg-scroll u-scroll"
      @scroll.passive="onMsgScroll"
    >
      <div v-if="!chat.activeSessionId" class="landing">
        <h2 class="land-greeting">{{ t('chat.landGreeting') }}</h2>
        <div v-if="loadingLandingSuggestions" class="landing-suggest-loading" aria-busy="true">
          <el-icon class="landing-suggest-spin"><Loading /></el-icon>
        </div>
        <div v-else class="suggestion-grid">
          <button
            v-for="(chip, i) in suggestionChips"
            :key="`${landingChipsAnimateKey}-${i}`"
            type="button"
            class="suggestion-chip"
            :style="{ '--chip-i': String(i) } as CSSProperties"
            @click="sendFromSuggestionChip(chip)"
          >
            {{ chip }}
          </button>
        </div>
      </div>
      <div v-else-if="chat.loadingMessages" class="muted center">{{ t('chat.loadingMessages') }}</div>
      <div v-else-if="showLanding" class="landing">
        <h2 class="land-greeting">{{ t('chat.landGreeting') }}</h2>
        <div v-if="loadingLandingSuggestions" class="landing-suggest-loading" aria-busy="true">
          <el-icon class="landing-suggest-spin"><Loading /></el-icon>
        </div>
        <div v-else class="suggestion-grid">
          <button
            v-for="(chip, i) in suggestionChips"
            :key="`${landingChipsAnimateKey}-${i}`"
            type="button"
            class="suggestion-chip"
            :style="{ '--chip-i': String(i) } as CSSProperties"
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
          :data-message-id="m.id"
          :data-outline-candidate="isOutlineRowCandidate(m) ? '1' : undefined"
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
                      <span class="msg-toolbar-copy-ic"><IconCopyLayers /></span>
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
                      <span class="msg-toolbar-copy-ic"><IconCopyLayers /></span>
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
              <div v-if="isAssistantStreaming(m)" class="typing" aria-hidden="true">
                <span class="dot" /><span class="dot" /><span class="dot" />
              </div>
              <div
                v-else-if="m.role === 'ASSISTANT' && !docMeta(m) && chat.sending && isLastMessage(idx)"
                class="prose-ai markdown-body"
                v-html="mdMessageStreaming(m)"
              />
              <div v-else class="prose-ai markdown-body" v-html="mdMessage(m)" />
            </div>

            <div v-if="m.role === 'ASSISTANT' && docMeta(m)" class="ai-toolbar-slot">
              <div class="ai-toolbar" :class="{ 'ai-toolbar--visible': showAiToolbar(idx, m) }">
                <el-tooltip hide-after="0" :content="t('chat.copy')" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="copyText(docMeta(m)!.markdownBody)">
                    <span class="msg-toolbar-copy-ic"><IconCopyLayers /></span>
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
                <el-tooltip hide-after="0" :content="speakTooltip(m.id)" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="speakAssistant(docMeta(m)!.markdownBody, m.id)">
                    <el-icon v-if="isTtsBusyForMessage(m.id)"><VideoPause /></el-icon>
                    <el-icon v-else><Microphone /></el-icon>
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
                    <span class="msg-toolbar-copy-ic"><IconCopyLayers /></span>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.like')" placement="top">
                  <el-button
                    text
                    circle
                    class="msg-toolbar-btn"
                    :type="feedbackVote(m) === 'up' ? 'primary' : 'default'"
                    :disabled="assistantReplyBusy"
                    @click="toggleFeedback(m, 'up')"
                  >
                    <span class="thumb-wrap" :class="{ 'thumb-wrap--on': feedbackVote(m) === 'up' }">
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
                    :disabled="assistantReplyBusy"
                    @click="toggleFeedback(m, 'down')"
                  >
                    <span class="thumb-wrap" :class="{ 'thumb-wrap--on': feedbackVote(m) === 'down' }">
                      <IconThumbDown />
                    </span>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="speakTooltip(m.id)" placement="top">
                  <el-button text circle class="msg-toolbar-btn" @click="speakAssistant(m.content, m.id)">
                    <el-icon v-if="isTtsBusyForMessage(m.id)"><VideoPause /></el-icon>
                    <el-icon v-else><Microphone /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip hide-after="0" :content="t('chat.toCanvas')" placement="top">
                  <el-button
                    text
                    circle
                    class="msg-toolbar-btn"
                    :loading="docConvertBusyId === m.id"
                    :disabled="!!docConvertBusyId || assistantReplyBusy"
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
                  :disabled="assistantReplyBusy"
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

    <footer class="composer">
      <div class="composer-inner">
        <div
          v-if="showJumpToBottom && chat.activeSessionId && chat.messages.length > 0 && !showLanding"
          class="jump-above-input"
        >
          <el-tooltip hide-after="0" :content="t('chat.jumpToBottom')" placement="top">
            <el-button class="jump-btn" circle @click="jumpToLatest">
              <el-icon :size="18"><ArrowDown /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
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
          ref="composerInputRef"
          v-model="chat.inputDraft"
          type="textarea"
          :autosize="composerAutosize"
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
            <el-icon v-if="voiceRecording" class="fab-icon"><Close /></el-icon>
            <el-icon v-else class="fab-icon"><Microphone /></el-icon>
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
    </div>

    <aside
      v-if="showOutlinePanel"
      class="chat-md-outline u-scroll"
      aria-label="markdown outline"
    >
      <div class="chat-md-outline-head-row">
        <span class="chat-md-outline-head">{{ t('chat.mdOutline') }}</span>
        <el-tooltip hide-after="0" :content="t('chat.mdOutlineClose')" placement="left">
          <button type="button" class="chat-md-outline-close" :aria-label="t('chat.mdOutlineClose')" @click="outlinePanelVisible = false">
            <el-icon :size="14"><Close /></el-icon>
          </button>
        </el-tooltip>
      </div>
      <nav class="chat-md-outline-nav">
        <button
          v-for="it in outlineItems"
          :key="it.id"
          type="button"
          class="chat-md-outline-link"
          :class="[
            `depth-${it.depth}`,
            { 'chat-md-outline-link--active': activeOutlineHeadingId === it.id },
          ]"
          @click="scrollToHeading(it)"
        >
          {{ it.text }}
        </button>
      </nav>
    </aside>

    <button
      v-if="hasOutlineContent && !showOutlinePanel"
      type="button"
      class="chat-md-outline-reopen"
      :aria-label="t('chat.mdOutlineShow')"
      @click="outlinePanelVisible = true"
    >
      <el-icon :size="14"><DArrowLeft /></el-icon>
    </button>
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
  --chat-content-max: min(790px, calc(100% - 250px));
}

/* 画布分屏内嵌：左右留白收紧，内容区占满可用宽度 */
.main--embedded {
  --chat-content-max: 100%;
}
.main--embedded .embed-bar {
  padding-left: 10px;
  padding-right: 10px;
}
.main--embedded .msg-scroll {
  padding: 16px 25px 25px;
}
.main--embedded .composer {
  padding: 0px 22px 12px 12px;
}
.main--embedded .composer-inner {
  max-width: 100%;
  margin-left: 0;
  margin-right: 0;
}
.main--embedded .msg-inner,
.main--embedded .follow-up-inline {
  max-width: 100%;
}

/* 消息区 + 回到底部条 + 输入区：占满标题下方剩余高度 */
.main-mid {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  position: relative;
}

.scroll-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  min-width: 0;
}

/* 消息列表 + 输入框同一列 */
.chat-column {
  flex: 1 1 auto;
  width: 100%;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chat-md-outline {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 200px;
  width: 200px;
  max-height: none;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 10px 12px 14px;
  opacity: 0.8;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  z-index: 4;
  pointer-events: auto;
  /* 上→下与聊天背景融合 */
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--bg-chat-surface) 45%, transparent) 0%,
    color-mix(in srgb, var(--bg-chat-surface) 82%, transparent) 42%,
    var(--bg-chat-surface) 100%
  );
  box-shadow: -10px 0 36px color-mix(in srgb, var(--bg-chat-surface) 35%, transparent);
}

/* 左侧竖线：自上而下渐隐，与背景衔接 */
.chat-md-outline::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 1px;
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--bg-chat-surface) 0%, transparent) 0%,
    var(--border-subtle) 28%,
    var(--border-subtle) 72%,
    color-mix(in srgb, var(--bg-chat-surface) 0%, transparent) 100%
  );
  pointer-events: none;
}

/* 点击目录定位后：标题闪烁两次（v-html 内须用 :deep） */
:deep(.prose-ai.markdown-body h1.chat-md-heading-flash),
:deep(.prose-ai.markdown-body h2.chat-md-heading-flash),
:deep(.prose-ai.markdown-body h3.chat-md-heading-flash),
:deep(.prose-ai.markdown-body h4.chat-md-heading-flash),
:deep(.prose-ai.markdown-body h5.chat-md-heading-flash),
:deep(.prose-ai.markdown-body h6.chat-md-heading-flash) {
  animation: chat-md-heading-flash 0.28s ease-in-out 2;
  border-radius: 4px;
}
@keyframes chat-md-heading-flash {
  0%,
  100% {
    box-shadow: 0 0 0 0 transparent;
    background-color: transparent;
  }
  50% {
    box-shadow: 0 0 0 3px var(--accent-soft);
    background-color: var(--bg-input-fill);
  }
}

.chat-md-outline-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  border-bottom: none;
  box-shadow: none;
}

.chat-md-outline-head {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--text-muted);
  min-width: 0;
}

.chat-md-outline-close {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.chat-md-outline-close:hover {
  background: var(--bg-input-fill);
  color: var(--text-primary);
}

.chat-md-outline-reopen {
  position: absolute;
  top: 12px;
  right: 0;
  z-index: 5;
  width: 28px;
  height: 40px;
  padding: 0;
  border: 1px solid var(--border-subtle);
  border-right: none;
  border-radius: 8px 0 0 8px;
  background: var(--bg-chat-surface);
  opacity: 0.9;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: var(--text-secondary);
  cursor: pointer;
  display: grid;
  place-items: center;
  box-shadow: -4px 0 16px rgba(15, 23, 42, 0.08);
  transition: background 0.15s ease, color 0.15s ease;
}
.chat-md-outline-reopen:hover {
  background: var(--bg-input-fill);
  color: var(--text-primary);
}

.chat-md-outline-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chat-md-outline-link {
  display: block;
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  line-height: 1.35;
  padding: 5px 6px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.chat-md-outline-link:hover {
  background: var(--bg-input-fill);
  color: var(--text-primary);
}

.chat-md-outline-link--active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.chat-md-outline-link.depth-1 {
  font-weight: 600;
}
.chat-md-outline-link.depth-2 {
  padding-left: 8px;
}
.chat-md-outline-link.depth-3 {
  padding-left: 14px;
  font-size: 11.5px;
}
.chat-md-outline-link.depth-4,
.chat-md-outline-link.depth-5,
.chat-md-outline-link.depth-6 {
  padding-left: 20px;
  font-size: 11px;
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

.suggestion-grid {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.landing-suggest-loading {
  margin-top: 22px;
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.landing-suggest-spin {
  font-size: 28px;
  color: var(--accent);
  animation: landing-spin 0.9s linear infinite;
}

@keyframes landing-spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes landing-chip-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
    filter: blur(2px);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
    filter: blur(0);
  }
}

.suggestion-chip {
  border: none;
  background: var(--bg-input-fill);
  color: var(--text-primary);
  font: inherit;
  font-size: 15px;
  padding: 10px 14px;
  border-radius: 999px;
  cursor: pointer;
  animation: landing-chip-in 0.42s cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: calc(var(--chip-i, 0) * 45ms);
  transition:
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.suggestion-chip:hover {
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
  max-width: var(--chat-content-max, min(790px, calc(100% - 250px)));
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

.follow-up-inline {
  margin-top: 10px;
  width: 100%;
  max-width: var(--chat-content-max, min(790px, calc(100% - 250px)));
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
  grid-template-columns: 1fr minmax(100px, 60%);
  gap: 0;
  min-height: 110px;
}

.doc-card-left {
  padding: 10px 12px;
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
  padding: 8px 10px 8px 12px;
  background: var(--bg-elevated);
  border-radius: 0 14px 14px 0;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
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
  max-height: 100%;
  flex: 1;
  min-height: 0;
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
  max-width: var(--chat-content-max, min(790px, calc(100% - 250px)));
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

/* 助手消息占满消息列宽度，避免长文/Markdown 只占半栏 */
.row.start .bubble-wrap {
  max-width: 100%;
  width: 100%;
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
  min-width: 0;
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
  color: #68748b;
}
.thumb-wrap.thumb-wrap--on {
  color: currentColor;
}

.typing {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 22px;
}
.typing .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
  animation: bounce 1.1s infinite ease-in-out;
}
.typing .dot:nth-child(1) {
  animation-delay: 0s;
}
.typing .dot:nth-child(2) {
  animation-delay: 0.12s;
}
.typing .dot:nth-child(3) {
  animation-delay: 0.24s;
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
  padding: 0px 22px 12px 12px;
}
.composer-inner {
  position: relative;
  max-width: var(--chat-content-max, min(790px, calc(100% - 250px)));
  margin: 0 auto;
}

/* 单独圆圈悬浮在输入框正上方，不参与消息区滚动 */
.jump-above-input {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 15px);
  display: flex;
  justify-content: center;
  pointer-events: none;
  z-index: 6;
}
.jump-above-input :deep(.el-tooltip__trigger) {
  pointer-events: auto;
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
  min-height: 108px !important;
  background: var(--bg-elevated) !important;
  color: var(--text-primary);
  border: 1px solid var(--border-subtle);
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

/* 多行输入出现纵向滚动条时，与 .u-scroll 相同的细滚动条交互 */
.composer-input :deep(.el-textarea__inner.u-scroll) {
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}
.composer-input :deep(.el-textarea__inner.u-scroll:hover),
.composer-input :deep(.el-textarea__inner.u-scroll.u-scroll--active) {
  scrollbar-color: #d9d9d9 transparent;
}
.composer-input :deep(.el-textarea__inner.u-scroll::-webkit-scrollbar) {
  width: 5px;
  height: 5px;
}
.composer-input :deep(.el-textarea__inner.u-scroll::-webkit-scrollbar-thumb) {
  background-color: transparent;
  border-radius: 999px;
}
.composer-input :deep(.el-textarea__inner.u-scroll:hover::-webkit-scrollbar-thumb),
.composer-input :deep(.el-textarea__inner.u-scroll.u-scroll--active::-webkit-scrollbar-thumb) {
  background-color: #d9d9d9;
}
.composer-input :deep(.el-textarea__inner.u-scroll::-webkit-scrollbar-track) {
  background: transparent;
}

.main--embedded .composer-input :deep(.el-textarea__inner) {
  height: 120px !important;
  min-height: 120px !important;
  max-height: 120px !important;
  resize: none;
}

.image-fab {
  position: absolute;
  right: 142px;
  bottom: 10px;
  width: 40px;
  height: 40px;
  border: none !important;
  background: var(--bg-elevated) !important;
  color: var(--text-secondary) !important;
  box-shadow: none !important;
}
.mic-fab {
  position: absolute;
  right: 98px;
  bottom: 10px;
  width: 40px;
  height: 40px;
  border: none !important;
  background: var(--bg-elevated) !important;
  color: var(--text-secondary) !important;
  box-shadow: none !important;
}

.attach-fab {
  position: absolute;
  right: 54px;
  bottom: 10px;
  width: 40px;
  height: 40px;
  border: none !important;
  background: var(--bg-elevated) !important;
  color: var(--text-secondary) !important;
  box-shadow: none !important;
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
.msg-toolbar-copy-ic {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  color: #68748b;
}
.msg-toolbar-btn:hover .msg-toolbar-copy-ic {
  color: var(--text-primary);
}
</style>
