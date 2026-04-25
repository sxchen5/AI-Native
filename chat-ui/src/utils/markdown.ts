import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import go from 'highlight.js/lib/languages/go'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import markdown from 'highlight.js/lib/languages/markdown'
import powershell from 'highlight.js/lib/languages/powershell'
import python from 'highlight.js/lib/languages/python'
import rust from 'highlight.js/lib/languages/rust'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import type { Token, Tokens } from 'marked'
import { Marked, Renderer } from 'marked'

import { chatHeadingPlainText, sanitizeMessageIdForHeadingPrefix, slugifyChatHeadingId } from './chatMarkdownHeadings'
import { inferLangFromCode } from './chatCodeInfer'
import { i18n } from '../i18n'

import 'highlight.js/styles/github.css'

export { extractChatMarkdownHeadingToc } from './chatMarkdownHeadings'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('python', python)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('java', java)
hljs.registerLanguage('kotlin', kotlin)
hljs.registerLanguage('go', go)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('c', cpp)
hljs.registerLanguage('csharp', csharp)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('powershell', powershell)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('dockerfile', dockerfile)

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

const ALERT_KINDS = new Set(['note', 'tip', 'important', 'warning', 'caution'])

const ALERT_TITLES: Record<string, string> = {
  note: 'Note',
  tip: 'Tip',
  important: 'Important',
  warning: 'Warning',
  caution: 'Caution',
}

/** GitHub 风格 `> [!NOTE]` 块 → `markdown-alert` HTML（内层再走 Markdown） */
function preprocessGithubAlerts(md: string): string {
  const lines = md.split(/\r?\n/)
  const out: string[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]!
    const m = line.match(/^(\s*)>\s*\[!(\w+)\]\s*$/i)
    if (!m) {
      out.push(line)
      i++
      continue
    }
    const indent = m[1] ?? ''
    let kind = (m[2] ?? 'note').toLowerCase()
    if (!ALERT_KINDS.has(kind)) kind = 'note'
    i++
    const innerLines: string[] = []
    while (i < lines.length) {
      const l = lines[i]!
      if (/^\s*>[ \t]/.test(l) || /^\s*>$/.test(l)) {
        innerLines.push(l.replace(/^\s*>\s?/, ''))
        i++
      } else break
    }
    const innerMd = innerLines.join('\n').trimEnd()
    const innerHtml = (marked.parse(innerMd, { async: false }) as string).trim()
    const title = ALERT_TITLES[kind] ?? 'Note'
    out.push('')
    out.push(`${indent}<div class="markdown-alert markdown-alert-${kind}">`)
    out.push(`${indent}<p class="markdown-alert-title">${escapeHtml(title)}</p>`)
    if (innerHtml) out.push(innerHtml)
    out.push(`${indent}</div>`)
    out.push('')
  }
  return out.join('\n')
}

function preprocessMarkdown(raw: string): string {
  return preprocessGithubAlerts(raw)
}

/** 单次 {@link renderAiMarkdown} 解析内为标题 id 去重 */
let headingSlugRegistry: Set<string> | null = null
/** 当前气泡 messageId 前缀，避免跨消息 id 冲突 */
let headingIdPrefix = ''

class AiRenderer extends Renderer {
  override heading({ tokens, depth }: Tokens.Heading) {
    const used = headingSlugRegistry ?? new Set<string>()
    const plain = chatHeadingPlainText(tokens as Token[])
    const id = escapeHtml(headingIdPrefix + slugifyChatHeadingId(plain, used))
    const inner = this.parser.parseInline(tokens)
    return `<h${depth} id="${id}">${inner}</h${depth}>\n`
  }

  override code({ text, lang }: { text: string; lang?: string }) {
    const raw = (lang || '').trim().toLowerCase().split(/[/\s]+/)[0] ?? ''
    const alias: Record<string, string> = {
      yml: 'yaml',
      sh: 'bash',
      shell: 'bash',
      zsh: 'bash',
      js: 'javascript',
      ts: 'typescript',
      py: 'python',
      rs: 'rust',
      kt: 'kotlin',
      cs: 'csharp',
      docker: 'dockerfile',
    }
    const candidate = raw ? alias[raw] ?? raw : ''
    const language = candidate && hljs.getLanguage(candidate) ? candidate : 'plaintext'
    let highlighted: string
    try {
      highlighted =
        language === 'plaintext'
          ? hljs.highlightAuto(text).value
          : hljs.highlight(text, { language }).value
    } catch {
      highlighted = escapeHtml(text)
    }
    const safeLang = escapeHtml(language)
    const displayLang = escapeHtml(inferLangFromCode(text, language))
    const collapse = escapeHtml(i18n.global.t('chat.collapseCode') as string)
    const copyLabel = escapeHtml(i18n.global.t('chat.copyCode') as string)
    return `<div class="chat-code-block" data-lang="${displayLang}">
<div class="chat-code-toolbar">
<div class="chat-code-toolbar-left">
<span class="chat-code-lang">${displayLang}</span>
<button type="button" class="chat-code-toggle" aria-expanded="true">${collapse}</button>
</div>
<button type="button" class="chat-code-copy" aria-label="${copyLabel}">${copyLabel}</button>
</div>
<div class="chat-code-body">
<pre><code class="hljs language-${safeLang}">${highlighted}</code></pre>
</div>
</div>`
  }
}

const marked = new Marked({
  gfm: true,
  breaks: true,
  renderer: new AiRenderer(),
})

const PURIFY_OPTS = {
  USE_PROFILES: { html: true },
  ADD_ATTR: ['align', 'id', 'aria-expanded', 'aria-label', 'type', 'data-lang'],
  ADD_TAGS: ['button'],
}

/**
 * 将 AI 文本渲染为安全的 HTML（Markdown + GFM 表格 + 代码高亮 + GitHub 风格提示块）。
 * 外层需配合 `github-markdown-css` 的 `.markdown-body` 使用（与 `.prose-ai` 同节点）。
 */
/**
 * @param messageId 可选；传入时为标题 id 加前缀，与 {@link extractChatMarkdownHeadingToc} 一致且避免多气泡 id 重复
 */
export function renderAiMarkdown(raw: string, messageId?: string): string {
  const md = preprocessMarkdown(raw || '')
  headingSlugRegistry = new Set<string>()
  headingIdPrefix = messageId ? `${sanitizeMessageIdForHeadingPrefix(messageId)}-` : ''
  try {
    const html = marked.parse(md, { async: false }) as string
    return DOMPurify.sanitize(html, PURIFY_OPTS) as unknown as string
  } finally {
    headingSlugRegistry = null
    headingIdPrefix = ''
  }
}
