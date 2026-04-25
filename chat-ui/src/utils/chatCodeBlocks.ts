/** 常见 fence 别名 → highlight.js 语言 id */
const LANG_ALIASES: Record<string, string> = {
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  js: 'javascript',
  ts: 'typescript',
  py: 'python',
  rs: 'rust',
  kt: 'kotlin',
  cs: 'csharp',
  cpp: 'cpp',
  'c++': 'cpp',
  cxx: 'cpp',
  h: 'c',
  hpp: 'cpp',
  ps1: 'powershell',
  ps: 'powershell',
  md: 'markdown',
  docker: 'dockerfile',
  tf: 'plaintext',
  vue: 'html',
  jsx: 'javascript',
  tsx: 'typescript',
}

function normalizeLang(raw: string | undefined | null): string {
  const s = (raw || '').trim().toLowerCase()
  if (!s) return ''
  const base = s.split(/[/\s]+/)[0] ?? s
  return LANG_ALIASES[base] ?? base
}

/** 无 fence 语言时按内容粗判（用于左上角展示） */
export function inferLangFromCode(text: string, hlLang: string): string {
  const t = text.trimStart()
  const fromHl = normalizeLang(hlLang)
  if (fromHl && fromHl !== 'plaintext') return fromHl
  if (/^\s*[\[{]/.test(t) && /"[\w-]+"\s*:/.test(t)) return 'json'
  if (/^\s*[\w-]+\s*:\s*.+$/m.test(t) && /^\s*[\w-]+\s*:/m.test(t) && t.includes(':')) {
    if (/apiVersion|kind:\s*Deployment|metadata:/m.test(t)) return 'yaml'
    if (/^---\s*$/m.test(t)) return 'yaml'
  }
  if (/^\s*(def |class |import |from \w+ import|print\()/m.test(t)) return 'python'
  if (/^\s*(package |import java\.|public class |void main\()/m.test(t)) return 'java'
  if (/^\s*(#include|int main\s*\(|namespace \w+)/m.test(t)) return 'cpp'
  if (/^\s*(fn |let mut |impl <|use std::)/m.test(t)) return 'rust'
  if (/^\s*(func |package main|:= range )/m.test(t)) return 'go'
  if (/^\s*(SELECT |INSERT INTO|CREATE TABLE)/i.test(t)) return 'sql'
  if (/^\s*<\?xml/i.test(t)) return 'xml'
  if (/^\s*<!DOCTYPE html|<html[\s>]/i.test(t)) return 'html'
  if (/^\s*#\!/.test(t) || /^\s*(echo |export |if \[)/m.test(t)) return 'bash'
  return 'plaintext'
}

export type ChatCodeBlockLabels = {
  copy: string
  collapse: string
  expand: string
}

/**
 * 将 `.markdown-body` 内由 marked 输出的 `<pre><code class="hljs ...">` 包一层工具栏（语言、折叠、复制）。
 * 在 v-html 更新后调用；同一容器可重复调用，已增强的块会跳过。
 */
export function enhanceMarkdownCodeBlocks(root: HTMLElement | null | undefined, labels: ChatCodeBlockLabels): void {
  if (!root) return
  const codes = root.querySelectorAll<HTMLElement>('pre > code.hljs')
  for (const code of codes) {
    const pre = code.parentElement
    if (!pre || pre.tagName !== 'PRE') continue
    if (pre.closest('.chat-code-block')) continue

    const rawText = code.textContent ?? ''
    const hlMatch = (code.className || '').match(/language-([\w-]+)/)
    const hlRaw = hlMatch?.[1] ?? 'plaintext'
    const displayLang = inferLangFromCode(rawText, hlRaw)

    const wrap = document.createElement('div')
    wrap.className = 'chat-code-block'
    wrap.dataset.lang = displayLang

    const toolbar = document.createElement('div')
    toolbar.className = 'chat-code-toolbar'

    const left = document.createElement('div')
    left.className = 'chat-code-toolbar-left'

    const label = document.createElement('span')
    label.className = 'chat-code-lang'
    label.textContent = displayLang

    const toggle = document.createElement('button')
    toggle.type = 'button'
    toggle.className = 'chat-code-toggle'
    toggle.setAttribute('aria-expanded', 'true')
    toggle.textContent = labels.collapse

    left.appendChild(label)
    left.appendChild(toggle)

    const copyBtn = document.createElement('button')
    copyBtn.type = 'button'
    copyBtn.className = 'chat-code-copy'
    copyBtn.setAttribute('aria-label', labels.copy)
    copyBtn.textContent = labels.copy

    toolbar.appendChild(left)
    toolbar.appendChild(copyBtn)

    const body = document.createElement('div')
    body.className = 'chat-code-body'

    const parent = pre.parentNode
    if (!parent) continue
    parent.insertBefore(wrap, pre)
    wrap.appendChild(toolbar)
    body.appendChild(pre)
    wrap.appendChild(body)

    toggle.addEventListener('click', () => {
      const collapsed = wrap.classList.toggle('chat-code-block--collapsed')
      toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true')
      toggle.textContent = collapsed ? labels.expand : labels.collapse
    })

    copyBtn.addEventListener('click', async () => {
      const t = code.textContent ?? ''
      try {
        await navigator.clipboard.writeText(t)
        const prev = copyBtn.textContent
        copyBtn.textContent = '✓'
        window.setTimeout(() => {
          copyBtn.textContent = prev
        }, 1600)
      } catch {
        try {
          const ta = document.createElement('textarea')
          ta.value = t
          ta.style.position = 'fixed'
          ta.style.left = '-9999px'
          document.body.appendChild(ta)
          ta.select()
          document.execCommand('copy')
          document.body.removeChild(ta)
          const prev = copyBtn.textContent
          copyBtn.textContent = '✓'
          window.setTimeout(() => {
            copyBtn.textContent = prev
          }, 1600)
        } catch {
          /* ignore */
        }
      }
    })
  }
}
