import { describe, expect, it } from 'vitest'
import { computeInitialLayout, placeNewNode, resolvePositions, HORIZONTAL_GAP, VERTICAL_GAP } from '@/features/workspace/graph/graphLayout'
import type { GraphPosition } from '@/features/workspace/graph/graphTypes'

describe('graph layout', () => {
  it('lays out root-to-child with increasing x', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b', parentNodeId: 'a' },
      { id: 'c', parentNodeId: 'b' },
    ]
    const positions = computeInitialLayout(nodes, {})
    expect(positions.a.x).toBeLessThan(positions.b.x)
    expect(positions.b.x).toBeLessThan(positions.c.x)
    expect(positions.a.x).toBe(0)
    expect(positions.b.x).toBe(HORIZONTAL_GAP)
    expect(positions.c.x).toBe(HORIZONTAL_GAP * 2)
  })

  it('gives siblings distinct vertical slots', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b1', parentNodeId: 'a' },
      { id: 'b2', parentNodeId: 'a' },
    ]
    const positions = computeInitialLayout(nodes, {})
    expect(positions.b1.x).toBe(positions.b2.x)
    expect(positions.b1.y).not.toBe(positions.b2.y)
    expect(Math.abs(positions.b1.y - positions.b2.y)).toBe(VERTICAL_GAP)
  })

  it('lets saved coordinates win over computed ones', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b', parentNodeId: 'a' },
    ]
    const saved: Record<string, GraphPosition> = { a: { x: 42, y: 99 } }
    const positions = computeInitialLayout(nodes, saved)
    expect(positions.a).toEqual({ x: 42, y: 99 })
    // the unsaved sibling still gets a computed position
    expect(positions.b.x).toBe(HORIZONTAL_GAP)
  })

  it('placeNewNode returns the parent-right slot when free', () => {
    const parent: GraphPosition = { x: 100, y: 200 }
    const pos = placeNewNode(parent, [])
    expect(pos).toEqual({ x: 100 + HORIZONTAL_GAP, y: 200 })
  })

  it('placeNewNode walks vertical offsets when the direct slot is occupied', () => {
    const parent: GraphPosition = { x: 100, y: 200 }
    const occupied = [{ x: 100 + HORIZONTAL_GAP, y: 200 }]
    const pos = placeNewNode(parent, occupied)
    expect(pos.y).toBe(200 + VERTICAL_GAP)
  })

  it('placeNewNode never mutates the input positions', () => {
    const parent: GraphPosition = { x: 100, y: 200 }
    const occupied: GraphPosition[] = [{ x: 460, y: 200 }]
    const parentBefore = { ...parent }
    const occupiedBefore = occupied.map((p) => ({ ...p }))
    placeNewNode(parent, occupied)
    expect(parent).toEqual(parentBefore)
    expect(occupied).toEqual(occupiedBefore)
  })

  it('placeNewNode skips a slot that a tall neighbour would visually cover', () => {
    const parent: GraphPosition = { x: 100, y: 200 }
    // 上一张卡实测 600px 高：y=200（正对）与 y=460（±1 槽）都会被它盖住，
    // 旧的中心距离规则（< VERTICAL_GAP*0.5 = 130）会误判 260 为可用。
    const occupied = [{ x: 460, y: 200, height: 600 }]
    const pos = placeNewNode(parent, occupied, { height: 150 })
    // 不重叠要求 |dy| >= (150 + 600) / 2 = 375
    expect(Math.abs(pos.y - 200)).toBeGreaterThanOrEqual(375)
  })

  it('placeNewNode keeps the tight slot for uniformly short cards', () => {
    const parent: GraphPosition = { x: 100, y: 200 }
    const occupied = [{ x: 460, y: 200, height: 120 }]
    const pos = placeNewNode(parent, occupied, { height: 120 })
    // 短卡之间仍用最近的一个垂直槽位（与旧行为一致）。
    expect(pos).toEqual({ x: 460, y: 200 + VERTICAL_GAP })
  })

  it('handles missing parents defensively without throwing', () => {
    const nodes = [{ id: 'orphan', parentNodeId: 'ghost' }]
    const positions = computeInitialLayout(nodes, {})
    expect(positions.orphan.x).toBe(0)
  })

  it('keeps the legacy fixed pitch when no measured height is known', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b1', parentNodeId: 'a' },
      { id: 'b2', parentNodeId: 'a' },
      { id: 'b3', parentNodeId: 'a' },
    ]
    const positions = computeInitialLayout(nodes, {})
    expect(positions.b1.y).toBe(0)
    expect(positions.b2.y).toBe(VERTICAL_GAP)
    expect(positions.b3.y).toBe(VERTICAL_GAP * 2)
  })

  it('runs a card that grows with content past its measured height', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'longNote', parentNodeId: 'a' },
      { id: 'shortNote', parentNodeId: 'a' },
    ]
    // 长笔记实测 900px 高：下一张卡必须落在 900 以下，否则会被覆盖。
    const heights: Record<string, number> = { longNote: 900, shortNote: 120 }
    const positions = computeInitialLayout(nodes, {}, {
      heightOf: (id) => heights[id],
    })
    expect(positions.longNote.y).toBe(0)
    expect(positions.shortNote.y).toBe(900 + VERTICAL_GAP)
    expect(positions.shortNote.y).toBeGreaterThan(900)
  })

  it('ignores unusable measured heights and keeps columns independent', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'tall', parentNodeId: 'a' },
      { id: 'next', parentNodeId: 'a' },
      { id: 'childOfTall', parentNodeId: 'tall' },
    ]
    const positions = computeInitialLayout(nodes, {}, {
      // NaN/0/负数 都不可用 -> 退回固定行距
      heightOf: (id) => ({ tall: Number.NaN, next: 0, childOfTall: -50 } as Record<string, number>)[id],
    })
    expect(positions.tall.y).toBe(0)
    expect(positions.next.y).toBe(VERTICAL_GAP)
    // 另一列（depth 2）从 0 起算，不受 depth 1 的高度影响。
    expect(positions.childOfTall.y).toBe(0)
    expect(positions.childOfTall.x).toBe(HORIZONTAL_GAP * 2)
  })

  it('still lets saved coordinates win when measured heights are supplied', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b', parentNodeId: 'a' },
    ]
    const positions = computeInitialLayout(
      nodes,
      { a: { x: 7, y: 8 } },
      { heightOf: () => 500 },
    )
    expect(positions.a).toEqual({ x: 7, y: 8 })
  })
})

