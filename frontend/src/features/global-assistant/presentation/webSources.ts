import type { GaResourceRef } from '../state/globalAssistantStore'

export function safeWebUrl(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 2000) return null
  try {
    const url = new URL(value)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null
    const host = url.hostname.toLowerCase()
    if (!host.includes('.') || host === 'localhost' || /\.(localhost|local|internal)$/.test(host)
      || /^(127\.|10\.|0\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host)) return null
    return url.href
  } catch { return null }
}

/** A citation is resolved only against this run's successful host events. */
export function resolveWebCitations(content: string, sources: GaResourceRef[]): string {
  const known = new Map(sources.filter(s => s.kind === 'WEB_SOURCE').map(s => [s.id, safeWebUrl(s.metadata?.url)]))
  return content.replace(/\[web:([0-9a-f-]{36})\]/gi, (_marker, id: string) => {
    const url = known.get(id)
    return url ? `[网页来源](<${url.replace(/>/g, '%3E')}>)` : '［网页来源未核验］'
  })
}
