// 文件名:graphLayout.ts
// 用途:画布的确定性从左到右布局(不依赖外部图引擎):为没有保存坐标的节点计算初始位置、为新节点在父节点右侧寻找空闲槽位,以及 canonical 刷新时的位置解析策略。
/*
 * 确定性的从左到右图布局,不依赖外部图引擎。
 *
 * 已有坐标绝不重算:只有没有保存位置的节点才会得到计算出的位置。新节点
 * 放在父节点右侧、接近父节点的纵向位置;完整自动布局只在用户显式点击
 * "重新自动布局"时执行。
 */

import type { GraphPosition } from './graphTypes'
import { GRAPH_NODE_HEIGHT, GRAPH_NODE_WIDTH } from './graphEdgeRouting'


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

/*
 * 初始布局可选的实测几何信息。
 *
 * 卡片尺寸随内容自适应(一条长笔记可以是短问题高度的 4-5 倍),而旧布局
 * 用固定 `VERTICAL_GAP` 的节距堆叠同一列——这正是过去重新自动布局后,
 * 高卡片会盖住下方节点的原因。提供实测高度时节距增长为
 * `measuredHeight + VERTICAL_GAP`,盒子从此不可能重叠。没有高度
 * (节点尚未完成测量,例如首次布局)时,行为与旧的固定节距逐字节一致。
 */
export interface InitialLayoutOptions {
  /** 节点 id 对应的实测卡片高度(px),若已知。 */
  heightOf?: (nodeId: string) => number | undefined
  /** 深度列之间的水平间距,默认 HORIZONTAL_GAP。 */
  horizontalGap?: number
  /** 两个堆叠卡片之间保留的最小纵向间距,默认 VERTICAL_GAP。 */
  verticalGap?: number
}

/*
 * 为没有保存坐标的节点计算位置。深度沿 parentNodeId 链计算;兄弟节点按
 * canonical 节点顺序分配稳定的纵向槽位。已保存的坐标永远优先。
 */
export function computeInitialLayout(
  nodes: LayoutNode[],
  savedPositions: Record<string, GraphPosition>,
  options: InitialLayoutOptions = {},
): Record<string, GraphPosition> {
  const result: Record<string, GraphPosition> = {}
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const depthMemo = new Map<string, number>()
  // 每个深度列的下一个空闲 y。某列的下一个槽位从上一张卡片实际高度
  // 之下开始,因此同一列的卡片永不重叠。
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

/** 一个已放置的节点。尺寸可选:未实测的节点使用声明的图外框尺寸。 */
export interface OccupiedNode {
  x: number
  y: number
  width?: number
  height?: number
}

export interface PlaceNodeOptions extends InitialLayoutOptions {
  /** 被放置节点的尺寸;缺省时回退到声明的图外框尺寸。 */
  width?: number
  height?: number
}

function resolveSize(value: number | undefined, fallback: number): number {
  return value !== undefined && Number.isFinite(value) && value > 0 ? value : fallback
}

/** 判断两个轴对齐的卡片盒子是否相交。 */
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

/*
 * 把新发现的节点放在父节点右侧、接近父节点纵向位置处,沿
 * [0, 1, -1, 2, -2, 3, -3] 的偏移顺序移动,直到找到空闲盒子。纯函数:
 * 绝不修改入参。
 *
 * 一个槽位空闲,当且仅当候选"卡片盒子"不与任何已占用的卡片盒子相交——
 * 旧规则比较中心点距离与 `VERTICAL_GAP * 0.5`,一张高卡片(长笔记)可以
 * 满足该距离却仍然在视觉上与邻居重叠。尺寸缺省时回退到声明的图外框,
 * 因此不带实测尺寸的调用对等尺寸节点保持原有行为。
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

/*
 * 为 canonical 刷新解析节点位置。分为两个阶段:
 *
 * 1. 首次布局:当集合内没有任何节点有保存位置时,整张图走确定性从左到右
 *    布局(computeInitialLayout)。调用方会把计算出的位置持久化到浏览器
 *    本地,让后续刷新把这些节点识别为"已存在"。
 *
 * 2. 增量刷新:所有已保存坐标逐字节保留,只有真正新增的节点 id 会通过
 *    placeNewNode 放到父节点旁边(父节点右侧,最近的空闲纵向槽位)。
 *    手动移动父节点因此绝不会拖动子节点,新子节点也总会落在移动后的
 *    父节点右侧。
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
  // 父节点自身也可能缺失(canonical 顺序不一定是拓扑序):反复扫描直到
  // 所有节点都放置完毕。若某轮毫无进展(父链断裂),回退到根位置放置。
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
