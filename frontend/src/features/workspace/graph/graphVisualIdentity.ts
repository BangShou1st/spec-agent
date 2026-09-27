// 文件名:graphVisualIdentity.ts
// 用途:画布的"视觉节点身份"计算:根据路线分叉类型把 canonical 节点映射为视觉实例 key(共享历史共用卡片、分叉点之后各开卡片),并构建整张图的视觉实例列表。
import type {
  GraphWorkspaceNodeView,
  GraphWorkspaceRouteView,
  GraphWorkspaceView,
} from '@/shared/contracts/types'

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

/*
 * 共享历史沿分支点之前继承来源路线的 key;显式的重新回答/替换分支从第一个
 * 分歧节点起改用带路线限定的 key。fork 与自由续问"穿过分支点共享"
 * (分支点本身是同一个 canonical 视觉实例);重新回答与换题在旧目标之前
 * 就分叉(旧问题留在来源路线上,新问题/替换节点开启路线专属卡片)。
 * 运行时血缘(provenance)是这套身份的唯一输入。
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
  const sharesThroughBranchPoint = route.branchType === 'fork' || route.branchType === 'continuation'
  const sharesPrefix = sharesThroughBranchPoint
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

  // 出处子节点(通过 kind 感知连接挂到谱系节点下的知识/资源)是路线成员,
  // 但不在父链上:后端把它们算作路线成员(见 RouteHistoryResolver
  // .belongsToRoute),投影必须同样处理——否则已连接的想法仍显示"独立节点"
  // 徽标、不画到父节点的 lineage 边、丢失路线操作。归属(以及视觉父边)
  // 从父实例传递继承。
  const lineagedNodeIds = new Set(
    view.routes.flatMap((route) => route.lineageNodeIds ?? []),
  )
  const orphans: typeof view.nodes = []
  for (const node of view.nodes) {
    if (lineagedNodeIds.has(node.id) || byKey.has(node.id)) continue
    const parentNodeId = node.parentNodeId
    const parentInstance = parentNodeId ? byKey.get(parentNodeId) : undefined
    if (!parentInstance) {
      orphans.push(node)
      continue
    }
    byKey.set(node.id, {
      visualNodeKey: node.id,
      canonicalNodeId: node.id,
      node,
      routeIds: [...parentInstance.routeIds],
      parentVisualNodeKey: parentInstance.visualNodeKey,
    })
  }
  // 传递挂接更深的出处链(子节点的子节点),如果有的话。
  let pending = orphans
  while (pending.length > 0) {
    const remaining: typeof view.nodes = []
    for (const node of pending) {
      const parentInstance = node.parentNodeId ? byKey.get(node.parentNodeId) : undefined
      if (!parentInstance) {
        remaining.push(node)
        continue
      }
      byKey.set(node.id, {
        visualNodeKey: node.id,
        canonicalNodeId: node.id,
        node,
        routeIds: [...parentInstance.routeIds],
        parentVisualNodeKey: parentInstance.visualNodeKey,
      })
    }
    if (remaining.length === pending.length) break
    pending = remaining
  }
  // 真正的浮动草稿(用户想法)不属于任何路线谱系:在手动接入前它们是独立
  // 的图内容。以 canonical id 为 key 投影成无路线实例,保证在画布上持续
  // 可见、可编辑。
  for (const node of pending) {
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
