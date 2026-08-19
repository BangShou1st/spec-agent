import type { GraphPosition } from './graphTypes'

/**
 * Adaptive anchored edge routing (browser-only presentation helper).
 *
 * Pure function: source/target node geometry -> the Vue Flow source and
 * target handle ids used by lineage and replacement edges. It knows nothing
 * about Runtime semantics (routes, answers, supersession) and never mutates
 * anything.
 *
 * The rule keeps the graph's left-to-right reading habit: only a clearly
 * vertical relationship (|dy| > |dx| / 0.8) switches to top/bottom anchors,
 * so a small y deviation while dragging never makes an edge flap between
 * horizontal and vertical routing.
 */

/** Horizontal routing wins while |dx| >= |dy| * HORIZONTAL_DOMINANCE_FACTOR. */
export const HORIZONTAL_DOMINANCE_FACTOR = 0.8

/** Safe fallback dimensions used when a node has no measured size yet. */
export const FALLBACK_NODE_WIDTH = 320
export const FALLBACK_NODE_HEIGHT = 220

/** CSS width of the current (answerable) node, wider than historical nodes. */
export const CURRENT_NODE_WIDTH = 420

export interface NodeGeometry {
  position: GraphPosition
  /** Measured size when known; the caller may omit it (safe fallback then). */
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

/**
 * Selects the natural connection direction between two nodes from their
 * centers:
 *
 * - mostly horizontal: source-right -> target-left (target right of source)
 *   or source-left -> target-right (target left of source)
 * - mostly vertical: source-bottom -> target-top (target below source)
 *   or source-top -> target-bottom (target above source)
 *
 * Deterministic for every input, including the degenerate overlapping case
 * (defaults to the LTR source-right -> target-left pair).
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
