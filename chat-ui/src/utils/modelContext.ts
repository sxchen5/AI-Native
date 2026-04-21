export type AttachmentChip = {
  label: string
  kind: 'file' | 'image' | 'voice'
  fileName: string
  mimeType: string
  extractedText: string
}

export type UserBubbleMeta = {
  chips?: { label: string; kind: string }[]
}

export function parseUserBubbleFromMetadata(raw: string | null | undefined): UserBubbleMeta | null {
  if (!raw) return null
  try {
    const o = JSON.parse(raw) as Record<string, unknown>
    const ub = o.userBubble as { chips?: { label: string; kind: string }[] } | undefined
    if (!ub?.chips?.length) return null
    return { chips: ub.chips }
  } catch {
    return null
  }
}
