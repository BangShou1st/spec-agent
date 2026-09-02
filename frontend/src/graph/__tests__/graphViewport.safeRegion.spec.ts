import { describe, expect, it } from 'vitest'
import {
  computeFitViewport,
  resolveSafeFitRegion,
  type FitViewportRegion,
  type ViewportNode,
} from '@/graph/graphViewport'

describe('safe fit viewport region', () => {
  const padding = 48
  const canvasWidth = 900
  const canvasHeight = 664
  // Three-node lineage deterministic positions via graphLayout computeInitialLayout:
  // depth 0: (0,0), depth1: (440,0), depth2: (880,0)
  // Current node is tip at 880,0 with measured size 320x286 (as in failing E2E)
  const nodes: ViewportNode[] = [
    { id: 'a', position: { x: 0, y: 0 }, width: 320, height: 286 },
    { id: 'b', position: { x: 440, y: 0 }, width: 320, height: 286 },
    { id: 'c', position: { x: 880, y: 0 }, width: 320, height: 286 },
  ]

  function screenRect(node: ViewportNode, transform: { x: number; y: number; zoom: number }) {
    const w = node.width ?? 320
    const h = node.height ?? 286
    return {
      left: node.position.x * transform.zoom + transform.x,
      top: node.position.y * transform.zoom + transform.y,
      right: node.position.x * transform.zoom + transform.x + w * transform.zoom,
      bottom: node.position.y * transform.zoom + transform.y + h * transform.zoom,
    }
  }

  it('RED: full-canvas fit without safe region leaves no zero-overlap placement for inspector (regression)', () => {
    // Full-canvas centered fit
    const transform = computeFitViewport(nodes, canvasWidth, canvasHeight, { padding })
    expect(transform).not.toBeNull()
    // Simulate inspector preferred 420x640 vs current node screen rect
    // After full-canvas fit, protectedOverlap >0 for any placement -> least-bad overlap
    // Here we just prove fitted graph extends into right side where routes/inspector live
    const currentScreen = screenRect(nodes[2], transform!)
    // With full canvas centering, tip node will be somewhere near center-right,
    // overlapping typical right-anchored routes window at 626,88 258x560
    // We assert it is NOT fully inside left corridor (i.e., extends past 610)
    // This proves without safe region, graph occupies the floating window strip
    expect(currentScreen.right).toBeGreaterThan(610)
  })

  it('deterministic transform inside safe interaction region stays within padded bounds', () => {
    const routes = { x: 626, y: 88, width: 258, height: 560 }
    const inspectorBefore = { x: 290, y: 16, width: 320, height: 225 }
    const safeRegion = resolveSafeFitRegion({
      canvasWidth,
      canvasHeight,
      obstacles: [routes, inspectorBefore],
      gap: 16,
      margin: 16,
    })
    expect(safeRegion.width).toBeGreaterThan(0)
    expect(safeRegion.height).toBeGreaterThan(0)

    const first = computeFitViewport(nodes, canvasWidth, canvasHeight, { padding, region: safeRegion })
    const second = computeFitViewport(nodes, canvasWidth, canvasHeight, { padding, region: safeRegion })
    expect(first).not.toBeNull()
    expect(second).toEqual(first)

    // All fitted nodes must lie inside safeRegion with padding
    for (const node of nodes) {
      const rect = screenRect(node, first!)
      expect(rect.left).toBeGreaterThanOrEqual(safeRegion.x + padding - 0.001)
      expect(rect.top).toBeGreaterThanOrEqual(safeRegion.y + padding - 0.001)
      expect(rect.right).toBeLessThanOrEqual(safeRegion.x + safeRegion.width - padding + 0.001)
      expect(rect.bottom).toBeLessThanOrEqual(safeRegion.y + safeRegion.height - padding + 0.001)
    }
  })

  it('same inputs twice produce exact same transform (determinism)', () => {
    const safeRegion: FitViewportRegion = { x: 0, y: 0, width: 610, height: 664 }
    const a = computeFitViewport(nodes, canvasWidth, canvasHeight, { padding, region: safeRegion })
    const b = computeFitViewport(
      nodes.map(n => ({ ...n, position: { ...n.position } })),
      canvasWidth,
      canvasHeight,
      { padding, region: { ...safeRegion } },
    )
    expect(b).toEqual(a)
  })

  it('safe region picks deterministic largest empty rectangle (tie-break stable)', () => {
    const obstacles = [
      { x: 626, y: 88, width: 258, height: 560 },
    ]
    const first = resolveSafeFitRegion({ canvasWidth, canvasHeight, obstacles, gap: 16 })
    const second = resolveSafeFitRegion({ canvasWidth, canvasHeight, obstacles, gap: 16 })
    expect(second).toEqual(first)
    // For single right-anchored window, largest empty is left strip
    expect(first.x).toBe(0)
    expect(first.width).toBeGreaterThan(400)
  })
})
