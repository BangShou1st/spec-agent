import { describe, expect, it } from 'vitest'
import { resolveRouteFocusIntent, resolveReadingRouteId } from '@/graph/graphInteraction'

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

describe('resolveReadingRouteId（当前查看的唯一真源）', () => {
  it('显式 Focus 命中归属时直接生效（只看这条路线 / 点卡片）', () => {
    expect(resolveReadingRouteId({
      membershipRouteIds: ['A', 'B'],
      visibleRouteIds: ['A', 'B'],
      focusRouteId: 'B',
    })).toBe('B')
  })

  it('无 Focus 但只有一条归属可见时自动确定（隐藏/筛选让歧义消失）', () => {
    expect(resolveReadingRouteId({
      membershipRouteIds: ['A', 'B'],
      visibleRouteIds: ['B'],
      focusRouteId: null,
    })).toBe('B')
  })

  it('单归属节点始终确定', () => {
    expect(resolveReadingRouteId({
      membershipRouteIds: ['A'],
      visibleRouteIds: ['A'],
      focusRouteId: null,
    })).toBe('A')
  })

  it('真正歧义（多归属都可见且无 Focus）返回 null，由卡片显式询问', () => {
    expect(resolveReadingRouteId({
      membershipRouteIds: ['A', 'B'],
      visibleRouteIds: ['A', 'B'],
      focusRouteId: null,
    })).toBeNull()
  })

  it('Focus 不属于该节点时忽略，绝不借 Active/first/latest 兜底', () => {
    expect(resolveReadingRouteId({
      membershipRouteIds: ['A', 'B'],
      visibleRouteIds: ['A', 'B'],
      focusRouteId: 'C',
    })).toBeNull()
  })

  it('浮动节点（无归属）恒为 null', () => {
    expect(resolveReadingRouteId({
      membershipRouteIds: [],
      visibleRouteIds: ['A'],
      focusRouteId: 'A',
    })).toBeNull()
  })
})
