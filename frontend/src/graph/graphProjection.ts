import type { Edge, Node } from '@vue-flow/core'
import type {
  GraphWorkspaceNodeView,
  GraphWorkspaceOptionView,
  GraphWorkspaceRouteView,
  GraphWorkspaceView,
  RouteLifecycleStatus,
} from '@/api/types'
import type { GraphPosition, GraphRouteDisplayState } from './graphTypes'
import { resolvePositions, VERTICAL_GAP } from './graphLayout'

export type GraphVisualWeight = 'active' | 'focus' | 'normal' | 'dimmed'

export interface GraphAnswerPresentation {
  routeId: string
  selectedOptionId: string | null
  selectedOptionLabel: string | null
  freeText: string | null
  isPrimary: boolean
}

/** Per-route state of one node: answered (with its answer) or waiting. */
export interface GraphRouteAnswerState {
  routeId: string
  answer: GraphAnswerPresentation | null
}

export interface SpecAgentGraphNodeData {
  node: GraphWorkspaceNodeView
  routeIds: string[]
  answers: GraphAnswerPresentation[]
  /**
   * Route-specific state for EVERY member route (in membership order):
   * a route without an answer gets `answer: null` and must be presented as
   * waiting — the node can never show another route's answer as its own.
   */
  routeStates: GraphRouteAnswerState[]
  primaryAnswer: GraphAnswerPresentation | null
  /** Browser reading context = focusRouteId ?? activeRouteId (browser-only). */
  readingRouteId: string | null
  isCurrent: boolean
  canAnswer: boolean
  isExpanded: boolean
  isShared: boolean
  visualWeight: GraphVisualWeight
}

export interface SpecAgentGraphEdgeData {
  kind: 'lineage' | 'replacement'
  routeIds: string[]
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
  uiState: {
    lifecycleFilters: Record<RouteLifecycleStatus, boolean>
    routeDisplayStates: Record<string, GraphRouteDisplayState>
  },
): boolean {
  if (route.id === activeRouteId) {
    // The Active route can never be hidden by manual or persisted state.
    return true
  }
  if (uiState.lifecycleFilters[route.lifecycleStatus] !== true) {
    return false
  }
  return uiState.routeDisplayStates[route.id] !== 'hidden'
}

/** Route ids visible under lifecycle filters + manual hide. */
export function getVisibleRouteIds(
  view: Pick<GraphWorkspaceView, 'routes' | 'activeRouteId'>,
  uiState: {
    lifecycleFilters: Record<RouteLifecycleStatus, boolean>
    routeDisplayStates: Record<string, GraphRouteDisplayState>
  },
): Set<string> {
  const visible = new Set<string>()
  for (const route of view.routes) {
    if (routeVisible(route, view.activeRouteId, uiState)) {
      visible.add(route.id)
    }
  }
  return visible
}

/** Node id -> route ids that contain it (canonical route membership). */
export function getNodeRouteMembership(
  view: GraphWorkspaceView,
): Map<string, string[]> {
  const membership = new Map<string, string[]>()
  for (const route of view.routes) {
    for (const nodeId of route.lineageNodeIds) {
      const ids = membership.get(nodeId) ?? []
      if (!ids.includes(route.id)) {
        membership.set(nodeId, [...ids, route.id])
      }
    }
  }
  return membership
}

/**
 * Deduplicated lineage edge membership: edge key `source->target` -> the
 * routes that traverse it. Never uses supersedesNodeId as a parent.
 */
