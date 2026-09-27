// 文件名:graphViewport.spec.ts
// 用途:视口辅助函数的单元测试:验证兜底/实测尺寸、包围盒合并、fit 与单节点居中的确定性数学以及 maxZoom 钳制。
import { describe, expect, it } from 'vitest'
import {
  computeBounds,
  computeFitNodeViewport,
  computeFitViewport,
  getNodeSize,
  FALLBACK_NODE_HEIGHT,
  FALLBACK_NODE_WIDTH,
} from '@/features/workspace/graph/graphViewport'
import type { ViewportNode } from '@/features/workspace/graph/graphViewport'

/*
 * 确定性的视口辅助函数:transform 完全由当前投影的节点坐标加已知/安全
 * 兜底尺寸计算,绝不查询 Vue Flow 的节点测量或 `fitView` 状态,因此无论
 * 画布是否已完成节点测量,结果都完全一致。
 */
describe('graph viewport helpers', () => {
  it('uses fallback dimensions when a node has no measured size', () => {
    const node: ViewportNode = { id: 'a', position: { x: 10, y: 20 } }
    expect(getNodeSize(node)).toEqual({
      width: FALLBACK_NODE_WIDTH,
      height: FALLBACK_NODE_HEIGHT,
    })
    const bounds = computeBounds([node])
    expect(bounds).toEqual({
      minX: 10,
      minY: 20,
      maxX: 10 + FALLBACK_NODE_WIDTH,
      maxY: 20 + FALLBACK_NODE_HEIGHT,
    })
  })

  it('prefers measured dimensions over fallbacks and ignores non-positive sizes', () => {
    const measured: ViewportNode = {
      id: 'a',
      position: { x: 0, y: 0 },
      width: 420,
      height: 300,
    }
    expect(getNodeSize(measured)).toEqual({ width: 420, height: 300 })
    const bounds = computeBounds([measured])
    expect(bounds?.maxX).toBe(420)
    expect(bounds?.maxY).toBe(300)

    const invalid: ViewportNode = { id: 'b', position: { x: 0, y: 0 }, width: 0, height: -5 }
    expect(getNodeSize(invalid)).toEqual({
      width: FALLBACK_NODE_WIDTH,
      height: FALLBACK_NODE_HEIGHT,
    })
  })

  it('computeBounds merges several node boxes into one bounding rect', () => {
    const bounds = computeBounds([
      { id: 'a', position: { x: 0, y: 0 }, width: 100, height: 50 },
      { id: 'b', position: { x: 900, y: 600 }, width: 200, height: 100 },
    ])
    expect(bounds).toEqual({ minX: 0, minY: 0, maxX: 1100, maxY: 700 })
  })

  it('returns null for empty node lists and missing canvas dimensions', () => {
    expect(computeBounds([])).toBeNull()
    expect(computeFitViewport([], 1200, 800)).toBeNull()
    expect(
      computeFitViewport([{ id: 'a', position: { x: 0, y: 0 } }], 0, 800),
    ).toBeNull()
    expect(
      computeFitViewport([{ id: 'a', position: { x: 0, y: 0 } }], 1200, 0),
    ).toBeNull()
  })

  it('centers a single node at zoom 1 when the canvas is large enough', () => {
    const transform = computeFitViewport(
      [{ id: 'a', position: { x: 360, y: 0 } }],
      1200,
      800,
      { padding: 48 },
    )
    expect(transform).not.toBeNull()
    // 包围盒:x 360..680,y 0..220 → 中心 (520, 110);zoom 钳制到 1。
    expect(transform?.zoom).toBe(1)
    expect(transform?.x).toBe(1200 / 2 - 520 * 1)
    expect(transform?.y).toBe(800 / 2 - 110 * 1)
  })

  it('shrinks zoom to fit wide graphs instead of overflowing', () => {
    const transform = computeFitViewport(
      [
        { id: 'a', position: { x: 0, y: 0 } },
        { id: 'b', position: { x: 360, y: 220 } },
        { id: 'c', position: { x: 720, y: 440 } },
        { id: 'd', position: { x: 1080, y: 660 } },
      ],
      1200,
      800,
      { padding: 48 },
    )
    expect(transform).not.toBeNull()
    // 内容盒子:x 0..1400,y 0..880
    const expectedZoom = Math.min(
      (1200 - 96) / 1400,
      (800 - 96) / 880,
      1,
    )
    expect(transform?.zoom).toBeCloseTo(expectedZoom, 6)
    // 视口把内容盒子居中
    const centerX = 700
    const centerY = 440
    expect(transform?.x).toBeCloseTo(1200 / 2 - centerX * (transform?.zoom ?? 1), 6)
    expect(transform?.y).toBeCloseTo(800 / 2 - centerY * (transform?.zoom ?? 1), 6)
  })

  it('caps the zoom at maxZoom even for a tiny content box', () => {
    const transform = computeFitViewport(
      [{ id: 'a', position: { x: 0, y: 0 }, width: 10, height: 10 }],
      1200,
      800,
      { padding: 0, maxZoom: 1 },
    )
    expect(transform?.zoom).toBe(1)
  })

  it('computeFitNodeViewport centers exactly the requested node', () => {
    const transform = computeFitNodeViewport(
      { id: 'n', position: { x: 900, y: 600 }, width: 320, height: 220 },
      1200,
      800,
      { padding: 48 },
    )
    expect(transform).not.toBeNull()
    expect(transform?.zoom).toBe(1)
    expect(transform?.x).toBe(1200 / 2 - (900 + 160))
    expect(transform?.y).toBe(800 / 2 - (600 + 110))
  })

  it('is fully deterministic: identical inputs produce identical transforms', () => {
    const nodes = [
      { id: 'a', position: { x: 10, y: 20 } },
      { id: 'b', position: { x: 370, y: 240 } },
    ]
    const first = computeFitViewport(nodes, 1200, 800)
    const second = computeFitViewport(nodes.map((n) => ({ ...n })), 1200, 800)
    expect(second).toEqual(first)
  })
})
