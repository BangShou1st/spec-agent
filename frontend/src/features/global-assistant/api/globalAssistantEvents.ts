// 文件名:globalAssistantEvents.ts
// 用途:全局助手运行的 SSE 事件流传输层:基于 fetch 流式读取解析 SSE 帧,
//       支持从指定 sequence 游标重放(Last-Event-ID)、收到终止事件后自动关闭,
//       传输断开绝不取消运行本身。

import { isGaTerminalEventType, type GaEventEnvelope } from './globalAssistant'
import { API_BASE_URL } from '@/shared/http/client'

export interface GaStreamHandlers {
  onEvent: (event: GaEventEnvelope) => void
  onError?: (err: Error) => void
  onClose?: () => void
}

export interface ParsedSse {
  events: Array<{ id: string; name: string; data: string }>
  rest: string
}

/** 纯函数的 SSE 帧解析器(便于测试):按空行分帧。 */
export function parseSseBuffer(buffer: string): ParsedSse {
  const events: Array<{ id: string; name: string; data: string }> = []
  // 末尾不完整的帧留在 rest 中,等待下次数据到达后补全。
  const parts = buffer.split(/\r?\n\r?\n/)
  const rest = parts.pop() ?? ''
  for (const part of parts) {
    if (!part.trim()) continue
    // 心跳注释帧只用于保活,不作为事件处理。
    if (part.trimStart().startsWith(':')) continue
    let id = ''
    let name = ''
    const dataLines: string[] = []
    for (const line of part.split(/\r?\n/)) {
      if (line.startsWith('id:')) id = line.slice(3).trim()
      else if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    if (dataLines.length === 0) continue
    events.push({ id, name, data: dataLines.join('\n') })
  }
  return { events, rest }
}

/** 将一段 SSE data 载荷解析为类型化信封;无法识别时返回 null。 */
export function parseGaEnvelope(data: string): GaEventEnvelope | null {
  try {
    const parsed = JSON.parse(data) as Partial<GaEventEnvelope>
    if (!parsed || typeof parsed !== 'object') return null
    if (typeof parsed.type !== 'string') return null
    if (typeof parsed.sequence !== 'number') return null
    return parsed as GaEventEnvelope
  } catch {
    return null
  }
}

/**
 * 从指定 sequence 游标打开全局助手的 SSE 事件流。
 * - 请求携带 Last-Event-ID 以支持重放。
 * - 收到终止事件后自动关闭。
 * - 传输层断开时绝不取消运行本身。
 */
export function openGaEventStream(
  runId: string,
  fromSequence: number,
  handlers: GaStreamHandlers,
  externalSignal?: AbortSignal,
): { close: () => void; done: Promise<void> } {
  const controller = new AbortController()
  const link = (s: AbortSignal, fn: () => void): void => {
    if (s.aborted) fn()
    else s.addEventListener('abort', fn, { once: true })
  }
  if (externalSignal) link(externalSignal, () => controller.abort())
  let closed = false
  const close = (): void => {
    if (closed) return
    closed = true
    controller.abort()
    handlers.onClose?.()
  }
  const done = (async (): Promise<void> => {
    let response: Response
    try {
      response = await fetch(API_BASE_URL + '/global-assistant/runs/' + runId + '/events', {
        method: 'GET',
        headers: { Accept: 'text/event-stream', 'Last-Event-ID': String(Math.max(0, fromSequence)) },
        signal: controller.signal,
      })
    } catch (err) {
      if (controller.signal.aborted || closed) return
      handlers.onError?.(err instanceof Error ? err : new Error('stream failed'))
      return
    }
    if (!response.ok || !response.body) {
      if (!closed) handlers.onError?.(new Error('stream unavailable: ' + response.status))
      return
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      for (;;) {
        const { done: readerDone, value } = await reader.read()
        if (readerDone) break
        buffer += decoder.decode(value, { stream: true })
        const parsed = parseSseBuffer(buffer)
        buffer = parsed.rest
        for (const frame of parsed.events) {
          const envelope = parseGaEnvelope(frame.data)
          if (!envelope) continue
          handlers.onEvent(envelope)
          if (isGaTerminalEventType(envelope.type)) {
            close()
            return
          }
        }
        if (closed || controller.signal.aborted) return
      }
      buffer += decoder.decode()
      if (buffer.trim()) {
        const parsed = parseSseBuffer(buffer + '\n\n')
        for (const frame of parsed.events) {
          const envelope = parseGaEnvelope(frame.data)
          if (!envelope) continue
          handlers.onEvent(envelope)
          if (isGaTerminalEventType(envelope.type)) {
            close()
            return
          }
        }
      }
    } catch (err) {
      if (controller.signal.aborted || closed) return
      handlers.onError?.(err instanceof Error ? err : new Error('stream failed'))
      return
    }
    if (!closed) handlers.onError?.(new Error('stream closed'))
  })()
  return { close, done }
}

