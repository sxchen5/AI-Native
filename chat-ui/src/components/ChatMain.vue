<script setup lang="ts">
import { Promotion, DocumentCopy, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import type { ChatMessage } from '../api/types'
import { useChatStore } from '../stores/chat'
import { renderAiMarkdown } from '../utils/markdown'

const { t } = useI18n()
const chat = useChatStore()

const bottomAnchor = ref<HTMLElement | null>(null)

function md(html: string) {
  return renderAiMarkdown(html)
}

async function scrollToBottom(smooth = true) {
  await nextTick()
  bottomAnchor.value?.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'end' })
}

watch(
  () => chat.messages,
  () => scrollToBottom(true),
  { deep: true },
)

watch(
  () => chat.activeSessionId,
  () => scrollToBottom(false),
)

async function copyAssistant(text: string) {
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
  await scrollToBottom(false)

  let assistantId = ''
  let assistantContent = ''

  const assistantPlaceholder: ChatMessage = {
    id: `local-ai-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    createdAt: new Date().toISOString(),
  }
  chat.messages = [...chat.messages, assistantPlaceholder]

  try {
    await chat.sendStream(sid, text, {
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
        await scrollToBottom(true)
      },
    })
  } catch (e: unknown) {
    if ((e as Error).name === 'AbortError') {
      ElMessage.info(t('errors.stopped'))
    } else {
      ElMessage.error((e as Error).message || t('errors.send'))
    }
    try {
      await chat.fetchMessages(sid)
    } catch {
      /* ignore */
    }
  }
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
          v-for="m in chat.messages"
          :key="m.id"
          class="row"
          :class="m.role === 'USER' ? 'end' : 'start'"
        >
          <div class="msg-animate bubble-wrap" :class="m.role === 'USER' ? 'user' : 'ai'">
            <div v-if="m.role === 'ASSISTANT'" class="ai-label">{{ t('chat.assistant') }}</div>
            <div v-if="m.role === 'USER'" class="bubble user">{{ m.content }}</div>
            <div v-else class="bubble ai">
              <div v-if="m.content.length === 0 && chat.sending" class="typing">
                <el-icon class="spin"><Loading /></el-icon>
                <span class="dot" /><span class="dot" /><span class="dot" />
              </div>
              <div v-else class="prose-ai" v-html="md(m.content)" />
            </div>
            <div class="meta">
              <time>{{ new Date(m.createdAt).toLocaleString() }}</time>
              <el-button
                v-if="m.role === 'ASSISTANT' && m.content"
                text
                size="small"
                class="copy-btn"
                @click="copyAssistant(m.content)"
              >
                <el-icon><DocumentCopy /></el-icon>
                {{ t('session.copy') }}
              </el-button>
            </div>
          </div>
        </div>
        <div ref="bottomAnchor" class="anchor" />
      </div>
    </div>

    <footer class="composer">
      <el-input
        v-model="chat.inputDraft"
        type="textarea"
        :rows="3"
        resize="none"
        :placeholder="t('chat.inputPlaceholder')"
        :disabled="!chat.activeSessionId || chat.sending"
        @keydown="onKeydown"
      />
      <div class="composer-actions">
        <el-button v-if="chat.sending" @click="chat.stopStream()">{{ t('chat.stop') }}</el-button>
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="chat.sending"
          :disabled="!chat.activeSessionId || !chat.inputDraft.trim()"
          @click="onSend"
        >
          {{ t('chat.send') }}
        </el-button>
      </div>
    </footer>
  </main>
</template>

<style scoped>
.main {
  display: grid;
  grid-template-rows: 1fr auto;
  min-width: 0;
  min-height: 0;
  background: var(--bg-app);
  transition: background 0.35s ease;
}

.msg-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: smooth;
  padding: 20px 66px 62px;
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
.bubble-wrap.user .bubble {
  border-radius: 16px 16px 4px 16px;
}
.bubble-wrap.ai .bubble {
  border-radius: 16px 16px 16px 4px;
}

.ai-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.bubble {
  padding: 12px 14px;
  line-height: 1.55;
  font-size: 14px;
  border: 1px solid transparent;
  box-shadow: var(--shadow-sm);
  transition:
    background 0.35s ease,
    color 0.35s ease,
    border-color 0.35s ease;
}
.bubble.user {
  background: var(--bg-bubble-user);
  color: #fff;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.ai {
  background: var(--bg-bubble-ai);
  color: var(--text-primary);
  border-color: var(--border-subtle);
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-muted);
}

.copy-btn {
  padding: 0 4px;
  height: auto;
  font-size: 11px;
  color: var(--accent);
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
  background: var(--bg-elevated);
  padding: 0px 72px 12px 62px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  align-items: end;
  box-shadow: 0 -4px 24px rgba(15, 23, 42, 0.06);
  transition:
    background 0.35s ease,
    border-color 0.35s ease;
}

.composer-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.composer :deep(.el-textarea__inner) {
  border-radius: var(--radius-md, 10px);
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}
</style>
