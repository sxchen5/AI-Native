import { http } from './http'
import type { ChatMessage, SessionSummary } from './types'

export async function listSessions(): Promise<SessionSummary[]> {
  const { data } = await http.get<SessionSummary[]>('/api/sessions')
  return data
}

export async function createSession(title?: string): Promise<SessionSummary> {
  const { data } = await http.post<SessionSummary>('/api/sessions', { title })
  return data
}

export async function renameSession(sessionId: string, title: string): Promise<void> {
  await http.patch(`/api/sessions/${sessionId}`, { title })
}

export async function deleteSession(sessionId: string): Promise<void> {
  await http.delete(`/api/sessions/${sessionId}`)
}

export async function listMessages(sessionId: string): Promise<ChatMessage[]> {
  const { data } = await http.get<ChatMessage[]>(`/api/sessions/${sessionId}/messages`)
  return data
}
