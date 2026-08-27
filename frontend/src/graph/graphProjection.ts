import { MarkerType, type Edge, type Node } from '@vue-flow/core'
import type {
  GraphWorkspaceNodeView,
  GraphWorkspaceOptionView,
  GraphWorkspaceRouteView,
  GraphWorkspaceView,
  RouteLifecycleStatus,
} from '@/api/types'
import type { GraphPosition, GraphRouteDisplayState } from './graphTypes'
import { placeNewNode, resolvePositions, HORIZONTAL_GAP, VERTICAL_GAP } from './graphLayout'
import {
  selectEdgeHandles,
  FALLBACK_NODE_WIDTH,
  type EdgeHandles,
  type NodeGeometry,
} from './graphEdgeRouting'
import {
  buildVisualInstances,
  type GraphVisualInstance,
} from './graphVisualIdentity'

export type GraphVisualWeight = 'active' | 'focus' | 'normal' | 'dimmed'

/** Runtime progress is projected separately from knowledge status. */
export type GraphRuntimeStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** A browser-only card projected from an in-flight AgentRun. */
export interface GraphPendingProjection {
  routeId: string
  sourceNodeId: string | null
  runId: string
  status: GraphRuntimeStatus
  phase: string | null
  message: string | null
}

/**
 * Contextual AI actions identify the canonical node and the visual instance
 * that the user actually acted on. The former anchors the query; the latter
 * preserves the clicked branch when one canonical node has multiple visuals.
 */
export interface ContextualAiTarget {
  canonicalNodeId: string
  visualNodeKey: string
}

export interface GraphAnswerPresentation {
  routeId: string
  routeLabel?: string
  selectedOptionId: string | null
  selectedOptionLabel: string | null
  freeText: string | null
  isPrimary: boolean
  inherited?: boolean
  ownerRouteId?: string
}

export interface GraphRouteAnswerState {
  routeId: string
  routeLabel?: string
  answer: GraphAnswerPresentation | null
}

export type AnswerPresentationMode =
  | 'focused'
  | 'single-route'
  | 'shared-common'
  | 'shared-divergent'

export interface CommonAnswerSummary {
  selectedOptionId: string | null
  selectedOptionLabel: string | null
  freeText: string | null
}

export interface GraphRouteMembershipPresentation {
  routeId: string
  label: string
  lifecycleStatus: RouteLifecycleStatus
  isActive: boolean
  branchType?: GraphWorkspaceRouteView['branchType']
  sourceRouteId?: string | null
  branchAtNodeId?: string | null
}

export interface SpecAgentGraphNodeData {
  node: GraphWorkspaceNodeView
  canonicalNodeId?: string
  visualNodeKey?: string
  projectId: string
  routeIds: string[]
  visibleRouteIds: string[]
  answers: GraphAnswerPresentation[]
  routeStates: GraphRouteAnswerState[]
  primaryAnswer: GraphAnswerPresentation | null
  answerPresentationMode: AnswerPresentationMode
  commonAnswer: CommonAnswerSummary | null
  readingRouteId: string | null
  isCurrent: boolean
  canAnswer: boolean
  isExpanded: boolean
  isShared: boolean
  isLatest: boolean
  qLabel: string | null
  routeMembership?: GraphRouteMembershipPresentation[]
  visualWeight: GraphVisualWeight
  /** Runtime facts are intentionally optional and never replace knowledgeStatus. */
  runtimeStatus?: GraphRuntimeStatus | null
  runtimePhase?: string | null
  runtimeMessage?: string | null
}

export interface SpecAgentGraphEdgeData {
  kind: 'lineage' | 'replacement' | 'relation'
  relationType?: string
  routeIds: string[]
  visibleRouteIds: string[]
  visualWeight: GraphVisualWeight
}

