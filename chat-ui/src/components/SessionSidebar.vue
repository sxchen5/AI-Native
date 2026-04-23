<script setup lang="ts">
import * as Icons from '@element-plus/icons-vue'
import {
  Expand,
  Fold,
  EditPen,
  Delete,
  MoreFilled,
  Setting,
  SwitchButton,
  Moon,
  Sunny,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import type { SessionSummary } from '../api/types'
import IconAppFavicon from './icons/IconAppFavicon.vue'
import IconSessionBubble from './icons/IconSessionBubble.vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useLocaleStore, type AppLocale } from '../stores/locale'
import { useProfileStore, AVATAR_PRESETS } from '../stores/profile'
import { useThemeStore } from '../stores/theme'
import { useUiStore } from '../stores/ui'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const ui = useUiStore()
const auth = useAuthStore()
const locale = useLocaleStore()
const theme = useThemeStore()
const profile = useProfileStore()

const histScrollEl = ref<HTMLElement | null>(null)
/** 历史标题是否被省略，仅此时显示完整标题 tooltip */
const sessionTitleTruncated = reactive<Record<string, boolean>>({})
const sessionTitleResizeObservers = new Map<string, ResizeObserver>()
/** 避免 v-for 的 ref 回调在每次 patch 重复执行时反复改 reactive 导致渲染死循环 */
const sessionTitleLastEl = new Map<string, HTMLElement | null>()

function bindSessionTitleEl(sessionId: string, el: unknown) {
  const node = (el as HTMLElement | null) ?? null
  if (sessionTitleLastEl.get(sessionId) === node) {
    return
  }
  sessionTitleLastEl.set(sessionId, node)

  sessionTitleResizeObservers.get(sessionId)?.disconnect()
  sessionTitleResizeObservers.delete(sessionId)

  if (!node) {
    delete sessionTitleTruncated[sessionId]
    return
  }

  const update = () => {
    const next = node.scrollWidth > node.clientWidth + 1
    if (sessionTitleTruncated[sessionId] !== next) {
      sessionTitleTruncated[sessionId] = next
    }
  }
  const ro = new ResizeObserver(() => update())
  ro.observe(node)
  sessionTitleResizeObservers.set(sessionId, ro)
  void nextTick(() => update())
}

const userAvatarIcon = computed(() => resolvePresetIcon(profile.currentAvatar.iconName))

function resolvePresetIcon(name: string) {
  const comp = (Icons as Record<string, unknown>)[name]
  return (comp as typeof Icons.UserFilled) || Icons.UserFilled
}

async function onNew() {
  const draftEmpty = !chat.activeSessionId && chat.messages.length === 0
  const sessionEmpty = !!chat.activeSessionId && chat.messages.length === 0
  if (draftEmpty || sessionEmpty) {
    ElMessage.info(t('session.alreadyEmpty'))
    return
  }
  chat.setActiveSession(null)
  if (route.name === 'chat') {
    const sid = typeof route.params.sessionId === 'string' ? route.params.sessionId : ''
    if (sid) {
      void router.replace({ name: 'chat' })
    }
  }
  await nextTick()
  histScrollEl.value?.scrollTo({ top: 0, behavior: 'smooth' })
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

async function onHistScroll() {
  const el = histScrollEl.value
  if (!el || chat.loadingMoreSessions || !chat.sessionsHasMore) return
  el.classList.add('u-scroll--active')
  const nearBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 72
  if (nearBottom) {
    try {
      await chat.loadMoreSessions()
    } catch (e: unknown) {
      ElMessage.error((e as Error).message || t('errors.loadSessions'))
    }
  }
}

function onWinKey(e: KeyboardEvent) {
  if (!e.ctrlKey || e.metaKey) return
  if (e.key !== 'k' && e.key !== 'K') return
  if (ui.sidebarCollapsedEffective) return
  e.preventDefault()
  void onNew()
}

onMounted(() => {
  window.addEventListener('keydown', onWinKey)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWinKey)
  for (const ro of sessionTitleResizeObservers.values()) {
    ro.disconnect()
  }
  sessionTitleResizeObservers.clear()
  sessionTitleLastEl.clear()
})

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
    <div class="sidebar-top">
      <div class="brand" v-show="!ui.sidebarCollapsedEffective">
        <div class="logo logo--favicon" aria-hidden="true">
          <IconAppFavicon />
        </div>
        <div class="brand-text">
          <span class="brand-name">{{ t('app.name') }}</span>
        </div>
      </div>
      <div v-show="ui.sidebarCollapsedEffective" class="brand-mini">
        <div class="logo logo--favicon small" aria-hidden="true">
          <IconAppFavicon small />
        </div>
      </div>
      <el-tooltip hide-after="0" :content="ui.sidebarCollapsedEffective ? t('session.expand') : t('session.collapse')" placement="bottom">
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

    <div v-show="!ui.sidebarCollapsedEffective" class="sidebar-main">
      <button type="button" class="new-chat-pill" @click="onNew">
        <span class="new-chat-icon" aria-hidden="true">
          <el-icon :size="18"><EditPen /></el-icon>
        </span>
        <span class="new-chat-label">{{ t('session.new') }}</span>
        <span class="new-chat-shortcut" aria-hidden="true">
          <kbd class="kbd">Ctrl</kbd><kbd class="kbd">K</kbd>
        </span>
      </button>

      <div class="hist-head">{{ t('session.historyTitle') }}</div>

      <div ref="histScrollEl" class="hist-scroll u-scroll" @scroll.passive="onHistScroll">
        <div v-if="chat.loadingSessions" class="muted pad">{{ t('session.loading') }}</div>
        <div v-else-if="chat.sessions.length === 0" class="muted pad">{{ t('session.empty') }}</div>
        <div v-else class="list">
          <el-tooltip
            v-for="s in chat.sessions"
            :key="s.id"
            hide-after="0"
            placement="right"
            :disabled="!sessionTitleTruncated[s.id]"
            :content="s.title"
          >
            <div
              class="item"
              :class="{ active: s.id === chat.activeSessionId }"
              @click="onSelect(s)"
            >
            <span class="item-msg-icon" aria-hidden="true">
              <IconSessionBubble class="item-msg-svg" />
            </span>
            <div class="item-main">
              <span
                :ref="(el) => bindSessionTitleEl(s.id, el)"
                class="item-title"
              >{{ s.title }}</span>
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
          </el-tooltip>
          <div v-if="chat.loadingMoreSessions" class="muted load-more">{{ t('session.loadingMore') }}</div>
        </div>
      </div>
    </div>

    <div v-show="ui.sidebarCollapsedEffective" class="collapsed-rail">
      <el-tooltip hide-after="0" :content="t('session.new')" placement="right">
        <el-button type="primary" circle size="small" class="rail-new" @click="onNew">
          <el-icon><EditPen /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

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
      <el-tooltip hide-after="0" :content="t('settings.title')" placement="right">
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
  min-height: 0;
  height: 100%;
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
  flex-shrink: 0;
}

