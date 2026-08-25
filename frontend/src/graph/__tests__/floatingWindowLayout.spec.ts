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

  it('constrains height when a full-height window would cover the toolbar', () => {
    const toolbar = { x: 320, y: 52, width: 640, height: 83 }
    const result = computeAutoFloatingWindowLayout({
      viewportWidth: 1280,
      viewportHeight: 684,
      state: { x: 976, y: 28, width: 420, height: 640, open: true, positionMode: 'auto' },
      range: { minWidth: 320, maxWidth: 640, minHeight: 260, maxHeight: 820 },
      obstacles: [toolbar],
      protectedObstacles: [toolbar],
    })

    expect(result.height).toBeLessThan(640)
    expect(floatingRectsOverlap(result, toolbar, 16)).toBe(false)
  })

  // Empty-project geometry as measured in the running app: the toolbar is a
  // centered pill and the start-placeholder obstacle is its interactive
  // content column (not the stretched full-canvas container).
  const emptyProjectObstacles = [
    { x: 320, y: 52, width: 640, height: 83 },
    { x: 444, y: 284, width: 392, height: 116 },
  ]

  it('keeps both default windows off the start placeholder and toolbar in an empty project', () => {
    for (const state of [
      { x: 0, y: 72, width: 320, height: 560, open: true, positionMode: 'auto' as const },
      { x: 0, y: 72, width: 420, height: 640, open: true, positionMode: 'auto' as const },
    ]) {
      const result = computeAutoFloatingWindowLayout({
        viewportWidth: 1280,
        viewportHeight: 684,
        state,
        range,
        obstacles: emptyProjectObstacles,
        protectedObstacles: emptyProjectObstacles,
      })
      for (const obstacle of emptyProjectObstacles) {
        expect(floatingRectsOverlap(result, obstacle, 16)).toBe(false)
      }
    }
  })

  it('still avoids obstacles on a small viewport when sufficient space exists', () => {
    const obstacles = [
      { x: 192, y: 52, width: 640, height: 83 },
      { x: 316, y: 318, width: 392, height: 132 },
    ]
    const result = computeAutoFloatingWindowLayout({
      viewportWidth: 1024,
      viewportHeight: 768,
      state: { x: 0, y: 72, width: 420, height: 640, open: true, positionMode: 'auto' },
      range,
      obstacles,
      protectedObstacles: obstacles,
    })
    for (const obstacle of obstacles) {
      expect(floatingRectsOverlap(result, obstacle, 16)).toBe(false)
    }
    expect(result.x).toBeGreaterThanOrEqual(16)
    expect(result.y).toBeGreaterThanOrEqual(16)
    expect(result.x + result.width).toBeLessThanOrEqual(1024 - 16)
    expect(result.y + result.height).toBeLessThanOrEqual(768 - 16)
  })

  it('degrades deterministically and in-bounds when no placement can avoid obstacles', () => {
    const obstacles = [
      { x: 10, y: 52, width: 480, height: 150 },
      { x: 54, y: 240, width: 392, height: 116 },
    ]
    const input = {
      viewportWidth: 500,
      viewportHeight: 400,
      state: { x: 0, y: 72, width: 420, height: 640, open: true, positionMode: 'auto' as const },
      range,
      obstacles,
      protectedObstacles: obstacles,
    }
    const first = computeAutoFloatingWindowLayout(input)
    const second = computeAutoFloatingWindowLayout(input)

    for (const value of [first.x, first.y, first.width, first.height]) {
      expect(Number.isFinite(value)).toBe(true)
    }
    expect(first.x).toBeGreaterThanOrEqual(16)
    expect(first.y).toBeGreaterThanOrEqual(16)
    expect(first.x + first.width).toBeLessThanOrEqual(500 - 16)
    expect(first.y + first.height).toBeLessThanOrEqual(400 - 16)
    expect(second).toEqual(first)
  })
})
