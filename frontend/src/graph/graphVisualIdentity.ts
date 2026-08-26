import type {
  GraphWorkspaceNodeView,
  GraphWorkspaceRouteView,
  GraphWorkspaceView,
} from '@/api/types'

export interface GraphVisualInstance {
  visualNodeKey: string
  canonicalNodeId: string
  node: GraphWorkspaceNodeView
  routeIds: string[]
  parentVisualNodeKey: string | null
}

function routeById(view: GraphWorkspaceView): Map<string, GraphWorkspaceRouteView> {
  return new Map(view.routes.map((route) => [route.id, route]))
}

/**
 * Shared history inherits the source route's key through the branch point;
 * explicit Re-answer/Replace branches qualify the key from the first
 * divergent node. Runtime provenance is the only input to this identity.
 */
export function visualNodeKeyFor(
  view: GraphWorkspaceView,
  routeId: string,
  canonicalNodeId: string,
  memo = new Map<string, string>(),
): string {
  const memoKey = routeId + '|' + canonicalNodeId
  const cached = memo.get(memoKey)
  if (cached) return cached

  const route = routeById(view).get(routeId)
  if (!route || !route.branchType || !route.sourceRouteId || !route.branchAtNodeId) {
    memo.set(memoKey, canonicalNodeId)
    return canonicalNodeId
  }

  const branchIndex = route.lineageNodeIds.indexOf(route.branchAtNodeId)
  const sourceRoute = routeById(view).get(route.sourceRouteId)
  const sourceBranchIndex = sourceRoute?.lineageNodeIds.indexOf(route.branchAtNodeId) ?? -1
  const nodeIndex = route.lineageNodeIds.indexOf(canonicalNodeId)
  const effectiveBranchIndex = branchIndex >= 0 ? branchIndex : sourceBranchIndex
  const sharesPrefix = route.branchType === 'fork'
    ? nodeIndex >= 0 && effectiveBranchIndex >= 0 && nodeIndex <= effectiveBranchIndex
    : nodeIndex >= 0 && effectiveBranchIndex >= 0 && nodeIndex < effectiveBranchIndex

  if (sharesPrefix) {
    const inherited = visualNodeKeyFor(view, route.sourceRouteId, canonicalNodeId, memo)
    memo.set(memoKey, inherited)
    return inherited
  }

  const qualified = `route:${route.id}:${canonicalNodeId}`
  memo.set(memoKey, qualified)
  return qualified
}

export function buildVisualInstances(view: GraphWorkspaceView): GraphVisualInstance[] {
  const nodesById = new Map(view.nodes.map((node) => [node.id, node]))
  const memo = new Map<string, string>()
  const byKey = new Map<string, GraphVisualInstance>()

  for (const route of view.routes) {
    for (let index = 0; index < route.lineageNodeIds.length; index += 1) {
      const canonicalNodeId = route.lineageNodeIds[index]
      const node = nodesById.get(canonicalNodeId)
      if (!node) continue
      const visualNodeKey = visualNodeKeyFor(view, route.id, canonicalNodeId, memo)
      const parentCanonicalId = index > 0 ? route.lineageNodeIds[index - 1] : null
      const parentVisualNodeKey = parentCanonicalId
        ? visualNodeKeyFor(view, route.id, parentCanonicalId, memo)
        : null
      const existing = byKey.get(visualNodeKey)
      if (existing) {
        if (!existing.routeIds.includes(route.id)) existing.routeIds = [...existing.routeIds, route.id]
      } else {
        byKey.set(visualNodeKey, {
          visualNodeKey,
          canonicalNodeId,
          node,
          routeIds: [route.id],
          parentVisualNodeKey,
        })
      }
    }
  }

  // Floating drafts (user ideas) belong to no route lineage: they are
  // standalone graph content until manually connected. They project as
  // route-less instances keyed by their canonical id so they stay visible
  // and editable on the canvas.
  const lineagedNodeIds = new Set(
    view.routes.flatMap((route) => route.lineageNodeIds ?? []),
  )
  for (const node of view.nodes) {
    if (lineagedNodeIds.has(node.id) || byKey.has(node.id)) continue
    byKey.set(node.id, {
      visualNodeKey: node.id,
      canonicalNodeId: node.id,
      node,
      routeIds: [],
      parentVisualNodeKey: null,
    })
  }
  return [...byKey.values()]
}

export function getVisualNodeRouteMembership(view: GraphWorkspaceView): Map<string, string[]> {
  return new Map(buildVisualInstances(view).map((instance) => [
    instance.visualNodeKey,
    [...instance.routeIds],
  ]))
}
