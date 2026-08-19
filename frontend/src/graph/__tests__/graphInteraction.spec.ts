import { describe, expect, it } from 'vitest'
import { resolveRouteFocusIntent } from '@/graph/graphInteraction'

describe('resolveRouteFocusIntent', () => {
  it('focuses the only visible route on an element whose canonical membership is shared', () => {
    // The caller supplies presentation membership here; canonical routeIds
    // remain available on the graph element but are not used for Focus.
    expect(resolveRouteFocusIntent(['A'], null)).toBe('A')
  })

  it('keeps the current Focus on a shared element', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], 'B')).toBe('B')
  })

  it('keeps a shared element neutral when Focus is absent, regardless of Active', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], null)).toBeNull()
  })

  it('returns null when Focus is not a member', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], 'C')).toBeNull()
  })

  it('never falls back to first or latest route', () => {
    expect(resolveRouteFocusIntent(['A', 'B'], null)).toBeNull()
  })
})
