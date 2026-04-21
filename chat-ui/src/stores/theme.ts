import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

const STORAGE_KEY = 'doubao-theme'

export type ThemeMode = 'light' | 'dark'

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>('light')

  const isDark = computed(() => mode.value === 'dark')

  function applyDom() {
    const root = document.documentElement
    if (mode.value === 'dark') {
      root.classList.add('dark')
    } else {
      root.classList.remove('dark')
    }
    root.dataset.theme = mode.value
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // ignore
    }
    applyDom()
  }

  function toggle() {
    setMode(mode.value === 'dark' ? 'light' : 'dark')
  }

  function init() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY) as ThemeMode | null
      if (saved === 'dark' || saved === 'light') {
        mode.value = saved
      } else if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
        mode.value = 'dark'
      }
    } catch {
      // ignore
    }
    applyDom()
  }

  watch(mode, applyDom, { immediate: false })

  return { mode, isDark, setMode, toggle, init }
})