export interface GraphProjectionInput {
  view: GraphWorkspaceView
  activeNodeId: string | null
  uiState: {
    focusRouteId: string | null
    lifecycleFilters: Record<RouteLifecycleStatus, boolean>
    routeDisplayStates: Record<string, GraphRouteDisplayState>
    expandedNodeIds: string[]
    /** Default false. Inspector remains the canonical relations viewer. */
    showRelationLayer?: boolean
  }
  savedPositions: Record<string, GraphPosition>
  runtime?: {
    nodeId: string | null
    status: GraphRuntimeStatus | null
    phase: string | null
  }
  pending?: GraphPendingProjection | null
}

export interface GraphProjectionResult {
  nodes: Node<SpecAgentGraphNodeData>[]
  edges: Edge<SpecAgentGraphEdgeData>[]
}

export interface LineageEdgeMembership {
  source: string
  target: string
  routeIds: string[]
}

function routeVisible(
  route: Pick<GraphWorkspaceRouteView, 'id' | 'lifecycleStatus'>,
  activeRouteId: string | null,
  uiState: Pick<GraphProjectionInput['uiState'], 'lifecycleFilters' | 'routeDisplayStates'>,
): boolean {
  if (route.id === activeRouteId) return true
  if (uiState.lifecycleFilters[route.lifecycleStatus] !== true) return false
  return uiState.routeDisplayStates[route.id] !== 'hidden'
}

export function getVisibleRouteIds(
  view: Pick<GraphWorkspaceView, 'routes' | 'activeRouteId'>,
  uiState: Pick<GraphProjectionInput['uiState'], 'lifecycleFilters' | 'routeDisplayStates'>,
): Set<string> {
  const visible = new Set<string>()
  for (const route of view.routes) {
    if (routeVisible(route, view.activeRouteId, uiState)) visible.add(route.id)
  }
  return visible
}

/** Canonical membership remains available for non-visual consumers. */
export function getNodeRouteMembership(view: GraphWorkspaceView): Map<string, string[]> {
  const membership = new Map<string, string[]>()
  for (const route of view.routes) {
    for (const nodeId of route.lineageNodeIds) {
      const ids = membership.get(nodeId) ?? []
      if (!ids.includes(route.id)) membership.set(nodeId, [...ids, route.id])
    }
  }
  return membership
}

/** Legacy canonical edge helper; visual projection uses the V2 helper. */
export function getLineageEdgeMembership(view: GraphWorkspaceView): Map<string, LineageEdgeMembership> {
  const membership = new Map<string, LineageEdgeMembership>()
  for (const route of view.routes) {
    for (let index = 1; index < route.lineageNodeIds.length; index += 1) {
      const source = route.lineageNodeIds[index - 1]
      const target = route.lineageNodeIds[index]
      const key = source + '->' + target
      const existing = membership.get(key)
      if (existing) {
        if (!existing.routeIds.includes(route.id)) existing.routeIds = [...existing.routeIds, route.id]
      } else {
        membership.set(key, { source, target, routeIds: [route.id] })
      }
    }
  }
  return membership
}

/** Physical lineage edges are deduplicated by visual endpoints. */
export function getVisualLineageEdgeMembership(view: GraphWorkspaceView): Map<string, LineageEdgeMembership> {
  const membership = new Map<string, LineageEdgeMembership>()
  for (const instance of buildVisualInstances(view)) {
    if (!instance.parentVisualNodeKey) continue
    const key = instance.parentVisualNodeKey + '->' + instance.visualNodeKey
    const existing = membership.get(key)
    if (existing) {
      for (const routeId of instance.routeIds) {
        if (!existing.routeIds.includes(routeId)) existing.routeIds = [...existing.routeIds, routeId]
      }
    } else {
      membership.set(key, {
        source: instance.parentVisualNodeKey,
        target: instance.visualNodeKey,
        routeIds: [...instance.routeIds],
      })
    }
  }
  return membership
}

function routeVisualWeight(
  routeIds: string[],
  activeRouteId: string | null,
  focusRouteId: string | null,
  routeDisplayStates: Record<string, GraphRouteDisplayState>,
): GraphVisualWeight {
  if (focusRouteId) return routeIds.includes(focusRouteId) ? 'focus' : 'dimmed'
  if (activeRouteId && routeIds.includes(activeRouteId)) return 'active'
  if (routeIds.some((id) => routeDisplayStates[id] === 'dimmed')) return 'dimmed'
  return 'normal'
}

