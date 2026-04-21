<script setup lang="ts">
import { computed, onBeforeMount } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'

import ChatWorkspace from './components/ChatWorkspace.vue'
import { useLocaleStore } from './stores/locale'
import { useThemeStore } from './stores/theme'
import { useUiStore } from './stores/ui'

const localeStore = useLocaleStore()
const themeStore = useThemeStore()
const uiStore = useUiStore()

onBeforeMount(() => {
  themeStore.init()
  localeStore.init()
  uiStore.init()
})

const elementLocale = computed(() => (localeStore.locale === 'en-US' ? en : zhCn))
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <div class="root">
      <ChatWorkspace />
    </div>
  </el-config-provider>
</template>

<style scoped>
.root {
  height: 100%;
}
</style>
