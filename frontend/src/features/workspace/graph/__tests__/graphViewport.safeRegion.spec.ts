// 文件名:graphViewport.safeRegion.spec.ts
// 用途:安全适配区域(resolveSafeFitRegion + 区域内 fit)的单元测试:验证避开浮动面板的最大空矩形、区域约束下的确定性 transform 与平局稳定。
import { describe, expect, it } from 'vitest'
import {
  computeFitViewport,
  resolveSafeFitRegion,
  type FitViewportRegion,
  type ViewportNode,
} from '@/features/workspace/graph/graphViewport'

describe('safe fit viewport region', () => {
  const padding = 48
  const canvasWidth = 900
  const canvasHeight = 664
  // 三个节点的谱系,坐标由 graphLayout computeInitialLayout 确定性给出:
  // depth 0: (0,0), depth1: (440,0), depth2: (880,0)
  // 当前节点是位于 880,0 的末端节点,实测尺寸 320x286(与失败的 E2E 一致)
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
    // 全画布居中适配
    const transform = computeFitViewport(nodes, canvasWidth, canvasHeight, { padding })
    expect(transform).not.toBeNull()
    // 模拟 inspector 偏好的 420x640 与当前节点的屏幕矩形:
    // 全画布 fit 后,任何摆放都会有 protectedOverlap > 0 → 只能选最不坏的
    // 重叠。这里只证明适配后的图延伸到了 routes/inspector 所在的右侧。
    const currentScreen = screenRect(nodes[2], transform!)
    // 全画布居中时,末端节点会在中偏右的位置,与典型的右锚定 routes 窗口
    // (626,88 258x560)重叠。断言它不完全在左侧走廊内(即延伸超过 610),
    // 从而证明没有安全区域时,图会占据浮动窗口的条带。
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

    // 所有适配后的节点都必须带 padding 地落在安全区域内
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
    // 单个右锚定窗口时,最大空矩形是左侧条带
    expect(first.x).toBe(0)
    expect(first.width).toBeGreaterThan(400)
  })
})
