/** 从 Markdown 粗提取可读纯文本（用于朗读等） */
export function markdownToPlainText(md: string): string {
  if (!md) return ''
  return md
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`[^`]+`/g, ' ')
    .replace(/!\[[^\]]*]\([^)]+\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')
    .replace(/[#>*_\-~`]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}
