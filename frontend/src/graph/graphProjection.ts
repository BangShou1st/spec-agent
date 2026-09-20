import { MarkerType, type Edge, type Node } from '@vue-flow/core'
import type {
  GraphWorkspaceNodeView,
  GraphWorkspaceOptionView,
  GraphWorkspaceRelationView,
  GraphWorkspaceRouteView,
  GraphWorkspaceView,
  RouteLifecycleStatus,
} from '@/api/types'
import type { RunProgressStep } from '@/api/agentRuns'
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
import { resolveReadingRouteId } from './graphInteraction'
import {
  agentOperationFailureLabel,
  agentOperationProgressLabel,
} from '@/presentation/agentPresentation'

export type GraphVisualWeight = 'active' | 'focus' | 'normal' | 'dimmed'

/** Runtime progress is projected separately from knowledge status. */
export type GraphRuntimeStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** Whitelisted process content shown inside an executing node card. */
export interface GraphRunProgress {
  summary: string | null
  steps: RunProgressStep[]
}

/** A browser-only card projected from an in-flight AgentRun. */
export interface GraphPendingProjection {
  routeId: string
  sourceNodeId: string | null
  runId: string
  status: GraphRuntimeStatus
  phase: string | null
  message: string | null
  operation?: string | null
  progress?: GraphRunProgress | null
}

/** Runtime overlay projected onto an existing canonical node. */
export interface GraphNodeRuntimeState {
  status: GraphRuntimeStatus | null
  phase: string | null
  progress: GraphRunProgress | null
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
  /** 多选题的全量选择（用户顺序）；单选答案为 null。 */
  selectedOptionIds: string[] | null
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

/**
 * A canonical Question Node carries exactly one immutable Answer identity
 * project-wide (SHARED_STATE_DIVERGENCE is an invariant violation, not a
 * presentation mode). Presentation is therefore either focused (reading a
 * specific route) or single-route; the answer content never changes with
 * Focus, only the reading context does.
 */
export type AnswerPresentationMode =
  | 'focused'
  | 'single-route'

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
  readingRouteId: string | null
  /** True when this visual node is the canonical tip of its current reading
   * route — the only case where "activate its owning route and answer" is a
   * legal affordance for a genuinely unanswered Question. */
  isTipOfReadingRoute?: boolean
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
  /** Process content (steps + summaries) for an in-flight run on this node. */
  runtimeProgress?: GraphRunProgress | null
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
    /** Ephemeral "只看这条路线" lens: when set, this route is the ONLY visible
     * one. Explicit per-route intent, so it outranks lifecycle filters, manual
     * dim/hide AND the Active-route force-visible rule. Never persisted. */
    isolatedRouteId?: string | null
    /** Default false. Inspector remains the canonical relations viewer. */
    showRelationLayer?: boolean
    /** Selected node ids (visual keys): their direct 1-hop relations project
     * onto the canvas even when the global relation layer is off. */
    selectedNodeIds?: string[]
  }
  savedPositions: Record<string, GraphPosition>
  /** Per-canonical-node runtime overlays for in-flight runs on existing nodes. */
  runtimeByNode?: Record<string, GraphNodeRuntimeState>
  /** Browser-only cards for runs whose target node does not exist yet. */
  pendings?: GraphPendingProjection[]
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

/**
 * A route is visible when the isolate lens (if any) selects it, or — with no
 * lens — when it is the Active route or passes the lifecycle filter and is not
 * manually hidden.
 *
 * The isolate lens is checked FIRST and wins outright: it is one explicit
 * per-route user command, so it may hide the Active route. Everything weaker
 * (lifecycle filter, manual dim/hide, Active force-visible) only applies
 * without a lens. Before this rule, "只看这条路线" on a non-Active route always
 * kept the running route on the canvas, so a second isolate looked like a no-op.
 */
