import type { GraphPosition } from './graphTypes'

/**
 * Deterministic left-to-right graph layout with no external graph engine.
 *
 * Existing coordinates are never recomputed: only nodes without saved
 * positions receive a computed position. New nodes are placed to the right
 * of their parent near the parent vertical position; the full auto-layout
 * is only run when the user explicitly requests 重新自动布局.
 */

export const HORIZONTAL_GAP = 360
export const VERTICAL_GAP = 220

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
 * Computes positions for nodes without saved coordinates. Depth follows
 * parentNodeId chains; siblings get stable vertical slots from the canonical
 * node order. Saved coordinates always win.
 */
export function computeInitialLayout(
  nodes: LayoutNode[],
  savedPositions: Record<string, GraphPosition>,
): Record<string, GraphPosition> {
  const result: Record<string, GraphPosition> = {}
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const depthMemo = new Map<string, number>()
  const slotByDepth = new Map<number, number>()
  for (const node of nodes) {
    const saved = savedPositions[node.id]
    if (saved) {
      result[node.id] = { x: saved.x, y: saved.y }
      continue
    }
    const depth = computeDepth(node.id, nodes, byId, depthMemo, new Set())
    const slot = slotByDepth.get(depth) ?? 0
    slotByDepth.set(depth, slot + 1)
    result[node.id] = { x: depth * HORIZONTAL_GAP, y: slot * VERTICAL_GAP }
  }
  return result
}

function distance(a: GraphPosition, b: GraphPosition): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

/**
 * Places a newly discovered node to the right of its parent near the parent
 * vertical position, walking [0, 1, -1, 2, -2, 3, -3] offsets until a free
 * slot is found. Pure function: never mutates its inputs.
 */
export function placeNewNode(
  parent: GraphPosition | null,
  occupied: GraphPosition[],
): GraphPosition {
  const base: GraphPosition = parent
    ? { x: parent.x + HORIZONTAL_GAP, y: parent.y }
    : { x: 0, y: 0 }
  for (const offset of VERTICAL_OFFSETS) {
    const candidate: GraphPosition = { x: base.x, y: base.y + offset * VERTICAL_GAP }
    if (!occupied.some((p) => distance(p, candidate) < VERTICAL_GAP * 0.5)) {
      return candidate
    }
  }
  return base
}
