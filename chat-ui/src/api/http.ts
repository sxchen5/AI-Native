import axios from 'axios'

/**
 * Axios 实例：默认走同源 /api（开发环境由 Vite 代理到网关）。
 */
export const http = axios.create({
  baseURL: '',
  timeout: 60_000,
})

http.interceptors.response.use(
  (r) => r,
  (err) => {
    const msg = err?.response?.data?.message || err?.message || '请求失败'
    return Promise.reject(new Error(msg))
  },
)
