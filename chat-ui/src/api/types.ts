export type ChatRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

export type SessionSummary = {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

/** 文档卡片消息的 metadata（JSON 字符串解析后） */
export type DocumentCardMeta = {
  type: 'document_card'
  title: string
  markdownBody: string
  sourceAssistantId?: string
  frozen?: boolean
}

export type ChatMessage = {
  id: string
  role: ChatRole
  content: string
  createdAt: string
  /** 后端 JSON 字符串，可为 null */
  metadata?: string | null
}

export type ExtractedAttachment = { fileName: string; mimeType: string; text: string }

export type SseEnvelope =
  | { type: 'start'; assistantMessageId: string; text?: string; message?: string }
  | { type: 'delta'; assistantMessageId?: string; text?: string; message?: string }
  | { type: 'done'; assistantMessageId?: string; text?: string; message?: string }
  | { type: 'error'; assistantMessageId?: string; text?: string; message?: string }
