import { describe, expect, it } from 'vitest'
import { computeAutoFloatingWindowLayout, floatingRectsOverlap } from '@/graph/floatingWindowLayout'

const range = { minWidth: 320, maxWidth: 640, minHeight: 260, maxHeight: 820 }

describe('floating window auto layout', () => {
  it('moves the inspector away from the active node without a fixed x coordinate', () => {
    const result = computeAutoFloatingWindowLayout({
      viewportWidth: 1280,
      viewportHeight: 720,
      state: { x: 0, y: 72, width: 420, height: 640, open: true, positionMode: 'auto' },
      range,
      obstacles: [{ x: 470, y: 210, width: 320, height: 220 }],
    })

    expect(result.positionMode).toBe('auto')
    expect(floatingRectsOverlap(result, { x: 470, y: 210, width: 320, height: 220 }, 16)).toBe(false)
  })

  it('clamps to a small viewport and chooses the least-obstructed candidate', () => {
    const result = computeAutoFloatingWindowLayout({
      viewportWidth: 800,
      viewportHeight: 600,
      state: { x: 0, y: 72, width: 420, height: 640, open: true, positionMode: 'auto' },
      range,
      obstacles: [{ x: 600, y: 170, width: 150, height: 220 }],
    })

    expect(result.x).toBeGreaterThanOrEqual(16)
    expect(result.y).toBeGreaterThanOrEqual(16)
    expect(result.x + result.width).toBeLessThanOrEqual(800 - 16)
    expect(result.y + result.height).toBeLessThanOrEqual(600 - 16)
    expect(floatingRectsOverlap(result, { x: 600, y: 170, width: 150, height: 220 }, 16)).toBe(false)
  })

  it('uses a constrained side width to keep both windows off the current node', () => {
    const currentNode = { x: 290, y: 227, width: 320, height: 281 }
    const routes = { x: 626, y: 88, width: 258, height: 560 }
    const result = computeAutoFloatingWindowLayout({
      viewportWidth: 900,
      viewportHeight: 664,
      state: { x: 16, y: 16, width: 420, height: 632, open: true, positionMode: 'auto' },
      range,
      obstacles: [currentNode, routes],
      protectedObstacles: [currentNode, routes],
    })

    expect(result.width).toBeLessThan(range.minWidth)
    expect(floatingRectsOverlap(result, currentNode, 16)).toBe(false)
    expect(floatingRectsOverlap(result, routes, 16)).toBe(false)
  })
})
