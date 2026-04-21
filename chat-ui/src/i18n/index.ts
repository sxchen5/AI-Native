import { createI18n } from 'vue-i18n'

import zhCN from './locales/zh-CN.json'
import enUS from './locales/en-US.json'

export type MessageSchema = typeof zhCN

export const i18n = createI18n<[MessageSchema], 'zh-CN' | 'en-US'>({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
  globalInjection: true,
})
