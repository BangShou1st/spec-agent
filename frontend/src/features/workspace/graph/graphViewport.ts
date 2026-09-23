import type { GraphPosition } from './graphTypes'
import { GRAPH_NODE_HEIGHT, GRAPH_NODE_WIDTH } from './graphEdgeRouting'

/**
 * Deterministic viewport helpers (Phase 7.3 wrap-up).
 *
 * Vue Flow's `fitView` depends on node-measurement state that may not be
 * ready when a refresh lands, which made some fit paths unreliable in E2E.
 * These helpers compute a viewport transform purely from the current
 * projected node coordinates plus known/safe fallback dimensions, so all
 * fit-style operations (initial fit, toolbar fit, locate node, locate
 * route, new-active-node reveal, post-auto-layout fit) use `setViewport`
 * with exactly the same deterministic math. They only produce transforms;
 * they never move node coordinates and never mutate any state.
 */

export const FALLBACK_NODE_WIDTH = GRAPH_NODE_WIDTH
export const FALLBACK_NODE_HEIGHT = GRAPH_NODE_HEIGHT

/** A node as seen by the viewport helpers: projected position + known size. */
export interface ViewportNode {
  id: string
  position: GraphPosition
  width?: number
  height?: number
}

export interface FitOptions {
  /** Margin in px kept between the fitted content and every canvas edge. */
  padding?: number
  /** Hard cap on the resulting zoom level. */
  maxZoom?: number
  /** When supplied, the graph is fitted inside this safe region instead of the full canvas. */
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

/**
 * Deterministic largest empty rectangle inside the canvas that avoids all
 * given obstacles (expanded by `gap`). Pure geometry: knows nothing about
 * Inspector/Routes/DOM. WorkspaceView supplies the obstacles from floating
 * window state; graphViewport only does the math.
 *
 * Determinism: maximal area wins; ties broken by smallest x, then y.
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


/** Resolves the size of a node: measured size wins, safe fallbacks otherwise. */
export function getNodeSize(node: ViewportNode): { width: number; height: number } {
  const width = node.width !== undefined && node.width > 0 ? node.width : FALLBACK_NODE_WIDTH
  const height = node.height !== undefined && node.height > 0 ? node.height : FALLBACK_NODE_HEIGHT
  return { width, height }
}

/** Bounding box of the given nodes from coordinates + resolved sizes. */
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

/**
 * Viewport transform that fits the given nodes into the canvas, keeping
 * `padding` px on every side and never zooming beyond `maxZoom`.
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

/** Viewport transform that centers a single node in the canvas. */
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
