/**
 * Canonical absolute-time rendering for the whole UI.
 *
 * Every timestamp shown to the user is rendered in China Standard Time
 * (Asia/Shanghai) with a stable `YYYY-MM-DD HH:mm[:ss]` shape, independent of
 * the viewer's device timezone, so shared screens and screenshots stay
 * consistent. Relative phrasing (刚刚 / N 分钟前) lives in
 * `conversationLibrary.formatGaRelativeTime` and is unaffected.
 */

function shanghaiParts(date: Date): Record<string, string> {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  const map: Record<string, string> = {}
  for (const part of formatter.formatToParts(date)) {
    map[part.type] = part.value
  }
  return map
}

const ZONE_AWARE = /[zZ]$|[+-]\d{2}:?\d{2}$/

/**
 * Resolves the Date for an ISO timestamp, interpreting the input the same way
 * on every machine: the backend serializes Instants with a zone suffix
 * (`...Z` / `+08:00`), which parse absolutely. A zone-less datetime string
 * (`2026-01-03T14:20:00`) would otherwise be parsed in the VIEWER's local
 * timezone and render differently per device — it is therefore treated as
 * Asia/Shanghai wall time. Date-only strings keep the native parser behavior.
 */
function parseDeterministic(iso: string): Date {
  if (ZONE_AWARE.test(iso) || !(iso.includes('T') || iso.includes(' '))) {
    return new Date(iso)
  }
  return new Date(`${iso}+08:00`)
}

/**
 * Formats an ISO timestamp as `YYYY-MM-DD HH:mm` (or `:ss` with seconds) in
 * Asia/Shanghai. Empty input renders as an em dash; unparseable input is
 * echoed back untouched.
 */
export function formatShanghaiDateTime(iso: string, withSeconds = false): string {
  if (!iso) return '—'
  const date = parseDeterministic(iso)
  if (Number.isNaN(date.getTime())) return iso
  const parts = shanghaiParts(date)
  const base = `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
  return withSeconds ? `${base}:${parts.second}` : base
}
