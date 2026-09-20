import type { GraphPosition } from './graphTypes'
import { GRAPH_NODE_HEIGHT, GRAPH_NODE_WIDTH } from './graphEdgeRouting'

/**
 * Deterministic left-to-right graph layout with no external graph engine.
 *
 * Existing coordinates are never recomputed: only nodes without saved
 * positions receive a computed position. New nodes are placed to the right
 * of their parent near the parent vertical position; the full auto-layout
 * is only run when the user explicitly requests 重新自动布局.
 */

export const HORIZONTAL_GAP = 360
export const VERTICAL_GAP = 260

const VERTICAL_OFFSETS = [0, 1, -1, 2, -2, 3, -3] as const

interface LayoutNode {
  id: string
  parentNodeId: string | null
}

function computeDepth(
  nodeId: string,
  nodes: LayoutNode[],
  byId: Map<string, LayoutNode>,
  memo: Map<string, number>,
  visited: Set<string>,
): number {
  if (memo.has(nodeId)) return memo.get(nodeId) ?? 0
  if (visited.has(nodeId)) return 0
  visited.add(nodeId)
  const node = byId.get(nodeId)
  const parent = node?.parentNodeId ? byId.get(node.parentNodeId) : undefined
  const depth = parent ? computeDepth(parent.id, nodes, byId, memo, visited) + 1 : 0
  memo.set(nodeId, depth)
  return depth
}

/**
 * Optional measured geometry for the initial layout.
 *
 * A card sizes to its content (a long note can be 4-5x taller than a short
 * question), but the legacy layout stacked a column with a fixed
 * `VERTICAL_GAP` pitch — which is exactly why a tall note used to cover the
 * node below it after 重新自动布局. When measured heights are supplied the
 * pitch grows to `measuredHeight + VERTICAL_GAP`, so boxes can never overlap.
 * With no heights (nodes not measured yet, e.g. the very first layout) the
 * legacy fixed-pitch behaviour is preserved byte-for-byte.
 */
export interface InitialLayoutOptions {
  /** Measured card height (px) for a node id, when known. */
  heightOf?: (nodeId: string) => number | undefined
  /** Horizontal pitch between depth columns. Defaults to HORIZONTAL_GAP. */
  horizontalGap?: number
  /** Minimum vertical gap kept between two stacked cards. Defaults to VERTICAL_GAP. */
  verticalGap?: number
}

/**
 * Computes positions for nodes without saved coordinates. Depth follows
 * parentNodeId chains; siblings get stable vertical slots from the canonical
 * node order. Saved coordinates always win.
 */
export function computeInitialLayout(
  nodes: LayoutNode[],
  savedPositions: Record<string, GraphPosition>,
  options: InitialLayoutOptions = {},
): Record<string, GraphPosition> {
  const result: Record<string, GraphPosition> = {}
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const depthMemo = new Map<string, number>()
  // Next free y per depth column. A column's next slot starts below the
  // previous card's real height, so cards in one column never overlap.
  const nextYByDepth = new Map<number, number>()
  const horizontalGap = options.horizontalGap ?? HORIZONTAL_GAP
  const verticalGap = options.verticalGap ?? VERTICAL_GAP
  for (const node of nodes) {
    const saved = savedPositions[node.id]
    if (saved) {
      result[node.id] = { x: saved.x, y: saved.y }
      continue
    }
    const depth = computeDepth(node.id, nodes, byId, depthMemo, new Set())
    const y = nextYByDepth.get(depth) ?? 0
    result[node.id] = { x: depth * horizontalGap, y }
    const measured = options.heightOf?.(node.id)
    const advance = measured !== undefined && Number.isFinite(measured) && measured > 0
      ? measured + verticalGap
      : verticalGap
    nextYByDepth.set(depth, y + advance)
  }
  return result
}

/** An already-placed node. Size is optional: unmeasured nodes use the declared footprint. */
export interface OccupiedNode {
  x: number
  y: number
  width?: number
  height?: number
}

export interface PlaceNodeOptions extends InitialLayoutOptions {
  /** Size of the node being placed. Falls back to the declared graph footprint. */
  width?: number
  height?: number
}

function resolveSize(value: number | undefined, fallback: number): number {
  return value !== undefined && Number.isFinite(value) && value > 0 ? value : fallback
}

