import { MarkerType, type Edge, type Node } from '@vue-flow/core'
import type {
  GraphWorkspaceNodeView,
  GraphWorkspaceOptionView,
  GraphWorkspaceRouteView,
  GraphWorkspaceView,
  RouteLifecycleStatus,
} from '@/api/types'
import type { GraphPosition, GraphRouteDisplayState } from './graphTypes'
import { resolvePositions, VERTICAL_GAP } from './graphLayout'
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
  routeIds: string[]
  visibleRouteIds: string[]
  answers: GraphAnswerPresentation[]
  routeStates: GraphRouteAnswerState[]
  primaryAnswer: GraphAnswerPresentation | null
  readingRouteId: string | null
  isCurrent: boolean
  canAnswer: boolean
  isExpanded: boolean
  isShared: boolean
  routeMembership?: GraphRouteMembershipPresentation[]
  visualWeight: GraphVisualWeight
}

export interface SpecAgentGraphEdgeData {
  kind: 'lineage' | 'replacement'
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
  }
  savedPositions: Record<string, GraphPosition>
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
  if (focusRouteId) return nodeAnswers.find((answer) => answer.routeId === focusRouteId) ?? null
  return null
}

function fallbackRouteLabel(route: Pick<GraphWorkspaceRouteView, 'branchType' | 'isActive'>): string {
  if (route.branchType === 'fork') return '分支路线'
  if (route.branchType === 'reanswer') return '重新回答路线'
  if (route.branchType === 'regenerate') return '换题路线'
  return route.isActive ? '主路线' : '路线'
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
  const visibleInstances = instances.filter((instance) => instance.routeIds.some((id) => visibleRouteIds.has(id)))
  const visibleKeys = new Set(visibleInstances.map((instance) => instance.visualNodeKey))
  const positions = computePositions(visibleInstances, savedPositions)
  const answersByCanonicalNode = buildAnswerPresentations(view)

  const nodes: Node<SpecAgentGraphNodeData>[] = visibleInstances.map((instance) => {
    const routeIds = instance.routeIds
    const answers = (answersByCanonicalNode.get(instance.canonicalNodeId) ?? [])
      .filter((answer) => routeIds.includes(answer.routeId))
    const readingRouteId = uiState.focusRouteId && routeIds.includes(uiState.focusRouteId)
      ? uiState.focusRouteId
      : routeIds.length === 1 ? routeIds[0] : null
    const primary = selectPrimaryAnswer(
      instance.canonicalNodeId,
      answers,
      readingRouteId,
      null,
      instance.node.options,
    )
    const isCurrent = activeNodeId === instance.canonicalNodeId && activeRouteId !== null && routeIds.includes(activeRouteId)
    const canAnswer = isCurrent && !answers.some((answer) => answer.routeId === activeRouteId)
    const visualWeight = routeVisualWeight(routeIds, activeRouteId, uiState.focusRouteId, uiState.routeDisplayStates)
    const routeStates = routeIds.map((routeId) => ({
      routeId,
      routeLabel: routeLabel(view.routes.find((route) => route.id === routeId)),
      answer: answers.find((answer) => answer.routeId === routeId) ?? null,
    }))
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
      type: 'question' as const,
      position: positions[instance.visualNodeKey] ?? { x: 0, y: 0 },
      data: {
        node: instance.node,
        canonicalNodeId: instance.canonicalNodeId,
        visualNodeKey: instance.visualNodeKey,
        routeIds,
        visibleRouteIds: routeIds.filter((id) => visibleRouteIds.has(id)),
        answers: answers.map((answer) => ({ ...answer, isPrimary: answer === primary })),
        routeStates,
        primaryAnswer: primary,
        readingRouteId,
        isCurrent,
        canAnswer,
        isExpanded: uiState.expandedNodeIds.includes(instance.visualNodeKey)
          || uiState.expandedNodeIds.includes(instance.canonicalNodeId),
        isShared: routeIds.length > 1,
        routeMembership,
        visualWeight,
      },
      dragHandle: '.graph-question-node__header',
      class: ['graph-node', 'graph-node--' + visualWeight],
    }
  })

  const edges: Edge<SpecAgentGraphEdgeData>[] = []
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

  return { nodes, edges }
}

export function freeSlotDistance(): number {
  return VERTICAL_GAP * 0.5
}