function routeVisible(
  route: Pick<GraphWorkspaceRouteView, 'id' | 'lifecycleStatus'>,
  activeRouteId: string | null,
  uiState: Pick<GraphProjectionInput['uiState'], 'lifecycleFilters' | 'routeDisplayStates' | 'isolatedRouteId'>,
): boolean {
  if (uiState.isolatedRouteId) return route.id === uiState.isolatedRouteId
  if (route.id === activeRouteId) return true
  if (uiState.lifecycleFilters[route.lifecycleStatus] !== true) return false
  return uiState.routeDisplayStates[route.id] !== 'hidden'
}

export function getVisibleRouteIds(
  view: Pick<GraphWorkspaceView, 'routes' | 'activeRouteId'>,
  uiState: Pick<GraphProjectionInput['uiState'], 'lifecycleFilters' | 'routeDisplayStates' | 'isolatedRouteId'>,
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
  _focusRouteId: string | null,
  _activeRouteId: string | null,
  _options: GraphWorkspaceOptionView[],
): GraphAnswerPresentation | null {
  const nodeAnswers = answers.filter((answer) => {
    const withNodeId = answer as GraphAnswerPresentation & { nodeId?: string }
    return withNodeId.nodeId === undefined || withNodeId.nodeId === nodeId
  })
  // A canonical Question Node carries at most ONE immutable Answer identity
  // project-wide (SHARED_STATE_DIVERGENCE is an invariant violation). The
  // answer CONTENT never depends on Focus/reading route — Focus only changes
  // the reading context. Always return the single canonical Answer, so a
  // Shared answered Question shows the same answer under Main/Branch/null.
  return nodeAnswers[0] ?? null
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
      selectedOptionIds: answer.selectedOptionIds,
      freeText: answer.freeText,
      isPrimary: false,
      inherited: answer.inherited,
      ownerRouteId: answer.ownerRouteId,
    }
    byNode.set(answer.nodeId, [...(byNode.get(answer.nodeId) ?? []), presentation])
  }
  return byNode
}

/** Compute the AnswerPresentationMode. Shared canonical nodes carry one
 * immutable Answer identity, so a "divergent summaries" mode cannot occur in
 * a healthy graph; the mode stays a pure reading-context signal. */
function computeAnswerPresentation(
  _routeIds: string[],
  _routeStates: GraphRouteAnswerState[],
  focusRouteId: string | null,
): { mode: AnswerPresentationMode } {
  return { mode: focusRouteId ? 'focused' : 'single-route' }
}

function computePositions(
  instances: GraphVisualInstance[],
  savedPositions: Record<string, GraphPosition>,
): Record<string, GraphPosition> {
  const heightByKey = new Map<string, number>()
  for (const instance of instances) {
    heightByKey.set(instance.visualNodeKey, estimateNodeCardHeight(instance.node))
  }
  return resolvePositions(
    instances.map((instance) => ({ id: instance.visualNodeKey, parentNodeId: instance.parentVisualNodeKey })),
    savedPositions,
    { heightOf: (id) => heightByKey.get(id) },
  )
}

/**
 * Deterministic card-height estimate, used ONLY while a card has never been
 * measured (Vue Flow reports real heights one frame later; 重新自动布局 then
 * uses the measured values).
 *
 * Why it is needed: a card sizes to its content, so a fixed row pitch piles a
 * long note on top of the next card. The first layout runs before any
 * measurement exists, and its result is persisted immediately — so without an
 * estimate the very first layout of a project with long notes overlaps.
 *
 * Calibration (measured on the real canvas): a 320px knowledge card with
 * 482 chars / 20 newlines renders 802px tall; a 320px interaction card with a
 * 148-char question, a 40-char purpose and a free-text box renders 463px.
 * Constants are deliberately tuned to OVER-estimate slightly: extra space is
 * cosmetic, too little space is a visible overlap.
 */
