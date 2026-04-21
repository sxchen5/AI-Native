<script setup lang="ts">
import { Document, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import ChatWorkspace from './ChatWorkspace.vue'
import { useChatStore } from '../stores/chat'
import { useUiStore } from '../stores/ui'
import { renderAiMarkdown } from '../utils/markdown'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const ui = useUiStore()

const messageId = computed(() => (typeof route.query.messageId === 'string' ? route.query.messageId : null))
const canvasDraft = ref('')
const previewTab = ref<'preview' | 'source'>('preview')

function md(html: string) {
  return renderAiMarkdown(html)
}

function syncDraftFromStore() {
  const id = messageId.value
  if (!id) {
    canvasDraft.value = ''
    return
  }
  const msg = chat.messages.find((m) => m.id === id && m.role === 'ASSISTANT')
  canvasDraft.value = msg?.content ?? ''
}

function applyToMessage() {
  const id = messageId.value
  if (!id) return
  chat.updateAssistantMessageContent(id, canvasDraft.value)
  ElMessage.success(t('chat.canvasApplied'))
}

function goBack() {
  void router.push({ name: 'chat' })
}

onMounted(() => {
  ui.enterCanvasLayout()
  syncDraftFromStore()
})

onBeforeUnmount(() => {
  ui.exitCanvasLayout()
})

watch(messageId, () => syncDraftFromStore())
watch(
  () => chat.messages,
  () => syncDraftFromStore(),
  { deep: true },
)
</script>

<template>
  <div class="canvas-shell">
    <section class="chat-pane" aria-label="chat">
      <ChatWorkspace compact />
    </section>
    <aside class="editor-pane" aria-label="canvas">
      <header class="editor-head">
        <div class="head-left">
          <el-button text @click="goBack">
            {{ t('canvas.backToChat') }}
          </el-button>
        </div>
        <div class="head-title">{{ t('canvas.title') }}</div>
        <div class="head-actions">
          <el-button type="primary" size="small" @click="applyToMessage">{{ t('chat.canvasApply') }}</el-button>
        </div>
      </header>
      <div v-if="!messageId" class="editor-empty muted">{{ t('canvas.noMessage') }}</div>
      <template v-else>
        <div class="editor-toolbar">
          <el-radio-group v-model="previewTab" size="small">
            <el-radio-button value="preview">
              <el-icon><View /></el-icon>
              {{ t('canvas.preview') }}
            </el-radio-button>
            <el-radio-button value="source">
              <el-icon><Document /></el-icon>
              {{ t('canvas.markdown') }}
            </el-radio-button>
          </el-radio-group>
        </div>
        <div class="editor-body">
          <el-input
            v-show="previewTab === 'source'"
            v-model="canvasDraft"
            type="textarea"
            class="md-editor"
            :placeholder="t('chat.canvasHint')"
          />
          <div v-show="previewTab === 'preview'" class="preview-wrap">
            <div class="prose-ai preview-md" v-html="md(canvasDraft)" />
          </div>
        </div>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.canvas-shell {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 42%) minmax(0, 1fr);
  background: var(--bg-app);
}

@media (max-width: 960px) {
  .canvas-shell {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(200px, 38vh) minmax(0, 1fr);
  }
}

.chat-pane {
  min-width: 0;
  min-height: 0;
  border-right: 1px solid var(--border-subtle);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

@media (max-width: 960px) {
  .chat-pane {
    border-right: none;
    border-bottom: 1px solid var(--border-subtle);
  }
}

.editor-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--bg-elevated);
}

.editor-head {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.head-left {
  justify-self: start;
}

.head-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

.head-actions {
  justify-self: end;
}

.editor-toolbar {
  padding: 8px 14px;
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.editor-body {
  flex: 1;
  min-height: 0;
  padding: 12px 14px 16px;
  display: flex;
  flex-direction: column;
}

.md-editor {
  flex: 1;
  min-height: 0;
}

.md-editor :deep(.el-textarea__inner) {
  height: 100% !important;
  min-height: 240px !important;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
  border-radius: var(--radius-md);
  background: var(--bg-input-fill) !important;
  border: 1px solid var(--border-subtle);
}

.preview-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-subtle);
  padding: 14px 16px;
  background: var(--bg-chat-panel);
}

.preview-md {
  font-size: 14px;
  line-height: 1.65;
  color: var(--text-primary);
}

.editor-empty {
  padding: 24px;
  text-align: center;
}

.muted {
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
