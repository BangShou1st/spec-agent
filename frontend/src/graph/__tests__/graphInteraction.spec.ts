import { describe, expect, it } from 'vitest'
import { resolveRouteFocusIntent } from '@/graph/graphInteraction'

describe('resolveRouteFocusIntent', () => {
  it('focuses the only route on an exclusive element', () => {
    expect(resolveRouteFocusIntent(['A'], null, 'C')).toBe('A')
  })

  it('keeps the current Focus on a shared element', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], 'B', 'A')).toBe('B')
  })

  it('uses Active only when it is a shared-element member', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], null, 'A')).toBe('A')
  })

  it('returns null when neither Focus nor Active is a member', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], null, 'C')).toBeNull()
  })

  it('never falls back to the first shared route', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], 'C', 'D')).toBeNull()
  })
})
