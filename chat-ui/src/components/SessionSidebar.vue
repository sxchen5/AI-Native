<script setup lang="ts">
import * as Icons from '@element-plus/icons-vue'
import {
  ChatDotRound,
  Expand,
  Fold,
  Plus,
  EditPen,
  Delete,
  MoreFilled,
  Setting,
  SwitchButton,
  Moon,
  Sunny,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { SessionSummary } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useLocaleStore, type AppLocale } from '../stores/locale'
import { useProfileStore, AVATAR_PRESETS } from '../stores/profile'
import { useThemeStore } from '../stores/theme'
import { useUiStore } from '../stores/ui'

const { t } = useI18n()
const chat = useChatStore()
const ui = useUiStore()
const auth = useAuthStore()
const locale = useLocaleStore()
const theme = useThemeStore()
const profile = useProfileStore()

const userAvatarIcon = computed(() => resolvePresetIcon(profile.currentAvatar.iconName))

function resolvePresetIcon(name: string) {
  const comp = (Icons as Record<string, unknown>)[name]
  return (comp as typeof Icons.UserFilled) || Icons.UserFilled
}

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

async function onLogout() {
  try {
    await auth.logout()
    ElMessage.success(t('header.logout'))
  } catch {
    await auth.refreshMe()
  }
}
</script>

<template>
  <aside class="sidebar" :class="{ collapsed: ui.sidebarCollapsedEffective }">
    <!-- 顶部：品牌 + 收起在会话列表右上方 -->
    <div class="sidebar-top">
      <div class="brand" v-show="!ui.sidebarCollapsedEffective">
        <div class="logo" aria-hidden="true">
          <el-icon :size="20"><ChatDotRound /></el-icon>
        </div>
        <div class="brand-text">
          <span class="brand-name">{{ t('app.name') }}</span>
        </div>
      </div>
      <div v-show="ui.sidebarCollapsedEffective" class="brand-mini">
        <div class="logo small" aria-hidden="true">
          <el-icon :size="18"><ChatDotRound /></el-icon>
        </div>
      </div>
      <el-tooltip :content="ui.sidebarCollapsedEffective ? t('session.expand') : t('session.collapse')" placement="bottom">
        <el-button
          circle
          size="small"
          class="fold-btn"
          :disabled="ui.forceSidebarCollapsed"
          @click="ui.toggleSidebar"
        >
          <el-icon><Fold v-if="!ui.sidebarCollapsedEffective" /><Expand v-else /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <el-button v-show="!ui.sidebarCollapsedEffective" type="primary" class="new-btn" :icon="Plus" block @click="onNew">
      {{ t('session.new') }}
    </el-button>

    <el-scrollbar v-show="!ui.sidebarCollapsedEffective" class="scroll">
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

    <div v-show="ui.sidebarCollapsedEffective" class="collapsed-rail">
      <el-tooltip :content="t('session.new')" placement="right">
        <el-button type="primary" circle size="small" class="rail-new" :icon="Plus" @click="onNew" />
      </el-tooltip>
    </div>

    <!-- 底部：用户名一行 + 设置 / 退出（语言与主题在设置里） -->
    <div v-show="!ui.sidebarCollapsedEffective" class="sidebar-footer">
      <button type="button" class="user-line" @click="ui.openSettings">
        <span class="user-avatar" :style="{ background: profile.currentAvatar.color }" aria-hidden="true">
          <el-icon :size="18"><component :is="userAvatarIcon" /></el-icon>
        </span>
        <span class="user-name">{{ auth.username || '—' }}</span>
        <el-icon class="user-settings-icon"><Setting /></el-icon>
      </button>
    </div>
    <div v-show="ui.sidebarCollapsedEffective" class="sidebar-footer mini">
      <el-tooltip :content="t('settings.title')" placement="right">
        <el-button circle size="small" @click="ui.openSettings">
          <el-icon><Setting /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <el-dialog v-model="ui.settingsOpen" :title="t('settings.title')" width="360px" @closed="ui.closeSettings">
      <div class="settings-block">
        <div class="settings-label">{{ t('settings.avatar') }}</div>
        <p class="settings-hint">{{ t('settings.avatarHint') }}</p>
        <div class="avatar-grid">
          <button
            v-for="opt in AVATAR_PRESETS"
            :key="opt.id"
            type="button"
            class="avatar-pick"
            :class="{ active: profile.avatarId === opt.id }"
            :style="{ background: opt.color }"
            :aria-label="opt.id"
            @click="profile.setAvatarId(opt.id)"
          >
            <el-icon :size="20">
              <component :is="resolvePresetIcon(opt.iconName)" />
            </el-icon>
          </button>
        </div>
      </div>
      <div class="settings-block">
        <div class="settings-label">{{ t('settings.language') }}</div>
        <el-radio-group :model-value="locale.locale" size="small" @change="(v: string) => locale.setLocale(v as AppLocale)">
          <el-radio-button label="zh-CN">{{ t('header.langZh') }}</el-radio-button>
          <el-radio-button label="en-US">{{ t('header.langEn') }}</el-radio-button>
        </el-radio-group>
      </div>
      <div class="settings-block">
        <div class="settings-label">{{ t('settings.theme') }}</div>
        <el-radio-group :model-value="theme.mode" size="small" @change="(v: string) => theme.setMode(v as 'light' | 'dark')">
          <el-radio-button label="light">
            <el-icon><Sunny /></el-icon>
            {{ t('header.themeLight') }}
          </el-radio-button>
          <el-radio-button label="dark">
            <el-icon><Moon /></el-icon>
            {{ t('header.themeDark') }}
          </el-radio-button>
        </el-radio-group>
      </div>
      <div class="settings-block settings-logout">
        <el-button class="logout-in-settings" @click="onLogout">
          <el-icon><SwitchButton /></el-icon>
          {{ t('header.logout') }}
        </el-button>
      </div>
    </el-dialog>
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

