import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import { Marked, Renderer } from 'marked'

import 'highlight.js/styles/github.css'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('python', python)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

class AiRenderer extends Renderer {
  override code({ text, lang }: { text: string; lang?: string }) {
    const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
    let highlighted: string
    try {
      highlighted =
        language === 'plaintext'
          ? hljs.highlightAuto(text).value
          : hljs.highlight(text, { language }).value
    } catch {
      highlighted = escapeHtml(text)
    }
    return `<pre><code class="hljs language-${language}">${highlighted}</code></pre>`
  }
}

const marked = new Marked({
  gfm: true,
  breaks: true,
  renderer: new AiRenderer(),
})

/**
 * 将 AI 文本渲染为安全的 HTML（Markdown + 代码高亮）。
 */
export function renderAiMarkdown(raw: string): string {
  const html = marked.parse(raw || '', { async: false }) as string
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}
