const FADE_MS = 900

/** 滚动时给元素加类，停止滚动一段时间后移除（配合 .u-scroll--active 显示滚动条） */
export function useScrollbarFade(elRef: { value: HTMLElement | null }) {
  let timer: ReturnType<typeof setTimeout> | null = null

  function onScroll() {
    const el = elRef.value
    if (!el) return
    el.classList.add('u-scroll--active')
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      el.classList.remove('u-scroll--active')
      timer = null
    }, FADE_MS)
  }

  function attach() {
    const el = elRef.value
    if (!el) return
    el.addEventListener('scroll', onScroll, { passive: true })
  }

  function detach() {
    const el = elRef.value
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    el?.removeEventListener('scroll', onScroll)
    el?.classList.remove('u-scroll--active')
  }

  return { attach, detach, onScroll }
}