export function selectPrimaryAnswer(
  nodeId: string,
  answers: GraphAnswerPresentation[],
  focusRouteId: string | null,
  _activeRouteId: string | null,
  _options: GraphWorkspaceOptionView[],
): GraphAnswerPresentation | null {
  const nodeAnswers = answers.filter((answer) => {
    const withNodeId = answer as GraphAnswerPresentation & { nodeId?: string }
    return withNodeId.nodeId === undefined || withNodeId.nodeId === nodeId
  })
  // 1. 显式阅读路线优先：返回该 route 的 answer（缺失时 null，卡片显示 waiting）。
  if (focusRouteId) {
    return nodeAnswers.find((answer) => answer.routeId === focusRouteId) ?? null
  }
  // 2. 无 focus：只允许在"非 shared 单一 route"下直接返回该 route answer；
  //    shared + 无 focus 一律 null primary —— UI 通过 answerPresentationMode
  //    ('shared-common' | 'shared-divergent') 渲染 commonAnswer 或 routeStates。
  const distinctRoutes = new Set(nodeAnswers.map((a) => a.routeId))
  if (distinctRoutes.size === 1) {
    return nodeAnswers[0] ?? null
  }
  return null
}

function fallbackRouteLabel(route: Pick<GraphWorkspaceRouteView, 'branchType' | 'isActive'>): string {
  if (route.branchType === 'fork') return '分支路线'
  if (route.branchType === 'reanswer') return '重新回答路线'
  if (route.branchType === 'regenerate') return '换题路线'
  if (route.branchType === 'continuation') return '探索分支'
  return route.isActive ? '主路线' : '路线'
}

/**
 * Registry-style node type resolution: the stable node kind maps to a
 * registered card component (see GraphCanvas nodeTypes). New subtypes reuse
 * an existing kind's card; they never add per-business card classes.
 */
export function nodeTypeForKind(kind: GraphWorkspaceNodeView['kind']): 'question' | 'knowledge' {
  return kind === 'INTERACTION' ? 'question' : 'knowledge'
}

function routeLabel(route: GraphWorkspaceRouteView | undefined): string {
  return route?.label?.trim() || (route ? fallbackRouteLabel(route) : '当前路线')
}

function buildAnswerPresentations(view: GraphWorkspaceView): Map<string, GraphAnswerPresentation[]> {
  const optionsByNode = new Map<string, GraphWorkspaceOptionView[]>()
  for (const node of view.nodes) optionsByNode.set(node.id, node.options)
  const byNode = new Map<string, GraphAnswerPresentation[]>()
  for (const answer of view.answers) {
    const option = optionsByNode.get(answer.nodeId)?.find((candidate) => candidate.id === answer.selectedOptionId)
    const presentation: GraphAnswerPresentation = {
      routeId: answer.routeId,
      routeLabel: routeLabel(view.routes.find((route) => route.id === answer.routeId)),
      selectedOptionId: answer.selectedOptionId,
      selectedOptionLabel: option?.label ?? null,
      freeText: answer.freeText,
      isPrimary: false,
      inherited: answer.inherited,
      ownerRouteId: answer.ownerRouteId,
    }
    byNode.set(answer.nodeId, [...(byNode.get(answer.nodeId) ?? []), presentation])
  }
  return byNode
}

/** True when two effective answers represent the same user semantic content. */
function answersEquivalent(a: GraphAnswerPresentation, b: GraphAnswerPresentation): boolean {
  return a.selectedOptionId === b.selectedOptionId
    && (a.freeText ?? null) === (b.freeText ?? null)
}

