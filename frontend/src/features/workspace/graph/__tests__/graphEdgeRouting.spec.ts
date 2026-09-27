// 文件名:graphEdgeRouting.spec.ts
// 用途:自适应锚点边路由的单元测试:验证 handle 选择的方向确定性、阈值翻转、兜底尺寸与纯函数不修改入参。
import { describe, expect, it } from 'vitest'
import {
  selectEdgeHandles,
  HORIZONTAL_DOMINANCE_FACTOR,
  GRAPH_NODE_WIDTH,
  GRAPH_NODE_HEIGHT,
  FALLBACK_NODE_WIDTH,
  FALLBACK_NODE_HEIGHT,
  type NodeGeometry,
} from '@/features/workspace/graph/graphEdgeRouting'

/*
 * 自适应锚点 handle 选择(边路由):
 *
 * 源/目标节点几何 → sourceHandle + targetHandle。
 *
 * 选择器是纯函数:只接收几何(位置 + 可选实测尺寸),返回两个 handle id。
 * 它绝不能知道运行时语义、路线、回答或持久化。
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
    // |dx| = 360 >= |dy| * 0.8 = 240 → 横向,即使 dy 很大。
    const source = geometry()
    const diagonal = geometry({ position: { x: 360, y: 300 } })
    expect(selectEdgeHandles(source, diagonal)).toEqual(RIGHT)
    // 小的纵向偏移绝不能翻转从左到右的方向。
    const slightVertical = geometry({ position: { x: 360, y: 80 } })
    expect(selectEdgeHandles(source, slightVertical)).toEqual(RIGHT)
  })

  it('the threshold flip is deterministic, never random', () => {
    const source = geometry()
    // 超过阈值:纵向,目标在下 → bottom/top。
    const below = geometry({ position: { x: 360, y: 500 } })
    expect(selectEdgeHandles(source, below)).toEqual(BELOW)
    // 恰好边界保持横向(abs(dx) >= abs(dy) * 0.8)。
    expect(selectEdgeHandles(source, geometry({ position: { x: 240, y: 300 } }))).toEqual(RIGHT)
    expect(selectEdgeHandles(source, geometry({ position: { x: 241, y: 300 } }))).toEqual(RIGHT)
    // 超过边界一个单位就翻转为纵向。
    expect(selectEdgeHandles(source, geometry({ position: { x: 239, y: 300 } }))).toEqual(BELOW)
  })

  it('vertical classification uses centers too (same threshold mirrored)', () => {
    const source = geometry()
    // dy = -300,dx 较小:|dx| = 100 < 300 * 0.8 → 纵向,目标在上。
    const above = geometry({ position: { x: 100, y: -300 } })
    expect(selectEdgeHandles(source, above)).toEqual(ABOVE)
    // 更强的横向牵引即使目标更高也保持从左到右。
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
