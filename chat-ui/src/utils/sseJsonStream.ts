import type { SseEnvelope } from '../api/types'

export type SseStreamOptions = {
  signal?: AbortSignal
  /** 首次请求失败后的重试次数（不含首次） */
  maxRetries?: number
  retryDelayMs?: number
}

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms))
}

function parseSseBlock(block: string): string | null {
  const lines = block.replace(/\r\n/g, '\n').split('\n')
  const dataParts: string[] = []
  for (const raw of lines) {
    const line = raw.trimEnd()
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('data:')) {
      dataParts.push(line.replace(/^data:\s?/, ''))
    }
  }
  if (dataParts.length === 0) return null
  return dataParts.join('\n')
}

/**
 * 处理不完整的 SSE 行：只要出现完整的 `data: {...}\\n`，立即解析并回调（不等双换行）。
 * 网关/代理可能逐行缓冲，避免首 token 延迟到整段结束才显示。
 */
async function readSseBody(
  res: Response,
  onEvent: (evt: SseEnvelope) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (!res.body) throw new Error('SSE: response body is not readable')
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const flushEventBlock = (block: string) => {
    const payload = parseSseBlock(block)
    if (!payload) return
    const evt = JSON.parse(payload) as SseEnvelope
    onEvent(evt)
  }

  const tryFlushLines = () => {
    buffer = buffer.replace(/\r\n/g, '\n')
    let idx: number
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trimEnd()
      buffer = buffer.slice(idx + 1)
      if (!line || line.startsWith(':')) continue
      if (line.startsWith('data:')) {
        flushEventBlock(line)
      }
    }
  }

  while (true) {
    if (signal?.aborted) {
      reader.cancel().catch(() => {})
      throw new DOMException('Aborted', 'AbortError')
    }
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    tryFlushLines()
  }
  tryFlushLines()
  const tail = buffer.trim()
  if (tail.length > 0) flushEventBlock(tail)
}

/**
 * POST + SSE：解析 `data:` JSON 事件；支持失败重试与 AbortSignal。
 */
export async function postSseJsonStream(
  path: string,
  body: unknown,
  onEvent: (evt: SseEnvelope) => void,
  opts?: SseStreamOptions,
): Promise<void> {
  const maxRetries = opts?.maxRetries ?? 0
  const retryDelayMs = opts?.retryDelayMs ?? 600
  const signal = opts?.signal

  let attempt = 0
  // eslint-disable-next-line no-constant-condition
  while (true) {
    try {
      const res = await fetch(path, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        },
        body: JSON.stringify(body),
        signal,
        credentials: 'include',
      })

      if (!res.ok) {
        const text = await res.text().catch(() => '')
        const err = new Error(text || `HTTP ${res.status}`)
        ;(err as { status?: number }).status = res.status
        throw err
      }

      await readSseBody(res, onEvent, signal)
      return
    } catch (e) {
      if (signal?.aborted) throw e
      const isLast = attempt >= maxRetries
      const status = (e as { status?: number })?.status
      const retryable = status == null || status >= 500 || status === 429
      if (!retryable || isLast) throw e
      attempt += 1
      await sleep(retryDelayMs * attempt)
    }
  }
}