describe('resolvePositions (first-ever layout vs incremental refresh)', () => {
  it('computes the first-ever layout deterministically when nothing is saved yet', () => {
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b', parentNodeId: 'a' },
      { id: 'c', parentNodeId: 'b' },
      { id: 'd', parentNodeId: 'b' },
    ]
    const positions = resolvePositions(nodes, {})
    expect(positions).toEqual(computeInitialLayout(nodes, {}))
    expect(positions.a.x).toBeLessThan(positions.b.x)
    expect(positions.b.x).toBeLessThan(positions.c.x)
    expect(positions.c.x).toBe(positions.d.x)
    expect(positions.c.y).not.toBe(positions.d.y)
  })

  it('preserves every existing coordinate and places only brand-new ids incrementally', () => {
    const saved: Record<string, GraphPosition> = {
      a: { x: 10, y: 10 },
      b: { x: 900, y: 600 },
      c: { x: 1260, y: 600 },
    }
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b', parentNodeId: 'a' },
      { id: 'c', parentNodeId: 'b' },
      { id: 'newChild', parentNodeId: 'b' },
    ]
    const positions = resolvePositions(nodes, saved)
    expect(positions.a).toEqual({ x: 10, y: 10 })
    expect(positions.b).toEqual({ x: 900, y: 600 })
    expect(positions.c).toEqual({ x: 1260, y: 600 })
    // 新 child 在手工移动后的 parent 右侧。
    expect(positions.newChild.x).toBe(900 + HORIZONTAL_GAP)
    // 直接槽位被 sibling 占用：向上/下寻找最近可用垂直槽位。
    expect((positions.newChild.y - 600) % VERTICAL_GAP).toBe(0)
    expect(Math.abs(positions.newChild.y - 600)).toBe(VERTICAL_GAP)
  })

  it('a new root without parent lands near the origin rather than inheriting a depth-based slot', () => {
    const saved: Record<string, GraphPosition> = { a: { x: 900, y: 600 } }
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'newRoot', parentNodeId: null },
    ]
    const positions = resolvePositions(nodes, saved)
    expect(positions.a).toEqual({ x: 900, y: 600 })
    expect(positions.newRoot.x).toBe(0)
    expect(positions.newRoot.y).toBe(0)
  })

  it('defensively resolves missing parent chains without throwing', () => {
    const saved: Record<string, GraphPosition> = { a: { x: 0, y: 0 } }
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'ghost', parentNodeId: 'missing' },
      { id: 'childOfGhost', parentNodeId: 'ghost' },
    ]
    const positions = resolvePositions(nodes, saved)
    expect(positions.a).toEqual({ x: 0, y: 0 })
    expect(Number.isFinite(positions.ghost.x)).toBe(true)
    expect(Number.isFinite(positions.ghost.y)).toBe(true)
    expect(Number.isFinite(positions.childOfGhost.x)).toBe(true)
    expect(Number.isFinite(positions.childOfGhost.y)).toBe(true)
  })

  it('never mutates its inputs', () => {
    const saved: Record<string, GraphPosition> = { a: { x: 5, y: 6 } }
    const nodes = [
      { id: 'a', parentNodeId: null },
      { id: 'b', parentNodeId: 'a' },
    ]
    const savedBefore = { ...saved, a: { ...saved.a } }
    const nodesBefore = nodes.map((n) => ({ ...n }))
    resolvePositions(nodes, saved)
    expect(saved).toEqual(savedBefore)
    expect(nodes).toEqual(nodesBefore)
  })
})
