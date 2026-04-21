<script setup lang="ts">
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'

import AppHeader from './AppHeader.vue'
import ChatMain from './ChatMain.vue'
import SessionSidebar from './SessionSidebar.vue'
import { useChatStore } from '../stores/chat'
import { useUiStore } from '../stores/ui'

const { t } = useI18n()
const chat = useChatStore()
const ui = useUiStore()

onMounted(async () => {
  try {
    await chat.fetchSessions()
    if (chat.activeSessionId) {
      await chat.fetchMessages(chat.activeSessionId)
    }
  } catch (e: unknown) {
    ElMessage.error((e as Error).message || t('errors.loadSessions'))
  }
})
</script>

<template>
  <div class="shell">
    <AppHeader />
    <div class="body" :class="{ 'sidebar-collapsed': ui.sidebarCollapsed }">
      <SessionSidebar class="sidebar-pane" />
      <ChatMain class="chat-pane" />
    </div>
  </div>
</template>

<style scoped>
.shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 280px 1fr;
  transition: grid-template-columns 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}

.body.sidebar-collapsed {
  grid-template-columns: 56px 1fr;
}

@media (max-width: 900px) {
  .body,
  .body.sidebar-collapsed {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
  .sidebar-pane {
    max-height: 220px;
    border-right: none;
    border-bottom: 1px solid var(--border-subtle);
  }
  .body.sidebar-collapsed .sidebar-pane {
    max-height: 120px;
  }
}

.chat-pane {
  min-width: 0;
  min-height: 0;
}
</style>
