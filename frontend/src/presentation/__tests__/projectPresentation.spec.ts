import { describe, expect, it } from 'vitest'
import { formatProjectCreatedAt } from '@/presentation/projectPresentation'

describe('formatProjectCreatedAt', () => {
  it('formats an ISO timestamp as a readable local date time', () => {
    expect(formatProjectCreatedAt('2026-01-01T00:00:00Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
  })

  it('never leaks a raw ISO timestamp', () => {
    expect(formatProjectCreatedAt('2026-01-01T00:00:00Z')).not.toContain('T')
  })

  it('falls back safely for empty or invalid input', () => {
    expect(formatProjectCreatedAt('')).toBe('—')
    expect(formatProjectCreatedAt('not-a-date')).toBe('not-a-date')
  })
})
