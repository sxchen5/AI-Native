<script setup lang="ts">
import { computed, onBeforeMount, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'

import { useAuthStore } from './stores/auth'
import { useLocaleStore } from './stores/locale'
import { useProfileStore } from './stores/profile'
import { useThemeStore } from './stores/theme'
import { useUiStore } from './stores/ui'

const localeStore = useLocaleStore()
const themeStore = useThemeStore()
const uiStore = useUiStore()
const profileStore = useProfileStore()
const auth = useAuthStore()
const { t } = useI18n()

onBeforeMount(async () => {
  themeStore.init()
  localeStore.init()
  uiStore.init()
  profileStore.init()
  await auth.refreshMe()
})

/** 浏览器页签标题与 html lang 随界面语言切换（与 app.name 一致：中文「AI助手」/ 英文 AI Assistant） */
watchEffect(() => {
  void localeStore.locale
  document.documentElement.lang = localeStore.locale === 'en-US' ? 'en-US' : 'zh-CN'
  document.title = t('app.name')
})

const elementLocale = computed(() => (localeStore.locale === 'en-US' ? en : zhCn))
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <div class="root">
      <div v-if="!auth.checked" class="boot" />
      <router-view v-else />
    </div>
  </el-config-provider>
</template>

<style scoped>
.root {
  height: 100%;
  min-height: 100%;
  background: var(--bg-app);
}

.boot {
  height: 100%;
  background: var(--bg-app);
}
</style>