/** True when two axis-aligned card boxes intersect. */
function boxesOverlap(
  candidate: GraphPosition,
  candidateWidth: number,
  candidateHeight: number,
  occupied: OccupiedNode,
): boolean {
  const occupiedWidth = resolveSize(occupied.width, GRAPH_NODE_WIDTH)
  const occupiedHeight = resolveSize(occupied.height, GRAPH_NODE_HEIGHT)
  const dx = Math.abs(occupied.x - candidate.x)
  const dy = Math.abs(occupied.y - candidate.y)
  return dx < (candidateWidth + occupiedWidth) / 2 && dy < (candidateHeight + occupiedHeight) / 2
}

/**
 * Places a newly discovered node to the right of its parent near the parent
 * vertical position, walking [0, 1, -1, 2, -2, 3, -3] offsets until a FREE
 * BOX is found. Pure function: never mutates its inputs.
 *
 * A slot is free only when the candidate CARD BOX does not intersect an
 * occupied card box — the legacy rule compared centre distance against
 * `VERTICAL_GAP * 0.5`, which a tall (long-note) card can satisfy while still
 * overlapping its neighbour visually. Sizes fall back to the declared graph
 * footprint, so calls without measurements keep the previous behaviour for
 * uniformly sized nodes.
 */
export function placeNewNode(
  parent: GraphPosition | null,
  occupied: OccupiedNode[],
  options: PlaceNodeOptions = {},
): GraphPosition {
  const horizontalGap = options.horizontalGap ?? HORIZONTAL_GAP
  const verticalGap = options.verticalGap ?? VERTICAL_GAP
  const base: GraphPosition = parent
    ? { x: parent.x + horizontalGap, y: parent.y }
    : { x: 0, y: 0 }
  const width = resolveSize(options.width, GRAPH_NODE_WIDTH)
  const height = resolveSize(options.height, GRAPH_NODE_HEIGHT)
  for (const offset of VERTICAL_OFFSETS) {
    const candidate: GraphPosition = { x: base.x, y: base.y + offset * verticalGap }
    if (!occupied.some((p) => boxesOverlap(candidate, width, height, p))) {
      return candidate
    }
  }
  return base
}

/**
 * Resolves positions for a canonical refresh.
 *
 * Two distinct phases:
 *
 * 1. First-ever layout: when no node in the set has a saved position yet,
 *    the whole graph gets the deterministic left-to-right layout
 *    (`computeInitialLayout`). The caller persists those computed positions
 *    browser-locally so later refreshes recognize the nodes as existing.
 *
 * 2. Incremental refresh: every existing saved coordinate is preserved
 *    byte-for-byte and only genuinely new node ids are placed next to their
 *    parents via `placeNewNode` (parent-right, nearest free vertical slot).
 *    A manual move of a parent therefore never drags its children along,
 *    and a new child always lands to the right of the moved parent.
 */
export function resolvePositions(
  nodes: LayoutNode[],
  savedPositions: Record<string, GraphPosition>,
  options: InitialLayoutOptions = {},
): Record<string, GraphPosition> {
  const anySaved = nodes.some((n) => savedPositions[n.id])
  if (!anySaved) {
    return computeInitialLayout(nodes, savedPositions, options)
  }
  const result: Record<string, GraphPosition> = {}
  const missing: LayoutNode[] = []
  for (const node of nodes) {
    const saved = savedPositions[node.id]
    if (saved) {
      result[node.id] = { x: saved.x, y: saved.y }
    } else {
      missing.push(node)
    }
  }
  // Parents may themselves be missing (canonical order is not necessarily
  // topological): repeat passes until every node is placed. If a pass makes
  // no progress (broken parent chain), fall back to root placement.
  let pending = missing
  while (pending.length > 0) {
    const deferred: LayoutNode[] = []
    let progress = false
    for (const node of pending) {
      const parentPos = node.parentNodeId ? result[node.parentNodeId] : undefined
      if (node.parentNodeId && parentPos === undefined) {
        deferred.push(node)
        continue
      }
      const occupied: OccupiedNode[] = Object.entries(result).map(([id, position]) => ({
        ...position,
        height: options.heightOf?.(id),
      }))
      result[node.id] = placeNewNode(parentPos ?? null, occupied, {
        ...options,
        height: options.heightOf?.(node.id),
      })
      progress = true
    }
    if (!progress) {
      for (const node of pending) {
        const occupied: OccupiedNode[] = Object.entries(result).map(([id, position]) => ({
          ...position,
          height: options.heightOf?.(id),
        }))
        result[node.id] = placeNewNode(null, occupied, {
          ...options,
          height: options.heightOf?.(node.id),
        })
      }
      break
    }
    pending = deferred
  }
  return result
}
