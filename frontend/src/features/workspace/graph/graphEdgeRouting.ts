// 文件名:graphEdgeRouting.ts
// 用途:画布边的自适应锚点路由(纯浏览器展示辅助):根据源/目标节点几何位置选出 Vue Flow 的 source/target handle id,被 lineage 与替代边共用。
import type { GraphPosition } from './graphTypes'

/*
 * 自适应锚点边路由(仅浏览器端的展示辅助)。
 *
 * 纯函数:源/目标节点几何 → lineage 与替代边使用的 Vue Flow source/target
 * handle id。不关心任何运行时语义(路线、回答、替代),也绝不修改任何状态。
 *
 * 规则保持图从左到右的阅读习惯:只有明显垂直的关系(|dy| > |dx| / 0.8)才
 * 切换到上/下锚点,这样拖拽时 y 的小幅偏移不会让边在横竖路由之间来回抖动。
 */

/** 当 |dx| >= |dy| * HORIZONTAL_DOMINANCE_FACTOR 时,横向路由优先。 */
export const HORIZONTAL_DOMINANCE_FACTOR = 0.8

/** 所有问题节点状态共用的稳定外框尺寸。 */
export const GRAPH_NODE_WIDTH = 320
export const GRAPH_NODE_HEIGHT = 220

/** 节点尚无实测尺寸时使用的安全兜底尺寸。 */
export const FALLBACK_NODE_WIDTH = GRAPH_NODE_WIDTH
export const FALLBACK_NODE_HEIGHT = GRAPH_NODE_HEIGHT

export interface NodeGeometry {
  position: GraphPosition
  /** 实测尺寸(若已知);调用方可省略(此时使用安全兜底值)。 */
  width?: number
  height?: number
}

export interface EdgeHandles {
  sourceHandle: string
  targetHandle: string
}

function resolveSize(value: number | undefined, fallback: number): number {
  return value !== undefined && Number.isFinite(value) && value > 0 ? value : fallback
}

/*
 * 根据两节点中心点选择自然的连线方向:
 *
 * - 以横向为主:源右侧 → 目标左侧(目标在源右侧)
 *   或源左侧 → 目标右侧(目标在源左侧)
 * - 以纵向为主:源底部 → 目标顶部(目标在源下方)
 *   或源顶部 → 目标底部(目标在源上方)
 *
 * 对所有输入都是确定性的,包括两节点完全重叠的退化情形
 * (默认取从左到右的 源右侧 → 目标左侧 组合)。
 */
export function selectEdgeHandles(source: NodeGeometry, target: NodeGeometry): EdgeHandles {
  const sourceWidth = resolveSize(source.width, FALLBACK_NODE_WIDTH)
  const sourceHeight = resolveSize(source.height, FALLBACK_NODE_HEIGHT)
  const targetWidth = resolveSize(target.width, FALLBACK_NODE_WIDTH)
  const targetHeight = resolveSize(target.height, FALLBACK_NODE_HEIGHT)

  const sourceCenterX = source.position.x + sourceWidth / 2
  const sourceCenterY = source.position.y + sourceHeight / 2
  const targetCenterX = target.position.x + targetWidth / 2
  const targetCenterY = target.position.y + targetHeight / 2

  const dx = targetCenterX - sourceCenterX
  const dy = targetCenterY - sourceCenterY

  if (Math.abs(dx) >= Math.abs(dy) * HORIZONTAL_DOMINANCE_FACTOR) {
    return dx >= 0
      ? { sourceHandle: 'source-right', targetHandle: 'target-left' }
      : { sourceHandle: 'source-left', targetHandle: 'target-right' }
  }
  return dy >= 0
    ? { sourceHandle: 'source-bottom', targetHandle: 'target-top' }
    : { sourceHandle: 'source-top', targetHandle: 'target-bottom' }
}