/** Compute the AnswerPresentationMode and (when applicable) a CommonAnswerSummary. */
function computeAnswerPresentation(
  routeIds: string[],
  routeStates: GraphRouteAnswerState[],
  focusRouteId: string | null,
): { mode: AnswerPresentationMode; common: CommonAnswerSummary | null } {
  if (focusRouteId) {
    return { mode: 'focused', common: null }
  }
  if (routeIds.length <= 1) {
    return { mode: 'single-route', common: null }
  }
  // Shared + no focus. Compare only answers that actually have content
  // (selectedOptionId OR freeText); routes with null answer count as waiting
  // and disqualify the "common" case.
  const answeredStates = routeStates.filter((s) => s.answer !== null)
  if (answeredStates.length !== routeStates.length) {
    // 部分 waiting → divergent
    return { mode: 'shared-divergent', common: null }
  }
  const answered = answeredStates.map((s) => s.answer as GraphAnswerPresentation)
  if (answered.length < 2) {
    return { mode: 'shared-divergent', common: null }
  }
  const first = answered[0]
  const allSame = answered.every((a) => answersEquivalent(first, a))
  if (!allSame) {
    return { mode: 'shared-divergent', common: null }
  }
  return {
    mode: 'shared-common',
    common: {
      selectedOptionId: first.selectedOptionId,
      selectedOptionLabel: first.selectedOptionLabel ?? null,
      freeText: first.freeText ?? null,
    },
  }
}

function computePositions(
  instances: GraphVisualInstance[],
  savedPositions: Record<string, GraphPosition>,
): Record<string, GraphPosition> {
  return resolvePositions(
    instances.map((instance) => ({ id: instance.visualNodeKey, parentNodeId: instance.parentVisualNodeKey })),
    savedPositions,
  )
}

function selectHandlesFor(sourceId: string, targetId: string, positions: Record<string, GraphPosition>): EdgeHandles {
  const geometry = (id: string): NodeGeometry => ({
    position: positions[id] ?? { x: 0, y: 0 },
    width: FALLBACK_NODE_WIDTH,
  })
  return selectEdgeHandles(geometry(sourceId), geometry(targetId))
}

