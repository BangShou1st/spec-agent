// 文件名:managementCopy.spec.ts
// 用途:managementCopy 的单元测试:验证后端枚举到产品文案的映射,以及字节/日期的确定性格式化(无时区输入按上海墙上时间解释)。
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
    // 不带时区的输入按 Asia/Shanghai 墙上时间解释。
    expect(formatDateTime('2026-01-03T14:20:00')).toBe('2026-01-03 14:20')
    // 后端的 UTC instant 以上海时间(+8)渲染。
    expect(formatDateTime('2026-01-03T14:20:00Z')).toBe('2026-01-03 22:20')
    expect(formatDateTime('2026-01-03T14:20:00+08:00')).toBe('2026-01-03 14:20')
  })
})
