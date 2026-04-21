import type { DocumentCardMeta } from '../api/types'

export function parseDocumentMeta(raw: string | null | undefined): DocumentCardMeta | null {
  if (!raw) return null
  try {
    const o = JSON.parse(raw) as Record<string, unknown>
    if (o.type !== 'document_card') return null
    return {
      type: 'document_card',
      title: String(o.title ?? '文档'),
      markdownBody: String(o.markdownBody ?? ''),
      sourceAssistantId: o.sourceAssistantId != null ? String(o.sourceAssistantId) : undefined,
      frozen: Boolean(o.frozen),
    }
  } catch {
    return null
  }
}
