import type { GraphPosition } from './graphTypes'

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

export const FALLBACK_NODE_WIDTH = 320
export const FALLBACK_NODE_HEIGHT = 220

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
}

export interface ViewportTransform {
  x: number
  y: number
  zoom: number
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
