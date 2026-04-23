<script setup lang="ts">
import {
  Close,
  CopyDocument,
  DArrowLeft,
  DArrowRight,
  Document,
  Download,
  Hide,
  View,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import ChatWorkspace from './ChatWorkspace.vue'
import * as chatApi from '../api/chatApi'
import { useChatStore } from '../stores/chat'
import { useUiStore } from '../stores/ui'
import { extractMarkdownToc, firstMarkdownTitle } from '../utils/canvasOutline'
import { renderAiMarkdown } from '../utils/markdown'
import { useScrollbarFade } from '../utils/scrollbarFade'

const CANVAS_CHAT_WIDTH_KEY = 'doubao-canvas-chat-width-px'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const ui = useUiStore()

const shellRef = ref<HTMLElement | null>(null)
const chatPaneWidthPx = ref(420)

const messageId = computed(() => (typeof route.query.messageId === 'string' ? route.query.messageId : null))
const readOnlyCanvas = computed(() => route.query.readOnly === '1' || route.query.readOnly === 'true')
const canvasDraft = ref('')
const lastModified = ref<Date | null>(null)
const tocCollapsed = ref(false)
const previewCollapsed = ref(false)
const previewRef = ref<HTMLElement | null>(null)
const tocInnerRef = ref<HTMLElement | null>(null)
const previewScrollFade = useScrollbarFade(previewRef)
const tocScrollFade = useScrollbarFade(tocInnerRef)

const docTitle = computed(() =>
  firstMarkdownTitle(canvasDraft.value, t('canvas.untitledDoc')),
)

const tocItems = computed(() => extractMarkdownToc(canvasDraft.value))

function md(html: string) {
  return renderAiMarkdown(html)
}

function touchModified() {
  lastModified.value = new Date()
}

function syncDraftFromStore() {
  const id = messageId.value
  if (!id) {
    canvasDraft.value = ''
    lastModified.value = null
    return
  }
  const msg = chat.messages.find((m) => m.id === id && m.role === 'ASSISTANT')
  canvasDraft.value = msg?.content ?? ''
  lastModified.value = new Date()
}

async function applyToMessage() {
  const id = messageId.value
  const sid = chat.activeSessionId
  if (!id || !sid) return
  try {
    const updated = await chatApi.updateDocumentMessage(sid, id, canvasDraft.value)
    chat.messages = chat.messages.map((m) =>
      m.id === id ? { ...m, content: updated.content, metadata: updated.metadata } : m,
    )
    await chat.fetchMessages(sid)
    touchModified()
    ElMessage.success(t('chat.canvasApplied'))
    void router.push({ name: 'chat', params: { sessionId: sid } })
  } catch (e) {
    ElMessage.error((e as Error).message || t('chat.uploadFail'))
  }
}

function goBack() {
  const sid = chat.activeSessionId
  void router.push(sid ? { name: 'chat', params: { sessionId: sid } } : { name: 'chat' })
}

async function copyDoc() {
  try {
    await navigator.clipboard.writeText(canvasDraft.value)
    ElMessage.success(t('session.copied'))
  } catch {
    ElMessage.error(t('session.copyFail'))
  }
}

function downloadMd() {
  const blob = new Blob([canvasDraft.value], { type: 'text/markdown;charset=utf-8' })
  const a = document.createElement('a')
  const safe = docTitle.value.replace(/[/\\?%*:|"<>]/g, '-').slice(0, 80) || 'document'
  a.href = URL.createObjectURL(blob)
  a.download = `${safe}.md`
  a.click()
  URL.revokeObjectURL(a.href)
}

function formatTime(d: Date | null) {
  if (!d) return '—'
  return d.toLocaleString(undefined, { hour: '2-digit', minute: '2-digit' })
}

async function scrollToTocItem(i: number) {
  await nextTick()
  const root = previewRef.value
  if (!root) return
  const headings = root.querySelectorAll('h2, h3')
  const el = headings[i] as HTMLElement | undefined
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function initChatPaneWidth() {
  try {
    const saved = localStorage.getItem(CANVAS_CHAT_WIDTH_KEY)
    if (saved) {
      const n = Number(saved)
      if (!Number.isNaN(n) && n >= 200) {
        chatPaneWidthPx.value = n
        return
      }
    }
  } catch {
    /* ignore */
  }
  const el = shellRef.value
  if (el) {
    chatPaneWidthPx.value = Math.round(el.getBoundingClientRect().width * 0.35)
  }
}

function startResize(e: MouseEvent) {
  e.preventDefault()
  const shell = shellRef.value
  if (!shell) return
  const rect = shell.getBoundingClientRect()
  const minChat = 200
  const minEditor = 280

  const onMove = (ev: MouseEvent) => {
    const w = ev.clientX - rect.left
    const maxChat = rect.width - minEditor
    chatPaneWidthPx.value = Math.min(maxChat, Math.max(minChat, w))
  }
  const onUp = () => {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    try {
      localStorage.setItem(CANVAS_CHAT_WIDTH_KEY, String(Math.round(chatPaneWidthPx.value)))
    } catch {
      /* ignore */
    }
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

onMounted(() => {
  ui.enterCanvasLayout()
  nextTick(() => initChatPaneWidth())
  syncDraftFromStore()
  previewScrollFade.attach()
  tocScrollFade.attach()
})

onBeforeUnmount(() => {
  ui.exitCanvasLayout()
  previewScrollFade.detach()
  tocScrollFade.detach()
})

watch(previewCollapsed, async (collapsed) => {
  await nextTick()
  if (collapsed) {
    previewScrollFade.detach()
  }
  else {
    previewScrollFade.attach()
  }
})

watch(messageId, () => syncDraftFromStore())
watch(
  () => chat.messages,
  () => syncDraftFromStore(),
  { deep: true },
)
</script>

<template>
  <div ref="shellRef" class="canvas-shell">
    <section
      class="chat-pane"
      aria-label="chat"
      :style="{ width: chatPaneWidthPx + 'px', flex: '0 0 auto' }"
    >
      <ChatWorkspace compact />
    </section>
    <div
      class="canvas-resizer"
      role="separator"
      :aria-valuenow="Math.round(chatPaneWidthPx)"
      aria-orientation="vertical"
      aria-label="resize"
      tabindex="0"
      @mousedown="startResize"
    />
    <aside class="editor-pane" aria-label="canvas">
      <header class="doc-toolbar">
        <div class="doc-toolbar-left">
          <div class="doc-title-row">
            <span class="doc-title">{{ messageId ? docTitle : t('canvas.title') }}</span>
          </div>
          <div v-if="messageId" class="doc-meta">
            {{ t('canvas.lastModified') }} {{ formatTime(lastModified) }}
          </div>
        </div>
        <div class="doc-toolbar-right">
          <template v-if="messageId">
            <el-tooltip :content="t('canvas.copy')" placement="bottom">
              <el-button text circle @click="copyDoc">
                <el-icon><CopyDocument /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip :content="t('canvas.download')" placement="bottom">
              <el-button text circle @click="downloadMd">
                <el-icon><Download /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip
              :content="previewCollapsed ? t('canvas.showPreview') : t('canvas.hidePreview')"
              placement="bottom"
            >
              <el-button text circle @click="previewCollapsed = !previewCollapsed">
                <el-icon><Hide v-if="!previewCollapsed" /><View v-else /></el-icon>
              </el-button>
            </el-tooltip>
          </template>
          <el-divider direction="vertical" class="toolbar-divider" />
          <el-tooltip :content="t('canvas.close')" placement="bottom">
            <el-button text circle @click="goBack">
              <el-icon><Close /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </header>

      <div v-if="!messageId" class="editor-empty muted">{{ t('canvas.noMessage') }}</div>

      <div v-else class="editor-workspace">
        <aside class="toc-panel" :class="{ collapsed: tocCollapsed }">
          <button type="button" class="toc-toggle" :aria-label="t('canvas.toggleToc')" @click="tocCollapsed = !tocCollapsed">
            <el-icon><DArrowLeft v-if="!tocCollapsed" /><DArrowRight v-else /></el-icon>
          </button>
          <div ref="tocInnerRef" v-show="!tocCollapsed" class="toc-inner u-scroll">
            <div class="toc-head">{{ t('canvas.outline') }}</div>
            <nav class="toc-nav">
              <button
                v-for="item in tocItems"
                :key="item.index"
                type="button"
                class="toc-link"
                :class="'level-' + item.level"
                @click="scrollToTocItem(item.index)"
              >
                {{ item.text }}
              </button>
              <div v-if="tocItems.length === 0" class="toc-empty muted">{{ t('canvas.noHeadings') }}</div>
            </nav>
          </div>
        </aside>

        <div class="split-editor" :class="{ 'split-editor--single': previewCollapsed }">
          <div class="split-pane split-pane--source">
            <div class="pane-label">
              <el-icon><Document /></el-icon>
              {{ t('canvas.markdown') }}
            </div>
            <el-input
              v-model="canvasDraft"
              type="textarea"
              class="md-editor"
              :placeholder="t('chat.canvasHint')"
              :disabled="readOnlyCanvas"
              @input="touchModified"
            />
          </div>
          <div v-show="!previewCollapsed" class="split-pane split-pane--preview">
            <div class="pane-label">
              <el-icon><View /></el-icon>
              {{ t('canvas.preview') }}
            </div>
            <div ref="previewRef" class="preview-scroll u-scroll">
              <div class="prose-ai markdown-body canvas-doc" v-html="md(canvasDraft)" />
            </div>
          </div>
        </div>
      </div>

      <footer v-if="messageId" class="editor-footer">
        <el-button @click="goBack">{{ t('canvas.backToChat') }}</el-button>
        <el-button v-if="!readOnlyCanvas" type="primary" @click="applyToMessage">{{ t('chat.canvasApply') }}</el-button>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.canvas-shell {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  background: var(--bg-app);
}

@media (max-width: 960px) {
  .canvas-shell {
    flex-direction: column;
  }
  .chat-pane {
    width: 100% !important;
    flex: 0 0 auto !important;
    max-height: 38vh;
  }
  .canvas-resizer {
    display: none;
  }
}

.chat-pane {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--bg-chat-surface);
}

.canvas-resizer {
  flex: 0 0 6px;
  width: 6px;
  cursor: col-resize;
  background: transparent;
  position: relative;
  z-index: 2;
  align-self: stretch;
}

.canvas-resizer:hover,
.canvas-resizer:active {
  background: rgba(59, 108, 255, 0.12);
}

.editor-pane {
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
}

.doc-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
  background: var(--bg-elevated);
}

.doc-toolbar-left {
  min-width: 0;
  flex: 1;
}

.doc-title-row {
  min-width: 0;
}

.doc-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
}

.doc-toolbar-right {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.toolbar-divider {
  margin: 0 6px;
  height: 22px;
}

.editor-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
}

.toc-panel {
  display: flex;
  flex-direction: row;
  flex-shrink: 0;
  border-right: 1px solid var(--border-subtle);
  background: var(--bg-sidebar);
  transition: width 0.25s var(--ease-out, ease);
}

.toc-panel:not(.collapsed) {
  width: min(200px, 28%);
}

.toc-panel.collapsed {
  width: 36px;
}

.toc-toggle {
  flex-shrink: 0;
  width: 36px;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--text-muted);
  display: grid;
  place-items: center;
  align-self: flex-start;
  padding: 10px 0;
}

.toc-toggle:hover {
  color: var(--accent);
}

.toc-inner {
  flex: 1;
  min-width: 0;
  padding: 10px 10px 12px 0;
  overflow-y: auto;
}

.toc-head {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 8px;
}

.toc-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.toc-link {
  border: none;
  background: transparent;
  text-align: left;
  font: inherit;
  font-size: 12px;
  line-height: 1.35;
  color: var(--text-secondary);
  padding: 6px 4px;
  border-radius: 6px;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.toc-link:hover {
  background: rgba(59, 108, 255, 0.08);
  color: var(--accent);
}

.toc-link.level-3 {
  padding-left: 12px;
  font-size: 11px;
}

.toc-empty {
  font-size: 12px;
  padding: 8px 0;
}

.split-editor {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 0;
}

.split-editor--single {
  grid-template-columns: 1fr;
}

@media (max-width: 1100px) {
  .split-editor:not(.split-editor--single) {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(160px, 38%) minmax(0, 1fr);
  }
}

.split-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

.split-pane--source {
  border-right: 1px solid var(--border-subtle);
}

.split-editor--single .split-pane--source {
  border-right: none;
}

@media (max-width: 1100px) {
  .split-editor:not(.split-editor--single) .split-pane--source {
    border-right: none;
    border-bottom: 1px solid var(--border-subtle);
  }
}

.pane-label {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-input-fill);
}

.md-editor {
  flex: 1;
  min-height: 0;
  padding: 0 8px 8px;
}

.md-editor :deep(.el-textarea__inner) {
  height: 100% !important;
  min-height: 120px !important;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
  border-radius: 8px;
  background: var(--bg-input-fill) !important;
  border: 1px solid var(--border-subtle);
  box-shadow: none;
}

.preview-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 16px 20px;
}

.canvas-doc {
  max-width: 720px;
  margin: 0 auto;
  font-size: 14px;
  line-height: 1.65;
  color: var(--text-primary);
}

.canvas-doc :deep(h1) {
  font-size: 1.65rem;
  font-weight: 800;
  margin: 0 0 0.75rem;
  letter-spacing: -0.02em;
}

.canvas-doc :deep(h2),
.canvas-doc :deep(h3) {
  scroll-margin-top: 12px;
}

.editor-footer {
  flex-shrink: 0;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 10px 16px;
  border-top: 1px solid var(--border-subtle);
  background: var(--bg-sidebar);
}

.editor-empty {
  flex: 1;
  display: grid;
  place-content: center;
  padding: 24px;
}

.muted {
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
