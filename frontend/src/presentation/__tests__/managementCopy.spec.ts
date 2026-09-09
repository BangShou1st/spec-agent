import { describe, expect, it } from 'vitest'
import { connectionKindLabel, formatBytes, formatDateTime, skillSourceLabel } from '@/presentation/managementCopy'

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
    expect(formatDateTime('2026-01-03T14:20:00')).toBe('2026-01-03 14:20')
  })
})
