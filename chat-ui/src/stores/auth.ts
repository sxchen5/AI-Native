import { defineStore } from 'pinia'
import { ref } from 'vue'

import * as authApi from '../api/authApi'

export const useAuthStore = defineStore('auth', () => {
  const authenticated = ref(false)
  const username = ref<string | null>(null)
  const checked = ref(false)

  async function refreshMe() {
    try {
      const me = await authApi.fetchMe()
      authenticated.value = !!me.authenticated
      username.value = me.username ?? null
      checked.value = true
      return me.authenticated
    } catch {
      authenticated.value = false
      username.value = null
      checked.value = true
      return false
    }
  }

  async function login(u: string, p: string, captchaId: string, captchaCode: string) {
    await authApi.login({ username: u, password: p, captchaId, captchaCode })
    await refreshMe()
  }

  async function logout() {
    await authApi.logout()
    authenticated.value = false
    username.value = null
  }

  return { authenticated, username, checked, refreshMe, login, logout }
})