export function getLineageEdgeMembership(
  view: GraphWorkspaceView,
): Map<string, LineageEdgeMembership> {
  const membership = new Map<string, LineageEdgeMembership>()
  for (const route of view.routes) {
    const lineage = route.lineageNodeIds
    for (let i = 1; i < lineage.length; i++) {
      const source = lineage[i - 1]
      const target = lineage[i]
      const key = source + '->' + target
      const existing = membership.get(key)
      if (existing) {
        if (!existing.routeIds.includes(route.id)) {
          existing.routeIds = [...existing.routeIds, route.id]
        }
      } else {
        membership.set(key, { source, target, routeIds: [route.id] })
      }
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
  if (focusRouteId) {
    // Focus is a browser-only reading context: while reading route F, F's
    // elements stay prominent and every other visible route — including the
    // Active route — is temporarily dimmed. Exiting Focus restores the
    // previous manual dim/hide state because Focus never touches it.
    return routeIds.includes(focusRouteId) ? 'focus' : 'dimmed'
  }
  if (activeRouteId && routeIds.includes(activeRouteId)) return 'active'
  if (routeIds.some((id) => routeDisplayStates[id] === 'dimmed')) return 'dimmed'
  return 'normal'
}

/**
 * Selects the primary answer presentation for a node: Focus route answer
 * outranks the Active route answer; without either, no primary answer.
 */
export function selectPrimaryAnswer(
  nodeId: string,
  answers: GraphAnswerPresentation[],
  focusRouteId: string | null,
  activeRouteId: string | null,
  _options: GraphWorkspaceOptionView[],
): GraphAnswerPresentation | null {
  // Callers may pass a per-node list (no nodeId fields) or the full graph
  // list (presentations extended with nodeId); filter only when present.
  const nodeAnswers = answers.filter((a) => {
    const withNodeId = a as GraphAnswerPresentation & { nodeId?: string }
    return withNodeId.nodeId === undefined || withNodeId.nodeId === nodeId
  })
  if (focusRouteId) {
    const focus = nodeAnswers.find((a) => a.routeId === focusRouteId)
    if (focus) return focus
    // The focused route has no answer on this node: never present another
    // route's answer as the focused route's primary (B must be shown as
    // waiting instead).
    return null
  }
  if (activeRouteId) {
    const active = nodeAnswers.find((a) => a.routeId === activeRouteId)
    if (active) return active
  }
  return null
}

function buildAnswerPresentations(
  view: GraphWorkspaceView,
): Map<string, GraphAnswerPresentation[]> {
  const optionsByNode = new Map<string, GraphWorkspaceOptionView[]>()
  for (const node of view.nodes) {
    optionsByNode.set(node.id, node.options)
  }
  const byNode = new Map<string, GraphAnswerPresentation[]>()
  for (const answer of view.answers) {
    const option = optionsByNode
      .get(answer.nodeId)
      ?.find((o) => o.id === answer.selectedOptionId)
    const presentation: GraphAnswerPresentation = {
      routeId: answer.routeId,
      selectedOptionId: answer.selectedOptionId,
      selectedOptionLabel: option?.label ?? null,
      freeText: answer.freeText,
      isPrimary: false,
    }
    const list = byNode.get(answer.nodeId) ?? []
    byNode.set(answer.nodeId, [...list, presentation])
  }
  return byNode
}

function buildRouteStates(
  view: GraphWorkspaceView,
  answersByNode: Map<string, GraphAnswerPresentation[]>,
  membership: Map<string, string[]>,
): Map<string, GraphRouteAnswerState[]> {
  const byNode = new Map<string, GraphRouteAnswerState[]>()
  for (const node of view.nodes) {
    const routeIds = membership.get(node.id) ?? []
    const answers = answersByNode.get(node.id) ?? []
    byNode.set(
      node.id,
      routeIds.map((routeId) => ({
        routeId,
        answer: answers.find((a) => a.routeId === routeId) ?? null,
      })),
    )
  }
  return byNode
}

function computePositions(
  view: GraphWorkspaceView,
  savedPositions: Record<string, GraphPosition>,
  visibleNodeIds: Set<string>,
): Record<string, GraphPosition> {
  const nodes = view.nodes.filter((n) => visibleNodeIds.has(n.id))
  // First-ever layout fills every missing position deterministically; any
  // later refresh only places genuinely new ids next to their parents and
  // preserves all existing coordinates byte-for-byte.
  return resolvePositions(nodes, savedPositions)
}

/**
 * Projects the canonical graph + browser UI state into Vue Flow nodes/edges.
 *
 * Nodes are deduplicated, route-specific answers stay separate, lineage and
 * replacement relationships are distinct edges, and only unanswered Active
 * nodes are answerable. This is a pure projection: it never mutates Runtime
 * data and never writes anything.
 */
export function projectGraph(input: GraphProjectionInput): GraphProjectionResult {
  const { view, activeNodeId, uiState, savedPositions } = input
  const activeRouteId = view.activeRouteId

  const visibleRouteIds = getVisibleRouteIds(view, uiState)
  const membership = getNodeRouteMembership(view)
  const lineageEdges = getLineageEdgeMembership(view)

  // Node visibility: a shared node stays visible when any of its routes is.
  const visibleNodeIds = new Set<string>()
  for (const node of view.nodes) {
    const routeIds = membership.get(node.id) ?? []
    if (routeIds.some((id) => visibleRouteIds.has(id))) {
      visibleNodeIds.add(node.id)
    }
  }

  const positions = computePositions(view, savedPositions, visibleNodeIds)
  const answersByNode = buildAnswerPresentations(view)
  const optionsByNode = new Map(view.nodes.map((n) => [n.id, n.options]))
  const statesByNode = buildRouteStates(view, answersByNode, membership)
  const readingRouteId = uiState.focusRouteId ?? activeRouteId

  const nodes: Node<SpecAgentGraphNodeData>[] = view.nodes
    .filter((node) => visibleNodeIds.has(node.id))
    .map((node) => {
      const routeIds = membership.get(node.id) ?? []
      const answers = answersByNode.get(node.id) ?? []
      const primary = selectPrimaryAnswer(
        node.id,
        answers,
        uiState.focusRouteId,
        activeRouteId,
        optionsByNode.get(node.id) ?? [],
      )
      const canAnswer =
        activeNodeId != null &&
        node.id === activeNodeId &&
        activeRouteId != null &&
        !view.answers.some(
          (answer) => answer.routeId === activeRouteId && answer.nodeId === node.id,
        )
      const visualWeight = routeVisualWeight(
        routeIds,
        activeRouteId,
        uiState.focusRouteId,
        uiState.routeDisplayStates,
      )
      const data: SpecAgentGraphNodeData = {
        node,
        routeIds,
        answers: answers.map((a) => ({ ...a, isPrimary: a === primary })),
        routeStates: statesByNode.get(node.id) ?? [],
        primaryAnswer: primary,
        readingRouteId,
        isCurrent: node.id === activeNodeId,
        canAnswer,
        isExpanded: uiState.expandedNodeIds.includes(node.id),
        isShared: routeIds.length > 1,
        visualWeight,
      }
      return {
        id: node.id,
        type: 'question' as const,
        position: positions[node.id] ?? { x: 0, y: 0 },
        data,
        dragHandle: '.graph-question-node__header',
        class: ['graph-node', `graph-node--${visualWeight}`],
      }
    })

  const edges: Edge<SpecAgentGraphEdgeData>[] = []
  for (const [key, edge] of lineageEdges) {
    if (visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target)) {
      edges.push({
        id: key,
        source: edge.source,
        target: edge.target,
        data: {
          kind: 'lineage',
          routeIds: [...edge.routeIds],
          visualWeight: routeVisualWeight(
            edge.routeIds,
            activeRouteId,
            uiState.focusRouteId,
            uiState.routeDisplayStates,
          ),
        },
        class: ['graph-edge--lineage', `graph-edge--${routeVisualWeight(
          edge.routeIds,
          activeRouteId,
          uiState.focusRouteId,
          uiState.routeDisplayStates,
        )}`],
      })
    }
  }

  for (const node of view.nodes) {
    if (
      node.supersedesNodeId &&
      visibleNodeIds.has(node.id) &&
      visibleNodeIds.has(node.supersedesNodeId)
    ) {
      const routeIds = membership.get(node.id) ?? []
      edges.push({
        id: 'replacement:' + node.supersedesNodeId + '->' + node.id,
        source: node.supersedesNodeId,
        target: node.id,
        data: {
          kind: 'replacement',
          routeIds: [...routeIds],
          visualWeight: routeVisualWeight(
            routeIds,
            activeRouteId,
            uiState.focusRouteId,
            uiState.routeDisplayStates,
          ),
        },
        class: ['graph-edge--replacement', `graph-edge--${routeVisualWeight(
          routeIds,
          activeRouteId,
          uiState.focusRouteId,
          uiState.routeDisplayStates,
        )}`],
      })
    }
  }

  return { nodes, edges }
}

/** Free-vertical-slot helper reused by callers for new-node placement. */
export function freeSlotDistance(): number {
  return VERTICAL_GAP * 0.5
}