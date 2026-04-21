<script setup lang="ts">
import { Expand, Fold, Plus, EditPen, Delete, MoreFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

import type { SessionSummary } from '../api/types'
import { useChatStore } from '../stores/chat'
import { useUiStore } from '../stores/ui'

const { t } = useI18n()
const chat = useChatStore()
const ui = useUiStore()

function sortedSessions() {
  return [...chat.sessions].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
}

async function onNew() {
  try {
    const created = await chat.createSession()
    if (created === null) {
      ElMessage.info(t('session.alreadyEmpty'))
    }
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
  <aside class="sidebar" :class="{ collapsed: ui.sidebarCollapsed }">
    <div class="toolbar">
      <el-tooltip :content="ui.sidebarCollapsed ? t('session.expand') : t('session.collapse')" placement="right">
        <el-button circle size="small" class="fold-btn" @click="ui.toggleSidebar">
          <el-icon><Fold v-if="!ui.sidebarCollapsed" /><Expand v-else /></el-icon>
        </el-button>
      </el-tooltip>
      <el-button v-show="!ui.sidebarCollapsed" type="primary" class="new-btn" :icon="Plus" @click="onNew">
        {{ t('session.new') }}
      </el-button>
    </div>
    <el-scrollbar v-show="!ui.sidebarCollapsed" class="scroll">
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
    <div v-show="ui.sidebarCollapsed" class="collapsed-rail">
      <el-tooltip :content="t('session.new')" placement="right">
        <el-button type="primary" circle size="small" class="rail-new" :icon="Plus" @click="onNew" />
      </el-tooltip>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-subtle);
  transition:
    width 0.32s cubic-bezier(0.22, 1, 0.36, 1),
    background 0.35s ease,
    border-color 0.35s ease;
  width: 280px;
  min-width: 280px;
  max-width: 280px;
}

.sidebar.collapsed {
  width: 56px;
  min-width: 56px;
  max-width: 56px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 10px 8px;
  flex-wrap: wrap;
}

.fold-btn {
  border: 1px solid var(--border-subtle);
  background: var(--bg-elevated);
  color: var(--text-secondary);
}

.new-btn {
  flex: 1;
  min-width: 0;
  height: 36px;
  border-radius: var(--radius-md, 10px);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.scroll {
  flex: 1;
  padding: 4px 8px 12px;
  min-height: 0;
}

.collapsed-rail {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0 12px;
  gap: 10px;
}

.rail-new {
  box-shadow: var(--shadow-sm);
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