.sidebar-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 10px 8px;
  min-height: 48px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.brand-mini {
  flex: 1;
  display: flex;
  justify-content: center;
}

.logo {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--bg-bubble-user);
  color: #fff;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.logo.small {
  width: 32px;
  height: 32px;
  border-radius: 9px;
}

.brand-name {
  font-weight: 700;
  font-size: 14px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fold-btn {
  flex-shrink: 0;
  border: 1px solid var(--border-subtle);
  background: var(--bg-elevated);
  color: var(--text-secondary);
}

.new-btn {
  margin: 0 10px 8px;
  height: 38px;
  border-radius: 10px;
  font-weight: 600;
}

.scroll {
  flex: 1;
  padding: 4px 8px 8px;
  min-height: 0;
}

.collapsed-rail {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0;
  gap: 10px;
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
  border-radius: 10px;
  border: 1px solid transparent;
  background: var(--bg-elevated);
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}
.item:hover {
  border-color: var(--border-subtle);
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
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-time {
  font-size: 10px;
  color: var(--text-muted);
}

.more {
  color: var(--text-muted);
}

.sidebar-footer {
  border-top: 1px solid var(--border-subtle);
  padding: 10px 10px 12px;
  background: var(--bg-sidebar);
}
.sidebar-footer.mini {
  display: flex;
  justify-content: center;
  padding: 8px 0 10px;
}

.user-line {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  background: var(--bg-elevated);
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: box-shadow 0.2s ease;
}
.user-line:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.user-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  color: #fff;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.user-settings-icon {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 18px;
}

.settings-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.45;
}

.avatar-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.avatar-pick {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  display: grid;
  place-items: center;
  color: #fff;
  padding: 0;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.avatar-pick:hover {
  transform: scale(1.06);
}

.avatar-pick.active {
  border-color: var(--text-primary);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.settings-logout {
  margin-bottom: 0;
  margin-top: 8px;
}

.logout-in-settings {
  width: 100%;
  justify-content: center;
}

.muted {
  color: var(--text-secondary);
  font-size: 13px;
}
.pad {
  padding: 12px 10px;
}

.settings-block {
  margin-bottom: 16px;
}
.settings-label {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--text-primary);
}
</style>
