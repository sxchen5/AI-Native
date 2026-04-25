import axios from 'axios'

/**
 * Axios 实例：默认走同源 /api（开发环境由 Vite 代理到网关）。
 */
export const http = axios.create({
  baseURL: '',
  timeout: 60_000,
  withCredentials: true,
})

function redirectToLoginIfNeeded() {
  /** 延迟到当前模块初始化完成后再拉 router，避免 http ↔ router 循环依赖 */
  queueMicrotask(() => {
    void import('../stores/auth').then(({ useAuthStore }) => {
      useAuthStore().clearSession()
    })
    void import('../router').then(({ router }) => {
      if (router.currentRoute.value.name !== 'login') {
        void router.replace({ name: 'login' })
      }
    })
  })
}

http.interceptors.response.use(
  (r) => r,
  (err) => {
    const status = err?.response?.status
    if (status === 401) {
      redirectToLoginIfNeeded()
    }
    const msg = err?.response?.data?.message || err?.message || '请求失败'
    return Promise.reject(new Error(msg))
  },
)
