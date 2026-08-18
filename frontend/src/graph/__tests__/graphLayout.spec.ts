import { describe, expect, it } from 'vitest'
import { computeInitialLayout, placeNewNode, resolvePositions, HORIZONTAL_GAP, VERTICAL_GAP } from '@/graph/graphLayout'
import type { GraphPosition } from '@/graph/graphTypes'

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

  it('handles missing parents defensively without throwing', () => {
    const nodes = [{ id: 'orphan', parentNodeId: 'ghost' }]
    const positions = computeInitialLayout(nodes, {})
    expect(positions.orphan.x).toBe(0)
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