export function projectGraph(input: GraphProjectionInput): GraphProjectionResult {
  const { view, activeNodeId, uiState, savedPositions } = input
  const visibleRouteIds = getVisibleRouteIds(view, uiState)
  const instances = buildVisualInstances(view)
  const activeRouteId = view.activeRouteId
  const visibleInstances = instances.filter((instance) =>
    instance.routeIds.length === 0 || instance.routeIds.some((id) => visibleRouteIds.has(id)))
  const visibleKeys = new Set(visibleInstances.map((instance) => instance.visualNodeKey))
  const positions = computePositions(visibleInstances, savedPositions)

  // Floating drafts (route-less ideas) start at a free slot to the right of
  // the current layout instead of the root column, which the on-canvas
  // toolbar overlays — a new idea must be immediately visible and reachable.
  for (const instance of visibleInstances) {
    if (instance.routeIds.length !== 0) continue
    if (savedPositions[instance.visualNodeKey]) continue
    const maxX = Object.values(positions).reduce((acc, p) => Math.max(acc, p.x), 0)
    positions[instance.visualNodeKey] = placeNewNode(
      { x: maxX + HORIZONTAL_GAP, y: 0 },
      Object.values(positions),
    )
  }
  const answersByCanonicalNode = buildAnswerPresentations(view)

  // Compute Q labels: topological order across all visible nodes.
  const nodeOrder = new Map<string, number>()
  let qCounter = 1
  for (const route of view.routes) {
    if (!visibleRouteIds.has(route.id)) continue
    for (const nodeId of route.lineageNodeIds ?? []) {
      const inst = visibleInstances.find(
        (i) => i.canonicalNodeId === nodeId && i.routeIds.includes(route.id),
      )
      if (inst && !nodeOrder.has(inst.visualNodeKey)) {
        nodeOrder.set(inst.visualNodeKey, qCounter++)
      }
    }
  }

  // Compute latest marker: active route tip with no answer.
  const activeRoute = view.routes.find((r) => r.id === activeRouteId)
  const activeTipNodeId = activeRoute?.tipNodeId ?? null
  const activeTipHasAnswer = activeTipNodeId != null
    && view.answers.some((a) => a.nodeId === activeTipNodeId && a.routeId === activeRouteId)

  const nodes: Node<SpecAgentGraphNodeData>[] = visibleInstances.map((instance) => {
    const routeIds = instance.routeIds
    const answers = (answersByCanonicalNode.get(instance.canonicalNodeId) ?? [])
      .filter((answer) => routeIds.includes(answer.routeId))
    const readingRouteId = uiState.focusRouteId && routeIds.includes(uiState.focusRouteId)
      ? uiState.focusRouteId
      : routeIds.length === 1 ? routeIds[0] : null
    const rawPrimary = selectPrimaryAnswer(
      instance.canonicalNodeId,
      answers,
      readingRouteId,
      activeRouteId,
      instance.node.options,
    )
    // shared + no focus 时 primary 强制为 null，UI 用 answerPresentationMode
    // + commonAnswer / routeStates 渲染；single-route 时直接走 rawPrimary。
    const primary = (routeIds.length > 1 && !readingRouteId) ? null : rawPrimary
    const isCurrent = activeNodeId === instance.canonicalNodeId && activeRouteId !== null && routeIds.includes(activeRouteId)
    const canAnswer = isCurrent && !answers.some((answer) => answer.routeId === activeRouteId)
    // 浮动想法不属于任何路线：聚焦/弱化语义都不适用，保持常规视觉权重，
    // 保证新建后立即可读可编辑。
    const visualWeight = routeIds.length === 0
      ? 'normal'
      : routeVisualWeight(routeIds, activeRouteId, uiState.focusRouteId, uiState.routeDisplayStates)
    const routeStates = routeIds.map((routeId) => ({
      routeId,
      routeLabel: routeLabel(view.routes.find((route) => route.id === routeId)),
      answer: answers.find((answer) => answer.routeId === routeId) ?? null,
    }))
    const answerPresentation = computeAnswerPresentation(
      routeIds, routeStates, uiState.focusRouteId,
    )
    const routeMembership = routeIds
      .filter((routeId) => visibleRouteIds.has(routeId))
      .map((routeId) => {
        const route = view.routes.find((candidate) => candidate.id === routeId)
        return {
          routeId,
          label: routeLabel(route),
          lifecycleStatus: route?.lifecycleStatus ?? 'open',
          isActive: route?.isActive === true || routeId === activeRouteId,
          branchType: route?.branchType,
          sourceRouteId: route?.sourceRouteId,
          branchAtNodeId: route?.branchAtNodeId,
        }
      })
    return {
      id: instance.visualNodeKey,
      type: nodeTypeForKind(instance.node.kind),
      position: positions[instance.visualNodeKey] ?? { x: 0, y: 0 },
      data: {
        node: instance.node,
        canonicalNodeId: instance.canonicalNodeId,
        visualNodeKey: instance.visualNodeKey,
        projectId: view.projectId,
        routeIds,
        visibleRouteIds: routeIds.filter((id) => visibleRouteIds.has(id)),
        answers: answers.map((answer) => ({ ...answer, isPrimary: answer === primary })),
        routeStates,
        primaryAnswer: primary,
        answerPresentationMode: answerPresentation.mode,
        commonAnswer: answerPresentation.common,
        readingRouteId,
        isCurrent,
        canAnswer,
        isExpanded: uiState.expandedNodeIds.includes(instance.visualNodeKey)
          || uiState.expandedNodeIds.includes(instance.canonicalNodeId),
        isShared: routeIds.length > 1,
        isLatest: instance.canonicalNodeId === activeTipNodeId
          && !activeTipHasAnswer
          && routeIds.includes(activeRouteId ?? ''),
        qLabel: nodeOrder.has(instance.visualNodeKey)
          ? 'Q' + nodeOrder.get(instance.visualNodeKey) : null,
        routeMembership,
        visualWeight,
        runtimeStatus: input.runtime?.nodeId === instance.canonicalNodeId
          ? input.runtime.status
          : null,
        runtimePhase: input.runtime?.nodeId === instance.canonicalNodeId
          ? input.runtime.phase
          : null,
        runtimeMessage: null,
      },
      dragHandle: '.graph-question-node__header',
      class: [
        'graph-node',
        'graph-node--' + visualWeight,
        ...(routeIds.length > 1 && readingRouteId === null ? ['graph-node--neutral'] : []),
      ],
    }
  })
  const edges: Edge<SpecAgentGraphEdgeData>[] = []

  // A pending card is a presentation projection of an AgentRun. It is never
  // added to the canonical GraphWorkspaceView and is replaced by the real
  // persisted node after the run completes.
  const pending = input.pending
  const pendingRoute = pending
    ? view.routes.find((route) => route.id === pending.routeId)
    : undefined
  if (pending && pendingRoute && visibleRouteIds.has(pending.routeId)) {
    const pendingId = `pending:${pending.runId}`
    const parentInstance = visibleInstances.find((instance) =>
      instance.canonicalNodeId === pending.sourceNodeId
      && instance.routeIds.includes(pending.routeId),
    )
    const parentKey = parentInstance?.visualNodeKey ?? null
    const pendingPosition = savedPositions[pendingId]
      ?? placeNewNode(parentKey ? positions[parentKey] ?? null : null, Object.values(positions))
    const pendingLabel = routeLabel(pendingRoute)
    const pendingNode: GraphWorkspaceNodeView = {
      id: pendingId,
      projectId: view.projectId,
      parentNodeId: pending.sourceNodeId,
      supersedesNodeId: null,
      question: pending.status === 'FAILED' ? '下一步问题生成失败' : '正在生成下一步问题…',
      purpose: null,
      options: [],
      allowFreeAnswer: false,
      createdAt: '1970-01-01T00:00:00.000Z',
      kind: 'INTERACTION',
      subtype: 'QUESTION',
      content: {},
      authorKind: 'RUNTIME',
      knowledgeStatus: null,
      userEditableDraft: false,
    }
    nodes.push({
      id: pendingId,
      type: 'question',
      position: pendingPosition,
      data: {
        node: pendingNode,
        canonicalNodeId: pendingId,
        visualNodeKey: pendingId,
        projectId: view.projectId,
        routeIds: [pending.routeId],
        visibleRouteIds: [pending.routeId],
        answers: [],
        routeStates: [{ routeId: pending.routeId, routeLabel: pendingLabel, answer: null }],
        primaryAnswer: null,
        answerPresentationMode: 'single-route',
        commonAnswer: null,
        readingRouteId: pending.routeId,
        isCurrent: false,
        canAnswer: false,
        isExpanded: true,
        isShared: false,
        isLatest: true,
        qLabel: null,
        routeMembership: [{
          routeId: pending.routeId,
          label: pendingLabel,
          lifecycleStatus: pendingRoute.lifecycleStatus,
          isActive: pendingRoute.isActive,
          branchType: pendingRoute.branchType,
          sourceRouteId: pendingRoute.sourceRouteId,
          branchAtNodeId: pendingRoute.branchAtNodeId,
        }],
        visualWeight: routeVisualWeight(
          [pending.routeId], activeRouteId, uiState.focusRouteId, uiState.routeDisplayStates,
        ),
        runtimeStatus: pending.status,
        runtimePhase: pending.phase,
        runtimeMessage: pending.message,
      },
      dragHandle: '.graph-question-node__header',
      class: [
        'graph-node',
        'graph-node--' + routeVisualWeight(
          [pending.routeId], activeRouteId, uiState.focusRouteId, uiState.routeDisplayStates,
        ),
      ],
    })
    if (parentKey && visibleKeys.has(parentKey)) {
      const handles = selectHandlesFor(parentKey, pendingId, {
        ...positions,
        [pendingId]: pendingPosition,
      })
      const weight = routeVisualWeight(
        [pending.routeId], activeRouteId, uiState.focusRouteId, uiState.routeDisplayStates,
      )
      edges.push({
        id: `${parentKey}->${pendingId}`,
        source: parentKey,
        target: pendingId,
        type: 'adaptive',
        sourceHandle: handles.sourceHandle,
        targetHandle: handles.targetHandle,
        markerEnd: { type: MarkerType.ArrowClosed, width: 12, height: 12 },
        data: {
          kind: 'lineage',
          routeIds: [pending.routeId],
          visibleRouteIds: [pending.routeId],
          visualWeight: weight,
        },
        class: ['graph-edge--lineage', 'graph-edge--' + weight],
      })
    }
  }

  for (const [key, edge] of getVisualLineageEdgeMembership(view)) {
    if (!visibleKeys.has(edge.source) || !visibleKeys.has(edge.target)) continue
    const handles = selectHandlesFor(edge.source, edge.target, positions)
    const weight = routeVisualWeight(edge.routeIds, activeRouteId, uiState.focusRouteId, uiState.routeDisplayStates)
    edges.push({
      id: key,
      source: edge.source,
      target: edge.target,
      type: 'adaptive',
      sourceHandle: handles.sourceHandle,
      targetHandle: handles.targetHandle,
      markerEnd: { type: MarkerType.ArrowClosed, width: 12, height: 12 },
      data: {
        kind: 'lineage',
        routeIds: [...edge.routeIds],
        visibleRouteIds: edge.routeIds.filter((id) => visibleRouteIds.has(id)),
        visualWeight: weight,
      },
      class: ['graph-edge--lineage', 'graph-edge--' + weight],
    })
  }

  // 用户手动创建的语义关系：连接两个可见节点，与 lineage 边在样式上区分。
  // 关系不属于任何路线；relationEndpointKey 用 focus（绝不借 Active）来稳定
  // 锚定 shared visual instance；无法稳定锚定时 relation 边不画，事实仍
  // 通过 view.relations 供 Inspector 读取。relation layer 默认关闭：
  // 默认 canvas 不被语义关系铺满，Inspector 永远可读。
  if (uiState.showRelationLayer) {
    for (const relation of view.relations) {
      const source = relationEndpointKey(
        visibleInstances, relation.sourceNodeId, uiState.focusRouteId, visibleKeys)
      const target = relationEndpointKey(
        visibleInstances, relation.targetNodeId, uiState.focusRouteId, visibleKeys)
      if (!source || !target || source === target) continue
      const handles = selectHandlesFor(source, target, positions)
      edges.push({
        id: `relation:${relation.id}`,
        source,
        target,
        type: 'adaptive',
        sourceHandle: handles.sourceHandle,
        targetHandle: handles.targetHandle,
        markerEnd: { type: MarkerType.ArrowClosed, width: 12, height: 12 },
        data: {
          kind: 'relation',
          relationType: relation.relationType,
          routeIds: [],
          visibleRouteIds: [],
          visualWeight: 'normal',
        },
        class: ['graph-edge--relation'],
      })
    }
  }

  return { nodes, edges }
}

