import { describe, expect, it } from 'vitest'
import { connectionKindLabel, formatBytes, formatDateTime, skillSourceLabel } from '@/shared/lib/managementCopy'

describe('managementCopy', () => {
  it('maps raw backend enums to product copy', () => {
    expect(skillSourceLabel('BUILTIN')).toBe('内置')
    expect(skillSourceLabel('GIT')).toBe('Git')
    expect(skillSourceLabel('UPLOAD_ZIP')).toBe('ZIP')
    expect(skillSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(connectionKindLabel('CUSTOM_MCP')).toBe('自定义连接')
  })

  it('formats sizes and datetimes deterministically', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    // Zone-less input is interpreted as Asia/Shanghai wall time.
    expect(formatDateTime('2026-01-03T14:20:00')).toBe('2026-01-03 14:20')
    // UTC instant from the backend renders in Shanghai (+8).
    expect(formatDateTime('2026-01-03T14:20:00Z')).toBe('2026-01-03 22:20')
    expect(formatDateTime('2026-01-03T14:20:00+08:00')).toBe('2026-01-03 14:20')
  })
})
