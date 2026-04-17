export type ChatRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

export type SessionSummary = {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export type ChatMessage = {
  id: string
  role: ChatRole
  content: string
  createdAt: string
}

export type SseEnvelope =
  | { type: 'start'; assistantMessageId: string; text?: string; message?: string }
  | { type: 'delta'; assistantMessageId?: string; text?: string; message?: string }
  | { type: 'done'; assistantMessageId?: string; text?: string; message?: string }
  | { type: 'error'; assistantMessageId?: string; text?: string; message?: string }
