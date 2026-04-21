/** 助手消息 metadata 中的 feedback：up | down */
export function parseFeedbackVote(raw: string | null | undefined): 'up' | 'down' | null {
  if (!raw) return null
  try {
    const o = JSON.parse(raw) as Record<string, unknown>
    const v = o.feedback
    if (v === 'up' || v === 'down') return v
    return null
  } catch {
    return null
  }
}
