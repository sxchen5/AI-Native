import { i18n } from '../i18n'

function closestCodeWrap(el: Element | null): HTMLElement | null {
  return el?.closest?.('.chat-code-block') ?? null
}

function onDocumentClick(e: MouseEvent) {
  const target = e.target as HTMLElement | null
  if (!target) return

  const toggle = target.closest('.chat-code-toggle') as HTMLElement | null
  const wrapFromToggle = closestCodeWrap(toggle)
  if (wrapFromToggle && toggle) {
    const collapsed = wrapFromToggle.classList.toggle('chat-code-block--collapsed')
    toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true')
    toggle.textContent = collapsed
      ? (i18n.global.t('chat.expandCode') as string)
      : (i18n.global.t('chat.collapseCode') as string)
    return
  }

  const copyBtn = target.closest('.chat-code-copy') as HTMLElement | null
  const wrap = closestCodeWrap(copyBtn)
  if (!wrap || !copyBtn) return
  const code = wrap.querySelector('pre code')
  const text = code?.textContent ?? ''
  if (!text) return
  void (async () => {
    try {
      await navigator.clipboard.writeText(text)
      const prev = copyBtn.textContent
      copyBtn.textContent = '✓'
      window.setTimeout(() => {
        copyBtn.textContent = prev
      }, 1600)
    } catch {
      try {
        const ta = document.createElement('textarea')
        ta.value = text
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
  })()
}

/** 代码块折叠/复制由 Markdown 内联 HTML 渲染，事件在此统一委托（含 v-html 流式更新场景） */
export function installChatCodeBlockDelegate(): void {
  document.addEventListener('click', onDocumentClick)
}
