<script setup lang="ts">
import { computed, onBeforeMount } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'

import ChatWorkspace from './components/ChatWorkspace.vue'
import LoginView from './components/LoginView.vue'
import { useAuthStore } from './stores/auth'
import { useLocaleStore } from './stores/locale'
import { useThemeStore } from './stores/theme'
import { useUiStore } from './stores/ui'

const localeStore = useLocaleStore()
const themeStore = useThemeStore()
const uiStore = useUiStore()
const auth = useAuthStore()

onBeforeMount(async () => {
  themeStore.init()
  localeStore.init()
  uiStore.init()
  await auth.refreshMe()
})

const elementLocale = computed(() => (localeStore.locale === 'en-US' ? en : zhCn))
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <div class="root">
      <div v-if="!auth.checked" class="boot" />
      <LoginView v-else-if="!auth.authenticated" />
      <ChatWorkspace v-else />
    </div>
  </el-config-provider>
</template>

<style scoped>
.root {
  height: 100%;
}

.boot {
  height: 100%;
  background: #ffffff;
}
</style>
