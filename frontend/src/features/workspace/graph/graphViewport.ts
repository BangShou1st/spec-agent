// 文件名:graphViewport.ts
// 用途:画布视口的确定性计算辅助:纯坐标数学计算 fit 视图、节点居中与安全空闲区域(避开浮动面板),产出 setViewport 所需的 transform,绝不移动节点坐标。
/*
 * 确定性的视口辅助函数(Phase 7.3 收尾)。
 *
 * Vue Flow 的 `fitView` 依赖节点实测状态,而刷新落地时它可能尚未就绪,
 * 导致部分 fit 路径在 E2E 中不稳定。这些辅助函数纯粹从当前投影出的节点
 * 坐标加上已知/安全的兜底尺寸计算视口 transform,于是所有 fit 类操作
 * (初始适配、工具栏适配、定位节点、定位路线、新活跃节点露出、自动布局
 * 后适配)都用 `setViewport` 走完全相同的确定性数学。它们只产出
 * transform;绝不移动节点坐标,也绝不修改任何状态。
 */

import type { GraphPosition } from './graphTypes'
import { GRAPH_NODE_HEIGHT, GRAPH_NODE_WIDTH } from './graphEdgeRouting'


export const FALLBACK_NODE_WIDTH = GRAPH_NODE_WIDTH
export const FALLBACK_NODE_HEIGHT = GRAPH_NODE_HEIGHT

/** 视口辅助函数眼中的节点:投影位置 + 已知尺寸。 */
export interface ViewportNode {
  id: string
  position: GraphPosition
  width?: number
  height?: number
}

export interface FitOptions {
  /** 适配内容与画布每条边之间保留的边距(px)。 */
  padding?: number
  /** 结果缩放级别的硬上限。 */
  maxZoom?: number
  /** 提供时,图被适配进该安全区域而不是整个画布。 */
  region?: FitViewportRegion
}

export interface ViewportTransform {
  x: number
  y: number
  zoom: number
}

export interface FitViewportRegion {
  x: number
  y: number
  width: number
  height: number
}

export interface SafeFitRegionInput {
  canvasWidth: number
  canvasHeight: number
  obstacles: Array<{ x: number; y: number; width: number; height: number }>
  gap?: number
  margin?: number
}

function rectsOverlap(a: FitViewportRegion, b: { x: number; y: number; width: number; height: number }): boolean {
  return !(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y)
}

/*
 * 在画布内寻找避开所有给定障碍物(按 `gap` 外扩)的确定性最大空矩形。
 * 纯几何计算:不认识 Inspector/路线/DOM。WorkspaceView 从浮动窗口状态
 * 提供障碍物;graphViewport 只做数学。
 *
 * 确定性:面积最大者获胜;平局时取更小的 x,再取更小的 y。
 */
export function resolveSafeFitRegion(input: SafeFitRegionInput): FitViewportRegion {
  const { canvasWidth, canvasHeight, obstacles } = input
  const gap = input.gap ?? 16
  if (!canvasWidth || !canvasHeight) {
    return { x: 0, y: 0, width: Math.max(1, canvasWidth), height: Math.max(1, canvasHeight) }
  }
  if (!obstacles || obstacles.length === 0) {
    return { x: 0, y: 0, width: canvasWidth, height: canvasHeight }
  }
  const expanded = obstacles
    .map((r) => {
      const x1 = Math.max(0, r.x - gap)
      const y1 = Math.max(0, r.y - gap)
      const x2 = Math.min(canvasWidth, r.x + r.width + gap)
      const y2 = Math.min(canvasHeight, r.y + r.height + gap)
      return { x: x1, y: y1, width: Math.max(0, x2 - x1), height: Math.max(0, y2 - y1) }
    })
    .filter((r) => r.width > 0 && r.height > 0)

  if (expanded.length === 0) {
    return { x: 0, y: 0, width: canvasWidth, height: canvasHeight }
  }

  const xs = new Set<number>([0, canvasWidth])
  const ys = new Set<number>([0, canvasHeight])
  for (const o of expanded) {
    xs.add(o.x)
    xs.add(o.x + o.width)
    ys.add(o.y)
    ys.add(o.y + o.height)
  }
  const xArr = [...xs].filter((v) => v >= 0 && v <= canvasWidth).sort((a, b) => a - b)
  const yArr = [...ys].filter((v) => v >= 0 && v <= canvasHeight).sort((a, b) => a - b)

  let best: FitViewportRegion | null = null
  let bestArea = -1
  for (let xi = 0; xi < xArr.length; xi++) {
    for (let xj = xi + 1; xj < xArr.length; xj++) {
      const x = xArr[xi]
      const width = xArr[xj] - x
      if (width <= 0) continue
      for (let yi = 0; yi < yArr.length; yi++) {
        for (let yj = yi + 1; yj < yArr.length; yj++) {
          const y = yArr[yi]
          const height = yArr[yj] - y
          if (height <= 0) continue
          const candidate: FitViewportRegion = { x, y, width, height }
          let empty = true
          for (const o of expanded) {
            if (rectsOverlap(candidate, o)) {
              empty = false
              break
            }
          }
          if (!empty) continue
          const area = width * height
          if (
            area > bestArea ||
            (area === bestArea && best !== null && (x < best.x || (x === best.x && y < best.y)))
          ) {
            bestArea = area
            best = candidate
          } else if (area === bestArea && best === null) {
            bestArea = area
            best = candidate
          }
        }
      }
    }
  }
  if (!best) {
    return { x: 0, y: 0, width: canvasWidth, height: canvasHeight }
  }
  return best
}


