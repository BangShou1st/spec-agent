// 文件名:formatTime.ts
// 用途:全 UI 统一的绝对时间渲染:所有时间戳固定按中国标准时间(Asia/Shanghai)与稳定的 YYYY-MM-DD HH:mm[:ss] 形状输出,与查看者设备时区无关。
/*
 * 全 UI 的权威绝对时间渲染。
 *
 * 展示给用户的每个时间戳都以中国标准时间(Asia/Shanghai)与稳定的
 * `YYYY-MM-DD HH:mm[:ss]` 形状渲染,与查看者设备时区无关,因此共享屏幕
 * 与截图保持一致。相对表述(刚刚 / N 分钟前)位于
 * `conversationLibrary.formatGaRelativeTime`,不受影响。
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

/*
 * 解析 ISO 时间戳为 Date,让每台机器对输入的解释一致:后端以带时区后缀
 * 的 Instant 序列化(`...Z` / `+08:00`),按绝对时间解析。不带时区的日期
 * 时间字符串(`2026-01-03T14:20:00`)否则会在查看者的本地时区解析,不同
 * 设备渲染不同——因此它被当作 Asia/Shanghai 的墙上时间。纯日期字符串
 * 保留原生解析器行为。
 */
function parseDeterministic(iso: string): Date {
  if (ZONE_AWARE.test(iso) || !(iso.includes('T') || iso.includes(' '))) {
    return new Date(iso)
  }
  return new Date(`${iso}+08:00`)
}

/*
 * 把 ISO 时间戳格式化为 Asia/Shanghai 的 `YYYY-MM-DD HH:mm`(带秒则
 * `:ss`)。空输入渲染为破折号;无法解析的输入原样返回。
 */
export function formatShanghaiDateTime(iso: string, withSeconds = false): string {
  if (!iso) return '—'
  const date = parseDeterministic(iso)
  if (Number.isNaN(date.getTime())) return iso
  const parts = shanghaiParts(date)
  const base = `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
  return withSeconds ? `${base}:${parts.second}` : base
}
