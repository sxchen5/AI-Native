import { defineStore } from 'pinia'
import { ref } from 'vue'

const STORAGE_KEY = 'doubao-sidebar-collapsed'

export const useUiStore = defineStore('ui', () => {
  const sidebarCollapsed = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    try {
      localStorage.setItem(STORAGE_KEY, sidebarCollapsed.value ? '1' : '0')
    } catch {
      // ignore
    }
  }

  function init() {
    try {
      sidebarCollapsed.value = localStorage.getItem(STORAGE_KEY) === '1'
    } catch {
      // ignore
    }
  }

  return { sidebarCollapsed, toggleSidebar, init }
})