/**
 * Resolves the visual instance a relation endpoint attaches to. Three-state
 * rule, NEVER falling back to Active/first/latest on shared ambiguity:
 *
 *  1. If a focus route is set and the canonical node has exactly one visible
 *     instance that includes the focus route → use that instance.
 *  2. Else if the canonical node has exactly one visible instance → use it.
 *  3. Else → return null (relation edge must NOT be drawn presentationally
 *     because we cannot deterministically pick a visual instance; the
 *     canonical fact remains in the read model and the Inspector).
 */
function relationEndpointKey(
  instances: GraphVisualInstance[],
  canonicalNodeId: string,
  focusRouteId: string | null,
  visibleKeys: Set<string>,
): string | null {
  const visible = instances.filter(
    (instance) => instance.canonicalNodeId === canonicalNodeId
      && visibleKeys.has(instance.visualNodeKey),
  )
  if (visible.length === 0) {
    return null
  }
  if (visible.length === 1) {
    return visible[0].visualNodeKey
  }
  if (focusRouteId) {
    const focusInstance = visible.find(
      (instance) => instance.routeIds.includes(focusRouteId),
    )
    if (focusInstance) {
      return focusInstance.visualNodeKey
    }
  }
  return null
}

export function freeSlotDistance(): number {
  return VERTICAL_GAP * 0.5
}
