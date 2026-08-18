import { describe, expect, it } from 'vitest'
import { computeInitialLayout, placeNewNode, HORIZONTAL_GAP, VERTICAL_GAP } from '@/graph/graphLayout'
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
