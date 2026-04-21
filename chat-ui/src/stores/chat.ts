import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { i18n } from '../i18n'
import * as chatApi from '../api/chatApi'
import type { ChatMessage, SessionSummary } from '../api/types'
import { postSseJsonStream } from '../utils/sseJsonStream'

function t(key: string) {
  return i18n.global.t(key) as string
}

function isDefaultSessionTitle(title: string) {
  const s = title?.trim() ?? ''
  return (
    s === '' ||
    s === t('session.defaultTitle') ||
    /^new chat$/i.test(s) ||
    /^new conversation$/i.test(s)
  )
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionSummary[]>([])
  const activeSessionId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  const loadingSessions = ref(false)
  const loadingMessages = ref(false)
  const sending = ref(false)
  const inputDraft = ref('')

  let abort: AbortController | null = null

  const activeSession = computed(() => sessions.value.find((s) => s.id === activeSessionId.value) ?? null)

  /** 当前为「空的新会话」：默认标题且无消息，禁止再点新建 */
  const isEmptyNewSession = computed(() => {
    const s = activeSession.value
    if (!s || !activeSessionId.value) return false
    return isDefaultSessionTitle(s.title) && messages.value.length === 0
  })

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

  /**
   * 新建会话；若当前已是空的新会话则不再创建。
   * @returns 新建会话，或 `null` 表示已阻止重复创建
   */
  async function createSession(): Promise<SessionSummary | null> {
    if (isEmptyNewSession.value) {
      return null
    }
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

  async function sendStream(
    sessionId: string,
    content: string,
    opts: {
      restartFromUserMessageId?: string | null
      onStart: (assistantMessageId: string) => void
      onDelta: (chunk: string) => void
      onDone: () => void
    },
  ) {
    if (sending.value) return
    sending.value = true
    abort = new AbortController()

    const body: Record<string, unknown> = { sessionId, content }
    if (opts.restartFromUserMessageId) {
      body.restartFromUserMessageId = opts.restartFromUserMessageId
    }

    try {
      await postSseJsonStream(
        '/api/chat/stream',
        body,
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
    isEmptyNewSession,
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
