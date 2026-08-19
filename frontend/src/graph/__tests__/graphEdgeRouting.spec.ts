import { describe, expect, it } from 'vitest'
import {
  selectEdgeHandles,
  HORIZONTAL_DOMINANCE_FACTOR,
  GRAPH_NODE_WIDTH,
  GRAPH_NODE_HEIGHT,
  FALLBACK_NODE_WIDTH,
  FALLBACK_NODE_HEIGHT,
  type NodeGeometry,
} from '@/graph/graphEdgeRouting'

/**
 * Adaptive anchored handle selection (edge routing):
 *
 * source/target node geometry -> sourceHandle + targetHandle.
 *
 * The selector is a pure function: it takes only geometry (position +
 * optional measured size) and returns the two handle ids. It must never
 * know about Runtime semantics, routes, answers or persistence.
 */

function geometry(overrides: Partial<NodeGeometry> = {}): NodeGeometry {
  return { position: { x: 0, y: 0 }, ...overrides }
}

const RIGHT = { sourceHandle: 'source-right', targetHandle: 'target-left' }
const LEFT = { sourceHandle: 'source-left', targetHandle: 'target-right' }
const BELOW = { sourceHandle: 'source-bottom', targetHandle: 'target-top' }
const ABOVE = { sourceHandle: 'source-top', targetHandle: 'target-bottom' }

describe('graph edge routing: adaptive anchored handle selection', () => {
  it('target to the right -> source-right / target-left', () => {
    const source = geometry()
    const target = geometry({ position: { x: 360, y: 0 } })
    expect(selectEdgeHandles(source, target)).toEqual(RIGHT)
  })

  it('target to the left -> source-left / target-right', () => {
    const source = geometry()
    const target = geometry({ position: { x: -400, y: 0 } })
    expect(selectEdgeHandles(source, target)).toEqual(LEFT)
  })

  it('target below -> source-bottom / target-top', () => {
    const source = geometry()
    const target = geometry({ position: { x: 0, y: 300 } })
    expect(selectEdgeHandles(source, target)).toEqual(BELOW)
  })

  it('target above -> source-top / target-bottom', () => {
    const source = geometry()
    const target = geometry({ position: { x: 0, y: -300 } })
    expect(selectEdgeHandles(source, target)).toEqual(ABOVE)
  })

  it('near-diagonal keeps the left-to-right reading habit (horizontal wins)', () => {
    // |dx| = 360 >= |dy| * 0.8 = 240 -> horizontal even though dy is large.
    const source = geometry()
    const diagonal = geometry({ position: { x: 360, y: 300 } })
    expect(selectEdgeHandles(source, diagonal)).toEqual(RIGHT)
    // Small vertical bias must never flip the LTR direction.
    const slightVertical = geometry({ position: { x: 360, y: 80 } })
    expect(selectEdgeHandles(source, slightVertical)).toEqual(RIGHT)
  })

  it('the threshold flip is deterministic, never random', () => {
    const source = geometry()
    // Past the threshold: vertical, target below -> bottom/top.
    const below = geometry({ position: { x: 360, y: 500 } })
    expect(selectEdgeHandles(source, below)).toEqual(BELOW)
    // Exact boundary stays horizontal (abs(dx) >= abs(dy) * 0.8).
    expect(selectEdgeHandles(source, geometry({ position: { x: 240, y: 300 } }))).toEqual(RIGHT)
    expect(selectEdgeHandles(source, geometry({ position: { x: 241, y: 300 } }))).toEqual(RIGHT)
    // One unit past the boundary flips to vertical.
    expect(selectEdgeHandles(source, geometry({ position: { x: 239, y: 300 } }))).toEqual(BELOW)
  })

  it('vertical classification uses centers too (same threshold mirrored)', () => {
    const source = geometry()
    // dy = -300, small dx: |dx| = 100 < 300 * 0.8 -> vertical, target above.
    const above = geometry({ position: { x: 100, y: -300 } })
    expect(selectEdgeHandles(source, above)).toEqual(ABOVE)
    // Stronger horizontal pull keeps LTR even when the target sits higher.
    const higherButRight = geometry({ position: { x: 360, y: -300 } })
    expect(selectEdgeHandles(source, higherButRight)).toEqual(RIGHT)
  })

  it('uses one stable outer footprint for historical and current nodes', () => {
    expect(GRAPH_NODE_WIDTH).toBe(320)
    expect(GRAPH_NODE_HEIGHT).toBe(220)
    expect(FALLBACK_NODE_WIDTH).toBe(GRAPH_NODE_WIDTH)
    expect(FALLBACK_NODE_HEIGHT).toBe(GRAPH_NODE_HEIGHT)

    const historical: NodeGeometry = {
      position: { x: 0, y: 0 },
      width: GRAPH_NODE_WIDTH,
      height: GRAPH_NODE_HEIGHT,
    }
    const current: NodeGeometry = {
      position: { x: 360, y: 0 },
      width: GRAPH_NODE_WIDTH,
      height: GRAPH_NODE_HEIGHT,
    }
    expect(selectEdgeHandles(historical, current)).toEqual(RIGHT)
  })

  it('falls back to safe dimensions when a node has no measured size', () => {
    const source = geometry()
    const target = geometry({ position: { x: 360, y: 0 } })
    const handles = selectEdgeHandles(source, target)
    expect(handles).toEqual(RIGHT)
    expect(FALLBACK_NODE_WIDTH).toBeGreaterThan(0)
    expect(FALLBACK_NODE_HEIGHT).toBeGreaterThan(0)
    expect(HORIZONTAL_DOMINANCE_FACTOR).toBe(0.8)
  })

  it('degenerate overlapping centers resolve deterministically to the LTR default', () => {
    expect(selectEdgeHandles(geometry(), geometry())).toEqual(RIGHT)
  })

  it('does not mutate its inputs', () => {
    const source = geometry()
    const target = geometry({ position: { x: 360, y: 200 } })
    const sourceBefore = JSON.stringify(source)
    const targetBefore = JSON.stringify(target)
    selectEdgeHandles(source, target)
    expect(JSON.stringify(source)).toBe(sourceBefore)
    expect(JSON.stringify(target)).toBe(targetBefore)
  })
})
