import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { i18n } from '../i18n'
import * as chatApi from '../api/chatApi'
import type { ChatMessage, SessionSummary } from '../api/types'
import { postSseJsonStream } from '../utils/sseJsonStream'

function t(key: string) {
  return i18n.global.t(key) as string
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionSummary[]>([])
  const activeSessionId = ref<string | null>(null)
  /** 当前展示的消息列表（与 Pinia 中活动会话同步） */
  const messages = ref<ChatMessage[]>([])
  const loadingSessions = ref(false)
  const loadingMessages = ref(false)
  const sending = ref(false)
  const inputDraft = ref('')

  let abort: AbortController | null = null

  const activeSession = computed(() => sessions.value.find((s) => s.id === activeSessionId.value) ?? null)

  async function fetchSessions() {
    loadingSessions.value = true
    try {
      sessions.value = await chatApi.listSessions()
      if (!activeSessionId.value && sessions.value.length > 0) {
        activeSessionId.value = sessions.value[0]!.id
      }
    } catch (e: unknown) {
      throw new Error((e as Error)?.message || t('errors.loadSessions'))
    } finally {
      loadingSessions.value = false
    }
  }

  async function fetchMessages(sessionId: string) {
    loadingMessages.value = true
    try {
      messages.value = await chatApi.listMessages(sessionId)
    } catch (e: unknown) {
      throw new Error((e as Error)?.message || t('errors.loadMessages'))
    } finally {
      loadingMessages.value = false
    }
  }

  function setActiveSession(id: string | null) {
    activeSessionId.value = id
    if (!id) {
      messages.value = []
    }
  }

  async function selectSession(id: string) {
    if (sending.value) {
      throw new Error(t('session.sendingWarn'))
    }
    activeSessionId.value = id
    await fetchMessages(id)
  }

  async function createSession() {
    const s = await chatApi.createSession(t('session.defaultTitle'))
    sessions.value = [s, ...sessions.value.filter((x) => x.id !== s.id)]
    activeSessionId.value = s.id
    messages.value = []
    return s
  }

  async function renameSession(sessionId: string, title: string) {
    await chatApi.renameSession(sessionId, title)
    await fetchSessions()
    activeSessionId.value = sessionId
  }

  async function deleteSession(sessionId: string) {
    await chatApi.deleteSession(sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = null
      messages.value = []
    }
    await fetchSessions()
    if (!activeSessionId.value && sessions.value.length > 0) {
      activeSessionId.value = sessions.value[0]!.id
      await fetchMessages(activeSessionId.value)
    }
  }

  function stopStream() {
    abort?.abort()
    abort = null
    sending.value = false
  }

  /**
   * 发送消息并流式接收；调用方负责 UI 提示（ElMessage）
   * onDelta: 每次增量回调，用于打字机
   */
  async function sendStream(
    sessionId: string,
    content: string,
    opts: {
      onStart: (assistantMessageId: string) => void
      onDelta: (chunk: string) => void
      onDone: () => void
    },
  ) {
    if (sending.value) return
    sending.value = true
    abort = new AbortController()

    try {
      await postSseJsonStream(
        '/api/chat/stream',
        { sessionId, content },
        (evt) => {
          if (evt.type === 'start' && evt.assistantMessageId) {
            opts.onStart(evt.assistantMessageId)
          } else if (evt.type === 'delta' && evt.text) {
            opts.onDelta(evt.text)
          } else if (evt.type === 'error') {
            throw new Error(evt.message || t('errors.send'))
          }
        },
        {
          signal: abort.signal,
          maxRetries: 2,
          retryDelayMs: 800,
        },
      )
      opts.onDone()
    } finally {
      sending.value = false
      abort = null
    }
  }

  return {
    sessions,
    activeSessionId,
    messages,
    loadingSessions,
    loadingMessages,
    sending,
    inputDraft,
    activeSession,
    fetchSessions,
    fetchMessages,
    setActiveSession,
    selectSession,
    createSession,
    renameSession,
    deleteSession,
    stopStream,
    sendStream,
  }
})
