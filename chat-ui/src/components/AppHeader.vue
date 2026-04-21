<script setup lang="ts">
import { ChatDotRound, Moon, Sunny, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'

import { useChatStore } from '../stores/chat'
import { useLocaleStore } from '../stores/locale'
import { useThemeStore } from '../stores/theme'

const { t } = useI18n()
const theme = useThemeStore()
const locale = useLocaleStore()
const chat = useChatStore()

async function onRefresh() {
  try {
    await chat.fetchSessions()
    if (chat.activeSessionId) await chat.fetchMessages(chat.activeSessionId)
  } catch (e: unknown) {
    ElMessage.error((e as Error).message || t('errors.loadSessions'))
  }
}
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <div class="logo" aria-hidden="true">
        <el-icon :size="22"><ChatDotRound /></el-icon>
      </div>
      <div class="titles">
        <span class="name">{{ t('app.name') }}</span>
        <span class="tag">{{ t('app.tagline') }}</span>
      </div>
    </div>
    <div class="actions">
      <el-button text class="icon-btn" @click="onRefresh">
        <el-icon><RefreshRight /></el-icon>
      </el-button>
      <el-button-group class="lang-group">
        <el-button
          :type="locale.locale === 'zh-CN' ? 'primary' : 'default'"
          size="small"
          @click="locale.setLocale('zh-CN')"
        >
          {{ t('header.langZh') }}
        </el-button>
        <el-button
          :type="locale.locale === 'en-US' ? 'primary' : 'default'"
          size="small"
          @click="locale.setLocale('en-US')"
        >
          {{ t('header.langEn') }}
        </el-button>
      </el-button-group>
      <el-tooltip :content="theme.isDark ? t('header.themeLight') : t('header.themeDark')" placement="bottom">
        <el-button circle class="theme-btn" @click="theme.toggle">
          <el-icon v-if="theme.isDark"><Sunny /></el-icon>
          <el-icon v-else><Moon /></el-icon>
        </el-button>
      </el-tooltip>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-sm);
  transition:
    background 0.35s var(--ease-out, ease),
    border-color 0.35s var(--ease-out, ease);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--bg-bubble-user);
  color: #fff;
  display: grid;
  place-items: center;
  box-shadow: var(--shadow-sm);
}

.titles {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.name {
  font-weight: 700;
  font-size: 15px;
  color: var(--text-primary);
  letter-spacing: -0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag {
  font-size: 11px;
  color: var(--text-secondary);
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.icon-btn {
  color: var(--text-secondary);
}

.lang-group :deep(.el-button) {
  border-radius: 8px;
}

.theme-btn {
  border: 1px solid var(--border-subtle);
  background: var(--bg-sidebar);
  color: var(--text-primary);
  transition: transform 0.2s ease;
}
.theme-btn:hover {
  transform: scale(1.04);
}
</style>
