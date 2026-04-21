<script setup lang="ts">
import { Plus, EditPen, Delete, MoreFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

import type { SessionSummary } from '../api/types'
import { useChatStore } from '../stores/chat'

const { t } = useI18n()
const chat = useChatStore()

function sortedSessions() {
  return [...chat.sessions].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
}

async function onNew() {
  try {
    await chat.createSession()
  } catch (e: unknown) {
    ElMessage.error((e as Error).message || t('errors.createSession'))
  }
}

async function onSelect(s: SessionSummary) {
  try {
    await chat.selectSession(s.id)
  } catch (e: unknown) {
    ElMessage.warning((e as Error).message)
  }
}

async function onRename(s: SessionSummary) {
  try {
    const { value } = await ElMessageBox.prompt(t('session.renamePlaceholder'), t('session.renameTitle'), {
      inputValue: s.title,
      confirmButtonText: t('session.save'),
      cancelButtonText: t('session.cancel'),
      inputPattern: /\S+/,
      inputErrorMessage: t('session.titleRequired'),
    })
    await chat.renameSession(s.id, value)
  } catch {
    /* cancel */
  }
}

async function onDelete(s: SessionSummary) {
  try {
    await ElMessageBox.confirm(t('session.deleteConfirm', { title: s.title }), t('session.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('session.confirmDelete'),
      cancelButtonText: t('session.cancel'),
    })
    await chat.deleteSession(s.id)
  } catch {
    /* cancel */
  }
}
</script>

<template>
  <aside class="sidebar">
    <el-button type="primary" class="new-btn" :icon="Plus" @click="onNew">{{ t('session.new') }}</el-button>
    <el-scrollbar class="scroll">
      <div v-if="chat.loadingSessions" class="muted pad">{{ t('session.loading') }}</div>
      <div v-else-if="chat.sessions.length === 0" class="muted pad">{{ t('session.empty') }}</div>
      <div v-else class="list">
        <div
          v-for="s in sortedSessions()"
          :key="s.id"
          class="item"
          :class="{ active: s.id === chat.activeSessionId }"
          @click="onSelect(s)"
        >
          <div class="item-main">
            <span class="item-title">{{ s.title }}</span>
            <span class="item-time">{{ new Date(s.updatedAt).toLocaleString() }}</span>
          </div>
          <el-dropdown trigger="click" @command="(cmd: string) => (cmd === 'rename' ? onRename(s) : onDelete(s))">
            <el-button text class="more" @click.stop>
              <el-icon><MoreFilled /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">
                  <el-icon><EditPen /></el-icon>
                  {{ t('session.rename') }}
                </el-dropdown-item>
                <el-dropdown-item command="delete" divided>
                  <el-icon><Delete /></el-icon>
                  {{ t('session.delete') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </el-scrollbar>
  </aside>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-subtle);
  transition:
    background 0.35s ease,
    border-color 0.35s ease;
}

.new-btn {
  margin: 14px 12px 10px;
  width: calc(100% - 24px);
  height: 40px;
  border-radius: var(--radius-md, 10px);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.scroll {
  flex: 1;
  padding: 4px 8px 12px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 10px;
  border-radius: var(--radius-md, 10px);
  border: 1px solid transparent;
  background: var(--bg-elevated);
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition:
    border-color 0.25s ease,
    box-shadow 0.25s ease,
    transform 0.2s ease;
}
.item:hover {
  border-color: var(--border-subtle);
  box-shadow: var(--shadow-md);
}
.item.active {
  border-color: var(--accent-soft);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.item-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.item-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-time {
  font-size: 11px;
  color: var(--text-muted);
}

.more {
  color: var(--text-muted);
}

.muted {
  color: var(--text-secondary);
  font-size: 13px;
}
.pad {
  padding: 12px 10px;
}
</style>
