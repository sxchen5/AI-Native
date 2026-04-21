/** 从 Markdown 中取文档主标题（优先一级 #，否则取首个 ##） */
export function firstMarkdownTitle(md: string, fallback: string): string {
  const t = md.trim()
  const h1 = /^#\s+(.+)$/m.exec(t)
  if (h1?.[1]) return h1[1].trim()
  const h2 = /^##\s+(.+)$/m.exec(t)
  if (h2?.[1]) return h2[1].trim()
  return fallback
}

export type TocItem = { level: 2 | 3; text: string; index: number }

/** 提取 ## / ### 作为画布目录（顺序与预览中 h2、h3 一致） */
export function extractMarkdownToc(md: string): TocItem[] {
  const out: TocItem[] = []
  const lines = md.split(/\r?\n/)
  let idx = 0
  for (const line of lines) {
    const m = /^(#{2,3})\s+(.+)$/.exec(line.trim())
    if (!m) continue
    const level = m[1].length as 2 | 3
    if (level !== 2 && level !== 3) continue
    out.push({ level, text: m[2].trim(), index: idx++ })
  }
  return out
}