/** 解析节点尺寸:实测尺寸优先,否则使用安全兜底值。 */
export function getNodeSize(node: ViewportNode): { width: number; height: number } {
  const width = node.width !== undefined && node.width > 0 ? node.width : FALLBACK_NODE_WIDTH
  const height = node.height !== undefined && node.height > 0 ? node.height : FALLBACK_NODE_HEIGHT
  return { width, height }
}

/** 由坐标 + 解析出的尺寸计算节点集合的包围盒。 */
export function computeBounds(
  nodes: ViewportNode[],
): { minX: number; minY: number; maxX: number; maxY: number } | null {
  let minX = Number.POSITIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const node of nodes) {
    const { width, height } = getNodeSize(node)
    minX = Math.min(minX, node.position.x)
    minY = Math.min(minY, node.position.y)
    maxX = Math.max(maxX, node.position.x + width)
    maxY = Math.max(maxY, node.position.y + height)
  }
  if (!Number.isFinite(minX) || !Number.isFinite(minY)) {
    return null
  }
  return { minX, minY, maxX, maxY }
}

/*
 * 把给定节点适配进画布的视口 transform:每侧保留 `padding` px 边距,
 * 缩放绝不超过 `maxZoom`。
 */
export function computeFitViewport(
  nodes: ViewportNode[],
  canvasWidth: number,
  canvasHeight: number,
  options: FitOptions = {},
): ViewportTransform | null {
  if (nodes.length === 0 || !canvasWidth || !canvasHeight) {
    return null
  }
  const bounds = computeBounds(nodes)
  if (!bounds) {
    return null
  }
  const padding = options.padding ?? 48
  const maxZoom = options.maxZoom ?? 1
  const boundsWidth = Math.max(bounds.maxX - bounds.minX, 1)
  const boundsHeight = Math.max(bounds.maxY - bounds.minY, 1)
  const region = options.region
  if (region && region.width > 0 && region.height > 0) {
    const availableWidth = Math.max(region.width - padding * 2, 1)
    const availableHeight = Math.max(region.height - padding * 2, 1)
    const zoom = Math.min(availableWidth / boundsWidth, availableHeight / boundsHeight, maxZoom)
    const centerX = (bounds.minX + bounds.maxX) / 2
    const centerY = (bounds.minY + bounds.maxY) / 2
    return {
      x: region.x + region.width / 2 - centerX * zoom,
      y: region.y + region.height / 2 - centerY * zoom,
      zoom,
    }
  }
  const availableWidth = Math.max(canvasWidth - padding * 2, 1)
  const availableHeight = Math.max(canvasHeight - padding * 2, 1)
  const zoom = Math.min(availableWidth / boundsWidth, availableHeight / boundsHeight, maxZoom)
  const centerX = (bounds.minX + bounds.maxX) / 2
  const centerY = (bounds.minY + bounds.maxY) / 2
  return {
    x: canvasWidth / 2 - centerX * zoom,
    y: canvasHeight / 2 - centerY * zoom,
    zoom,
  }
}

/** 把单个节点居中在画布中的视口 transform。 */
export function computeFitNodeViewport(
  node: ViewportNode | null,
  canvasWidth: number,
  canvasHeight: number,
  options: FitOptions = {},
): ViewportTransform | null {
  if (!node) {
    return null
  }
  return computeFitViewport([node], canvasWidth, canvasHeight, options)
}
