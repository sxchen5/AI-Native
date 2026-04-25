/**
 * @deprecated 代码块工具栏已内联进 {@link renderAiMarkdown}，保留此文件以免旧 import 报错。
 * 仍导出 {@link inferLangFromCode} 供外部复用。
 */
export { inferLangFromCode, normalizeFenceLang } from './chatCodeInfer'

export type ChatCodeBlockLabels = {
  copy: string
  collapse: string
  expand: string
}

/** 空实现：历史代码若仍调用则无操作 */
export function enhanceMarkdownCodeBlocks(): void {
  /* no-op */
}
