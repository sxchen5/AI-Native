import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { i18n } from '../i18n'
import * as chatApi from '../api/chatApi'
import type { ChatMessage, SessionSummary } from '../api/types'
import { postSseJsonStream } from '../utils/sseJsonStream'

function t(key: string) {
  return i18n.global.t(key) as string
}

const SESSION_PAGE = 30

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionSummary[]>([])
  const sessionsHasMore = ref(false)
  const loadingMoreSessions = ref(false)
  const activeSessionId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  const loadingSessions = ref(false)
  const loadingMessages = ref(false)
  const sending = ref(false)
  const inputDraft = ref('')

  let abort: AbortController | null = null

  const activeSession = computed(() => sessions.value.find((s) => s.id === activeSessionId.value) ?? null)

  /** 重新拉取已加载数量的会话（至少一页），用于初始化或流式结束后刷新标题 */
  async function fetchSessions() {
    loadingSessions.value = true
    try {
      const want = Math.max(SESSION_PAGE, sessions.value.length)
      const merged: SessionSummary[] = []
      let off = 0
      let lastHasMore = false
      while (merged.length < want) {
        const { items, hasMore } = await chatApi.listSessions(off, SESSION_PAGE)
        lastHasMore = hasMore
        merged.push(...items)
        off += items.length
        if (!hasMore || items.length === 0) break
      }
      sessions.value = merged
      sessionsHasMore.value = lastHasMore
      if (!activeSessionId.value && sessions.value.length > 0) {
        activeSessionId.value = sessions.value[0]!.id
      }
    } catch (e: unknown) {
      throw new Error((e as Error)?.message || t('errors.loadSessions'))
    } finally {
      loadingSessions.value = false
    }
  }

  async function loadMoreSessions() {
    if (!sessionsHasMore.value || loadingMoreSessions.value || loadingSessions.value) return
    loadingMoreSessions.value = true
    try {
      const { items, hasMore } = await chatApi.listSessions(sessions.value.length, SESSION_PAGE)
      const seen = new Set(sessions.value.map((s) => s.id))
      for (const s of items) {
        if (!seen.has(s.id)) {
          sessions.value.push(s)
          seen.add(s.id)
        }
      }
      sessionsHasMore.value = hasMore
    } catch (e: unknown) {
      throw new Error((e as Error)?.message || t('errors.loadSessions'))
    } finally {
      loadingMoreSessions.value = false
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

  async function createSession(): Promise<SessionSummary> {
    const s = await chatApi.createSession(t('session.defaultTitle'))
    sessions.value = [s, ...sessions.value.filter((x) => x.id !== s.id)]
    activeSessionId.value = s.id
    messages.value = []
    return s
  }

  async function renameSession(sessionId: string, title: string) {
    await chatApi.renameSession(sessionId, title)
    sessions.value = sessions.value.map((s) => (s.id === sessionId ? { ...s, title } : s))
    activeSessionId.value = sessionId
  }

  function updateAssistantMessageContent(messageId: string, content: string, metadata?: string | null) {
    messages.value = messages.value.map((m) =>
      m.id === messageId ? { ...m, content, ...(metadata !== undefined ? { metadata } : {}) } : m,
    )
  }

  function patchMessageMetadata(messageId: string, metadata: string | null) {
    messages.value = messages.value.map((m) => (m.id === messageId ? { ...m, metadata } : m))
  }

  async function deleteSession(sessionId: string) {
    await chatApi.deleteSession(sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = null
      messages.value = []
    }
    sessions.value = sessions.value.filter((s) => s.id !== sessionId)
    if (sessions.value.length === 0) {
      sessionsHasMore.value = false
    }
    if (!activeSessionId.value && sessions.value.length > 0) {
      activeSessionId.value = sessions.value[0]!.id
      await fetchMessages(activeSessionId.value)
    } else if (!activeSessionId.value) {
      await fetchSessions()
      if (sessions.value.length > 0) {
        activeSessionId.value = sessions.value[0]!.id
        await fetchMessages(activeSessionId.value)
      }
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
      appendAfterUserMessageId?: string | null
      modelContextJson?: string | null
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
    if (opts.appendAfterUserMessageId) {
      body.appendAfterUserMessageId = opts.appendAfterUserMessageId
    }
    if (opts.modelContextJson) {
      body.modelContextJson = opts.modelContextJson
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
    sessionsHasMore,
    loadingMoreSessions,
    activeSessionId,
    messages,
    loadingSessions,
    loadingMessages,
    sending,
    inputDraft,
    activeSession,
    fetchSessions,
    loadMoreSessions,
    fetchMessages,
    setActiveSession,
    selectSession,
    createSession,
    renameSession,
    deleteSession,
    updateAssistantMessageContent,
    patchMessageMetadata,
    stopStream,
    sendStream,
  }
})
