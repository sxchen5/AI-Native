import type { SseEnvelope } from '../api/types'

function parseSseBlock(block: string): string | null {
  const lines = block.replace(/\r\n/g, '\n').split('\n')
  const dataParts: string[] = []
  for (const raw of lines) {
    const line = raw.trimEnd()
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('data:')) {
      dataParts.push(line.replace(/^data:\s?/, ''))
    }
    // 忽略 event:/id:/retry: 等字段，本项目服务端仅使用默认 message 事件
  }
  if (dataParts.length === 0) return null
  return dataParts.join('\n')
}

/**
 * 通过 fetch 以 POST 方式消费 text/event-stream，并将每条 data JSON 解析为业务事件。
 * 说明：浏览器原生 EventSource 仅支持 GET，因此流式对话使用 fetch + ReadableStream。
 */
export async function postSseJsonStream(
  path: string,
  body: unknown,
  onEvent: (evt: SseEnvelope) => void,
  opts?: { signal?: AbortSignal },
): Promise<void> {
  const res = await fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(body),
    signal: opts?.signal,
  })

  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `HTTP ${res.status}`)
  }
  if (!res.body) {
    throw new Error('响应体不可读（缺少 body）')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const flushEventBlock = (block: string) => {
    const payload = parseSseBlock(block)
    if (!payload) return
    const evt = JSON.parse(payload) as SseEnvelope
    onEvent(evt)
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // Spring 的 SseEmitter 通常以 "\n\n" 分隔事件；为兼容性也支持 "\r\n\r\n"
    buffer = buffer.replace(/\r\n/g, '\n')

    while (true) {
      const idx = buffer.indexOf('\n\n')
      if (idx < 0) break
      const rawEvent = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      flushEventBlock(rawEvent)
    }
  }

  // 处理末尾未以空行结束的情况
  const tail = buffer.trim()
  if (tail.length > 0) {
    flushEventBlock(tail)
  }
}
