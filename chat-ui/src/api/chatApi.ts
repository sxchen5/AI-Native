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

export type ExtractedAttachment = { fileName: string; text: string }

export async function extractAttachmentText(file: File): Promise<ExtractedAttachment> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<ExtractedAttachment>('/api/chat/attachments/extract', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120_000,
  })
  return data
}
