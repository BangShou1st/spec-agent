import { describe, expect, it } from 'vitest'
import { currentGaTitle, formatGaRelativeTime, groupGaThreads } from '@/presentation/conversationLibrary'
import type { GaThreadListItem } from '@/api/globalAssistant'

function item(threadId: string, updatedAt: string, title = 't-' + threadId): GaThreadListItem {
  return { threadId, title, preview: 'p-' + threadId, updatedAt, createdAt: updatedAt }
}

describe('conversation library presentation', () => {
  it('groups threads into today, yesterday, last7 and earlier', () => {
    const now = new Date('2026-09-10T12:00:00')
    const items = [
      item('today-1', '2026-09-10T10:00:00Z'),
      item('yesterday-1', '2026-09-09T10:00:00Z'),
      item('week-1', '2026-09-05T10:00:00Z'),
      item('old-1', '2026-08-01T10:00:00Z'),
    ]
    const groups = groupGaThreads(items, now)
    expect(groups.map((g) => g.key)).toEqual(['today', 'yesterday', 'last7', 'earlier'])
    expect(groups[0].items[0].threadId).toBe('today-1')
  })

  it('never duplicates threads and preserves backend order', () => {
    const now = new Date('2026-09-10T12:00:00')
    const dup = item('dup', '2026-09-10T10:00:00Z')
    const groups = groupGaThreads([dup, dup, item('other', '2026-09-10T09:00:00Z')], now)
    const flat = groups.flatMap((g) => g.items)
    expect(flat).toHaveLength(2)
    expect(flat[0].threadId).toBe('dup')
  })

  it('formats relative time compactly', () => {
    const now = new Date('2026-09-10T12:00:00Z')
    expect(formatGaRelativeTime('2026-09-10T11:59:30Z', now)).toBe('刚刚')
    expect(formatGaRelativeTime('2026-09-10T11:30:00Z', now)).toContain('分钟前')
    expect(formatGaRelativeTime('2026-09-09T10:00:00Z', now)).toBe('昨天')
  })

  it('resolves current title with new-conversation fallback', () => {
    const items = [item('t-1', '2026-09-10T10:00:00Z', '帮我找支付项目')]
    expect(currentGaTitle('t-1', items)).toBe('帮我找支付项目')
    expect(currentGaTitle(null, items)).toBe('新对话')
    expect(currentGaTitle('missing', items)).toBe('新对话')
  })
})
