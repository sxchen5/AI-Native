import { defineStore } from 'pinia'
import { ref } from 'vue'

import { i18n } from '../i18n'

const STORAGE_KEY = 'doubao-locale'

export type AppLocale = 'zh-CN' | 'en-US'

function applyI18nLocale(next: AppLocale) {
  const gl = i18n.global as { locale?: unknown }
  const loc = gl.locale
  if (loc && typeof loc === 'object' && 'value' in loc) {
    ;(loc as { value: AppLocale }).value = next
  } else {
    ;(gl as { locale: AppLocale }).locale = next
  }
}

export const useLocaleStore = defineStore('locale', () => {
  const locale = ref<AppLocale>('zh-CN')

  function setLocale(next: AppLocale) {
    locale.value = next
    applyI18nLocale(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // ignore
    }
  }

  function toggle() {
    setLocale(locale.value === 'zh-CN' ? 'en-US' : 'zh-CN')
  }

  function init() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY) as AppLocale | null
      if (saved === 'zh-CN' || saved === 'en-US') {
        locale.value = saved
        applyI18nLocale(saved)
      }
    } catch {
      // ignore
    }
  }

  return { locale, setLocale, toggle, init }
})
