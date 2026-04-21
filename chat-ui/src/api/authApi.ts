import { http } from './http'

export type MeResponse = { authenticated: boolean; username?: string }

export async function fetchCaptcha(): Promise<{ blobUrl: string; captchaId: string }> {
  const res = await fetch('/api/auth/captcha', { credentials: 'include' })
  if (!res.ok) throw new Error(`验证码加载失败: ${res.status}`)
  const captchaId = res.headers.get('X-Captcha-Id')
  if (!captchaId) throw new Error('缺少验证码 ID')
  const blob = await res.blob()
  return { blobUrl: URL.createObjectURL(blob), captchaId }
}

export async function login(payload: {
  username: string
  password: string
  captchaId: string
  captchaCode: string
}): Promise<void> {
  await http.post('/api/auth/login', payload, { withCredentials: true })
}

export async function logout(): Promise<void> {
  await http.post('/api/auth/logout', {}, { withCredentials: true })
}

export async function fetchMe(): Promise<MeResponse> {
  const { data } = await http.get<MeResponse>('/api/auth/me', { withCredentials: true })
  return data
}
