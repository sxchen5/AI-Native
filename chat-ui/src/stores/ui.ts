import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

const STORAGE_KEY = 'doubao-sidebar-collapsed'

export const useUiStore = defineStore('ui', () => {
  const sidebarCollapsed = ref(false)
  /** 画布分屏页：强制收起左侧会话栏 */
  const forceSidebarCollapsed = ref(false)
  /** 设置弹窗（主题、语言） */
  const settingsOpen = ref(false)

  const sidebarCollapsedEffective = computed(() => forceSidebarCollapsed.value || sidebarCollapsed.value)

  function enterCanvasLayout() {
    forceSidebarCollapsed.value = true
  }

  function exitCanvasLayout() {
    forceSidebarCollapsed.value = false
  }

  function toggleSidebar() {
    if (forceSidebarCollapsed.value) return
    sidebarCollapsed.value = !sidebarCollapsed.value
    try {
      localStorage.setItem(STORAGE_KEY, sidebarCollapsed.value ? '1' : '0')
    } catch {
      // ignore
    }
  }

  function openSettings() {
    settingsOpen.value = true
  }

  function closeSettings() {
    settingsOpen.value = false
  }

  function init() {
    try {
      sidebarCollapsed.value = localStorage.getItem(STORAGE_KEY) === '1'
    } catch {
      // ignore
    }
  }

  return {
    sidebarCollapsed,
    sidebarCollapsedEffective,
    forceSidebarCollapsed,
    settingsOpen,
    toggleSidebar,
    enterCanvasLayout,
    exitCanvasLayout,
    openSettings,
    closeSettings,
    init,
  }
})
