import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

import { i18n } from '../i18n'

const STORAGE_KEY = 'doubao-locale'

export type AppLocale = 'zh-CN' | 'en-US'

export const useLocaleStore = defineStore('locale', () => {
  const locale = ref<AppLocale>('zh-CN')

  /** 与 vue-i18n@11 的 WritableComputedRef 同步，修复 v-model 不生效 */
  watch(
    locale,
    (next) => {
      const gl = i18n.global.locale as unknown as { value: AppLocale }
      gl.value = next
      try {
        localStorage.setItem(STORAGE_KEY, next)
      } catch {
        // ignore
      }
    },
    { flush: 'sync' },
  )

  function setLocale(next: AppLocale) {
    locale.value = next
  }

  function toggle() {
    setLocale(locale.value === 'zh-CN' ? 'en-US' : 'zh-CN')
  }

  function init() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY) as AppLocale | null
      if (saved === 'zh-CN' || saved === 'en-US') {
        locale.value = saved
      }
    } catch {
      // ignore
    }
    ;(i18n.global.locale as unknown as { value: AppLocale }).value = locale.value
  }

  return { locale, setLocale, toggle, init }
})
