import type { Token, Tokens } from 'marked'
import { Marked } from 'marked'

/** 与 {@link renderAiMarkdown} 内 lexer 配置一致（仅用于抽标题） */
const LEXER_MARKED = new Marked({ gfm: true, breaks: true })

/** 消息 id 作锚点前缀时仅保留安全字符，避免非法 HTML id */
export function sanitizeMessageIdForHeadingPrefix(messageId: string): string {
  return (messageId || 'm').replace(/[^a-zA-Z0-9_-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || 'm'
}

function stripInlineMd(s: string): string {
  return s.replace(/\*\*|__|`|~~|\*|_/g, '').trim()
}

/** 生成稳定、可锚定的 id；与 {@link renderAiMarkdown} 中标题渲染共用 */
export function slugifyChatHeadingId(text: string, used: Set<string>): string {
  let base = stripInlineMd(text)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  if (!base) base = 'section'
  let slug = base
  let n = 0
  while (used.has(slug)) {
    n++
    slug = `${base}-${n}`
  }
  used.add(slug)
  return slug
}

/** 标题 token 列表转纯文本（用于 slug 与目录文案） */
export function chatHeadingPlainText(tokens: Token[]): string {
  let out = ''
  const walk = (list: Token[]) => {
    for (const t of list) {
      const tok = t as Tokens.Generic
      if (tok.type === 'text' && 'text' in tok) {
        out += (tok as Tokens.Text).text
      } else if (tok.type === 'escape' && 'text' in tok) {
        out += (tok as Tokens.Escape).text
      } else if ('tokens' in tok && Array.isArray(tok.tokens)) {
        walk(tok.tokens as Token[])
      } else if (tok.type === 'codespan' && 'text' in tok) {
        out += (tok as Tokens.Codespan).text
      }
    }
  }
  walk(tokens)
  return out.trim() || '标题'
}

export type ChatHeadingTocItem = {
  depth: number
  text: string
  id: string
  messageId: string
  /** 在该条消息 Markdown 渲染结果中，所有 h1–h6 的文档顺序下标（与画布目录按序号定位一致） */
  headingIndex: number
}

/**
 * 从 AI 回复 Markdown 中提取 h1–h6 目录项；id 与 {@link renderAiMarkdown} 带相同 {@code messageId} 前缀时一致。
 */
export function extractChatMarkdownHeadingToc(markdown: string, messageId?: string): ChatHeadingTocItem[] {
  const md = (markdown || '').trim()
  if (!md) return []
  const mid = messageId ?? ''
  const prefix = mid ? `${sanitizeMessageIdForHeadingPrefix(mid)}-` : ''
  const tokens = LEXER_MARKED.lexer(md) as Token[]
  const used = new Set<string>()
  const out: ChatHeadingTocItem[] = []
  let headingIndex = 0
  for (const t of tokens) {
    if (t.type !== 'heading') continue
    const h = t as Tokens.Heading
    const depth = Math.min(6, Math.max(1, h.depth))
    const plain = chatHeadingPlainText(h.tokens)
    const id = prefix + slugifyChatHeadingId(plain, used)
    out.push({ depth, text: plain, id, messageId: mid, headingIndex })
    headingIndex++
  }
  return out
}