export function estimateNodeCardHeight(node: GraphWorkspaceNodeView): number {
  const BASE = 76
  const QUESTION_CHARS_PER_LINE = 18
  const QUESTION_LINE_HEIGHT = 25
  const PURPOSE_CHARS_PER_LINE = 24
  const PURPOSE_LINE_HEIGHT = 19
  const CONTENT_CHARS_PER_LINE = 24
  const CONTENT_LINE_HEIGHT = 20
  const OPTION_ROW_HEIGHT = 30
  const SUBMIT_RESERVE = 34
  const FREE_ANSWER_RESERVE = 124
  const MIN_HEIGHT = 110
  const MAX_HEIGHT = 1400

  /** Rendered lines of a text block: explicit breaks plus soft wrapping. */
  const renderedLines = (text: string | null | undefined, charsPerLine: number): number => {
    if (!text) return 0
    const trimmed = text.trim()
    if (!trimmed) return 0
    const explicit = (trimmed.match(/\n/g) ?? []).length
    return explicit + Math.max(1, Math.ceil(trimmed.length / charsPerLine))
  }

  const clamp = (value: number): number => Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, Math.round(value)))

  if (node.kind === 'INTERACTION') {
    const questionLines = renderedLines(node.question, QUESTION_CHARS_PER_LINE)
    const purposeLines = renderedLines(node.purpose, PURPOSE_CHARS_PER_LINE)
    return clamp(
      BASE
      + questionLines * QUESTION_LINE_HEIGHT
      + purposeLines * PURPOSE_LINE_HEIGHT
      + node.options.length * OPTION_ROW_HEIGHT
      + SUBMIT_RESERVE
      + (node.allowFreeAnswer ? FREE_ANSWER_RESERVE : 0),
    )
  }
  const text = typeof node.content?.text === 'string' ? node.content.text : ''
  return clamp(BASE + renderedLines(text, CONTENT_CHARS_PER_LINE) * CONTENT_LINE_HEIGHT)
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

  // Compute Q labels: 节点不再编号（Q1/Q2 的序号随路线增删漂移，没有稳定
  // 含义），路线上的 INTERACTION 节点统一展示"问题"身份标签。
  const labeledQuestionNodes = new Set<string>()
  for (const route of view.routes) {
    if (!visibleRouteIds.has(route.id)) continue
    for (const nodeId of route.lineageNodeIds ?? []) {
      const inst = visibleInstances.find(
        (i) => i.canonicalNodeId === nodeId && i.routeIds.includes(route.id),
      )
      if (inst && inst.node.kind === 'INTERACTION') {
        labeledQuestionNodes.add(inst.visualNodeKey)
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
    const readingRouteId = resolveReadingRouteId({
      membershipRouteIds: routeIds,
      visibleRouteIds,
      focusRouteId: uiState.focusRouteId,
    })
    const rawPrimary = selectPrimaryAnswer(
      instance.canonicalNodeId,
      answers,
      readingRouteId,
      activeRouteId,
      instance.node.options,
    )
    const primary = rawPrimary
    const isCurrent = activeNodeId === instance.canonicalNodeId && activeRouteId !== null && routeIds.includes(activeRouteId)
    const isTipOfReadingRoute = readingRouteId != null
      && view.routes.some((route) => route.id === readingRouteId
        && route.tipNodeId === instance.canonicalNodeId)
    const readingRouteAnswer = readingRouteId === null
      ? undefined
      : answers.find((answer) => answer.routeId === readingRouteId) ?? null
    /**
     * 可回答 = (a) 运行路线的当前节点未答（原语义，逐字未变），或
     *         (b) 用户**显式聚焦**的那条路线（Focus / 只看这条路线）的末端未答。
     *
     * (b) 是多路线独立的那一半：聚焦 B 的末端时可以直接回答 B，即使运行路线仍是
     * A —— 答案写入 B（提交时带显式路线），A 的链完全不受影响。
     *
     * 门槛故意收紧到"显式 Focus"：默认视图（无 Focus）下可回答节点仍然只有运行
     * 路线的当前节点，绝不会因为某条分支刚好只有一个归属就冒出第二个作答入口。
     */
    const canAnswer = (isCurrent && !answers.some((answer) => answer.routeId === activeRouteId))
      || (readingRouteId !== null
        && readingRouteId !== activeRouteId
        && uiState.focusRouteId === readingRouteId
        && isTipOfReadingRoute
        && readingRouteAnswer === null)
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
        readingRouteId,
        isTipOfReadingRoute,
        isCurrent,
        canAnswer,
        isExpanded: uiState.expandedNodeIds.includes(instance.visualNodeKey)
          || uiState.expandedNodeIds.includes(instance.canonicalNodeId),
        isShared: routeIds.length > 1,
        isLatest: instance.canonicalNodeId === activeTipNodeId
          && !activeTipHasAnswer
          && routeIds.includes(activeRouteId ?? ''),
        qLabel: labeledQuestionNodes.has(instance.visualNodeKey) ? '问题' : null,
        routeMembership,
        visualWeight,
        runtimeStatus: input.runtimeByNode?.[instance.canonicalNodeId]?.status ?? null,
        runtimePhase: input.runtimeByNode?.[instance.canonicalNodeId]?.phase ?? null,
        runtimeMessage: null,
        runtimeProgress: input.runtimeByNode?.[instance.canonicalNodeId]?.progress ?? null,
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

  // Pending cards are presentation projections of in-flight AgentRuns. They
  // are never added to the canonical GraphWorkspaceView and are replaced by
  // the real persisted nodes after their runs complete.
  const pendings = input.pendings ?? []
  for (const pending of pendings) {
    const pendingRoute = view.routes.find((route) => route.id === pending.routeId)
    if (!pendingRoute || !visibleRouteIds.has(pending.routeId)) continue
    const pendingId = `pending:${pending.runId}`
    const parentInstance = visibleInstances.find((instance) =>
      instance.canonicalNodeId === pending.sourceNodeId
      && instance.routeIds.includes(pending.routeId),
    )
    const parentKey = parentInstance?.visualNodeKey ?? null
    const pendingLabel = routeLabel(pendingRoute)
    const pendingNode: GraphWorkspaceNodeView = {
      id: pendingId,
      projectId: view.projectId,
      parentNodeId: pending.sourceNodeId,
      supersedesNodeId: null,
      question: pending.status === 'FAILED'
        ? agentOperationFailureLabel(pending.operation)
        : agentOperationProgressLabel(pending.operation),
      purpose: null,
      options: [],
      allowFreeAnswer: false,
      allowMultiSelect: false,
      createdAt: '1970-01-01T00:00:00.000Z',
      kind: 'INTERACTION',
      subtype: 'QUESTION',
      content: {},
      authorKind: 'RUNTIME',
      knowledgeStatus: null,
      userEditableDraft: false,
    }
    const pendingPosition = savedPositions[pendingId]
      ?? placeNewNode(parentKey ? positions[parentKey] ?? null : null, Object.values(positions), {
        // Pending 卡在问题卡之上还渲染运行过程面板，实际高度远大于默认
        // 声明盒；碰撞检查按真实高度走，两张并发/连续失败卡才不会叠放。
        height: estimateNodeCardHeight(pendingNode) + 180,
      })
    // Register the slot so a second concurrent card never overlaps the first.
    positions[pendingId] = pendingPosition
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
        runtimeProgress: pending.progress ?? null,
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
  // 语义关系：与 lineage 边在样式上区分（次级虚线）。关系不属于任何路线；
  // relationEndpointKey 用 focus（绝不借 Active）来稳定锚定 shared visual
  // instance；无法稳定锚定时 relation 边不画，事实仍通过 view.relations 供
  // Inspector 读取。全局 relation layer 默认关闭；选中节点的 direct 1-hop
  // 关系在任何情况下都可见（刚创建时 endpoints 保持选中 → 立即可见），
  // 取消选择后收起。
  const selectedNodeIds = uiState.selectedNodeIds ?? []
  const relationLayerOn = uiState.showRelationLayer === true
  const relationVisible = (relation: GraphWorkspaceRelationView): boolean => {
    if (relationLayerOn) return true
    const source = relationEndpointKey(
      visibleInstances, relation.sourceNodeId, uiState.focusRouteId, visibleKeys)
    const target = relationEndpointKey(
      visibleInstances, relation.targetNodeId, uiState.focusRouteId, visibleKeys)
    if (!source || !target) return false
    return selectedNodeIds.includes(source) || selectedNodeIds.includes(target)
  }
  for (const relation of view.relations) {
    if (!relationVisible(relation)) continue
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
