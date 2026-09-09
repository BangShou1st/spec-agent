import type { GaThreadListItem } from '@/api/globalAssistant'

export type GaHistoryGroupKey = 'today' | 'yesterday' | 'last7' | 'earlier'

export interface GaHistoryGroup {
  key: GaHistoryGroupKey
  label: string
  items: GaThreadListItem[]
}

function startOfDay(date: Date): Date {
  const d = new Date(date)
  d.setHours(0, 0, 0, 0)
  return d
}

function diffDays(from: Date, to: Date): number {
  const ms = startOfDay(to).getTime() - startOfDay(from).getTime()
  return Math.round(ms / 86400000)
}

export function groupGaThreads(
  items: GaThreadListItem[],
  now: Date = new Date(),
): GaHistoryGroup[] {
  const today: GaThreadListItem[] = []
  const yesterday: GaThreadListItem[] = []
  const last7: GaThreadListItem[] = []
  const earlier: GaThreadListItem[] = []
  // Backend already returns recency DESC; preserve that order inside groups.
  const seen = new Set<string>()
  for (const item of items) {
    if (!item || !item.threadId || seen.has(item.threadId)) continue
    seen.add(item.threadId)
    const updated = new Date(item.updatedAt)
    if (Number.isNaN(updated.getTime())) {
      earlier.push(item)
      continue
    }
    const age = diffDays(updated, now)
    if (age <= 0) today.push(item)
    else if (age === 1) yesterday.push(item)
    else if (age <= 7) last7.push(item)
    else earlier.push(item)
  }
  const groups: GaHistoryGroup[] = []
  if (today.length > 0) groups.push({ key: 'today', label: '今天', items: today })
  if (yesterday.length > 0) groups.push({ key: 'yesterday', label: '昨天', items: yesterday })
  if (last7.length > 0) groups.push({ key: 'last7', label: '最近 7 天', items: last7 })
  if (earlier.length > 0) groups.push({ key: 'earlier', label: '更早', items: earlier })
  return groups
}

export function formatGaRelativeTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const diffMs = now.getTime() - date.getTime()
  if (diffMs < 0) return '刚刚'
  const minutes = Math.floor(diffMs / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return minutes + ' 分钟前'
  const hours = Math.floor(minutes / 60)
  if (hours < 24 && startOfDay(date).getTime() === startOfDay(now).getTime()) return hours + ' 小时前'
  const age = diffDays(date, now)
  if (age === 1) return '昨天'
  if (age <= 7) return age + ' 天前'
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return month + '-' + day
}

export function currentGaTitle(
  threadId: string | null,
  threads: GaThreadListItem[],
  fallback = '新对话',
): string {
  if (!threadId) return fallback
  const found = threads.find((t) => t.threadId === threadId)
  if (!found || !found.title) return fallback
  return found.title
}
