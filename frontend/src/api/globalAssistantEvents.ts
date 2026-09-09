import { isGaTerminalEventType, type GaEventEnvelope } from './globalAssistant'

/** Focused fetch-stream SSE transport for Global Assistant runs. */

export interface GaStreamHandlers {
  onEvent: (event: GaEventEnvelope) => void
  onError?: (err: Error) => void
  onClose?: () => void
}

const API_BASE = (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_BASE_URL ?? '/api/v1'

export interface ParsedSse {
  events: Array<{ id: string; name: string; data: string }>
  rest: string
}

/** Pure SSE frame parser (testable): splits on blank lines. */
export function parseSseBuffer(buffer: string): ParsedSse {
  const events: Array<{ id: string; name: string; data: string }> = []
  // Keep the trailing incomplete frame in rest.
  const parts = buffer.split(/\r?\n\r?\n/)
  const rest = parts.pop() ?? ''
  for (const part of parts) {
    if (!part.trim()) continue
    // Heartbeat comments keep the connection alive.
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

/** Parses one SSE data payload into a typed envelope; null when unknown. */
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
 * Opens a Global Assistant SSE stream from a sequence cursor.
 * - Sends Last-Event-ID for replay.
 * - Closes automatically on terminal events.
 * - Never cancels the run on transport disconnect.
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
      response = await fetch(API_BASE + '/global-assistant/runs/' + runId + '/events', {
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

