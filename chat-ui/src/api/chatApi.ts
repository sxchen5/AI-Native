import { http } from './http'
import type { ChatMessage, ExtractedAttachment, SessionListPage, SessionSummary } from './types'

const SESSION_PAGE = 30

export async function listSessions(offset = 0, limit = SESSION_PAGE): Promise<SessionListPage> {
  const { data } = await http.get<SessionListPage>('/api/sessions', {
    params: { offset, limit },
  })
  return { items: data.items ?? [], hasMore: !!data.hasMore }
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

export async function convertToDocument(sessionId: string, sourceAssistantMessageId: string): Promise<ChatMessage> {
  const { data } = await http.post<ChatMessage>('/api/chat/document/convert', {
    sessionId,
    sourceAssistantMessageId,
  })
  return data
}

export async function updateDocumentMessage(
  sessionId: string,
  messageId: string,
  markdownBody: string,
): Promise<ChatMessage> {
  const { data } = await http.post<ChatMessage>('/api/chat/document/update', {
    sessionId,
    messageId,
    markdownBody,
  })
  return data
}

export async function freezeDocumentMessage(sessionId: string, messageId: string): Promise<ChatMessage> {
  const { data } = await http.post<ChatMessage>('/api/chat/document/freeze', { sessionId, messageId })
  return data
}

export async function setMessageFeedback(
  sessionId: string,
  messageId: string,
  vote: 'up' | 'down' | 'clear',
): Promise<ChatMessage> {
  const { data } = await http.post<ChatMessage>('/api/chat/message/feedback', { sessionId, messageId, vote })
  return data
}

export async function fetchFollowUpQuestions(sessionId: string): Promise<string[]> {
  const { data } = await http.post<{ questions: string[] }>('/api/chat/suggestions', { sessionId })
  return data.questions ?? []
}

export async function extractAttachmentText(file: File): Promise<ExtractedAttachment> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<ExtractedAttachment>('/api/chat/attachments/extract', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120_000,
  })
  return data
}