.sidebar-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 10px;
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
.logo--favicon {
  background: var(--bg-elevated);
  border: 1px solid var(--border-subtle);
}
.logo.small {
  width: 32px;
  height: 32px;
  border-radius: 9px;
}

.brand-name {
  font-weight: 700;
  font-size: 18px;
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

.new-chat-pill {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px;
  margin-top: 10px;
  margin-bottom: 10px;
  border-radius: 12px;
  border: 1px solid var(--accent-soft);
  background: linear-gradient(180deg, rgba(59, 108, 255, 0.08) 0%, rgba(59, 108, 255, 0.03) 100%);
  color: var(--accent);
  font: inherit;
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
  transition:
    box-shadow 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease;
  flex-shrink: 0;
}
.new-chat-pill:hover {
  border-color: var(--accent);
  box-shadow: 0 4px 14px rgba(59, 108, 255, 0.12);
}
html.dark .new-chat-pill {
  background: rgba(122, 162, 255, 0.1);
  border-color: var(--accent-soft);
}

.new-chat-icon {
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.new-chat-label {
  flex: 1;
  text-align: left;
  min-width: 0;
}

.new-chat-shortcut {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.kbd {
  font-size: 10px;
  font-weight: 600;
  font-family: inherit;
  padding: 2px 5px;
  border-radius: 4px;
  border: 1px solid var(--accent-soft);
  color: var(--accent);
  background: var(--bg-elevated);
  line-height: 1.2;
}

.hist-head {
  font-size: 12px;
  color: var(--text-muted);
  margin: 10px 2px 6px;
  flex-shrink: 0;
}

.hist-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-bottom: 8px;
  margin-right: -4px;
  padding-right: 4px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px;
  border-radius: 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s ease;
}
.item:hover {
  background: var(--sidebar-item-hover);
}
.item.active {
  background: var(--sidebar-item-active);
}

.item-msg-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  color: var(--text-muted);
  background: transparent;
}
.item:hover .item-msg-icon,
.item.active .item-msg-icon {
  color: var(--text-secondary);
}
.item-msg-svg {
  width: 16px;
  height: 16px;
}

.item-main {
  flex: 1;
  min-width: 0;
}

.item-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

.more {
  color: var(--text-muted);
  flex-shrink: 0;
}

.load-more {
  text-align: center;
  padding: 10px 4px;
  font-size: 12px;
}

.collapsed-rail {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0;
  gap: 10px;
  min-height: 0;
}

.sidebar-footer {
  border-top: 1px solid var(--border-subtle);
  padding: 10px 10px 12px;
  background: var(--bg-sidebar);
  flex-shrink: 0;
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
  border: none;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: background 0.15s ease;
}
.user-line:hover {
  background: var(--sidebar-item-hover);
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
  padding: 12px 4px;
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
