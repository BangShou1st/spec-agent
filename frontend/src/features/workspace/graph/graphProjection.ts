// 文件名:graphProjection.ts
// 用途:画布投影核心:把后端的 canonical GraphWorkspaceView(节点/路线/回答/关系)加上浏览器 UI 状态(Focus、筛选、镜头、运行时进度)投影成 Vue Flow 可渲染的节点与边,是 Graph 视图层与数据层之间的转换器。
import { MarkerType, type Edge, type Node } from '@vue-flow/core'
import type {
  GraphWorkspaceNodeView,
  GraphWorkspaceOptionView,
  GraphWorkspaceRelationView,
  GraphWorkspaceRouteView,
  GraphWorkspaceView,
  RouteLifecycleStatus,
} from '@/shared/contracts/types'
import type { RunProgressStep, UnresolvedFailure } from '@/features/workspace/api/agentRuns'
export type { UnresolvedFailure }
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
} from '@/features/workspace/presentation/agentPresentation'

export type GraphVisualWeight = 'active' | 'focus' | 'normal' | 'dimmed'

/** 运行时进度与知识状态分开投影。 */
export type GraphRuntimeStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** 执行中节点卡片内部展示的白名单过程内容。 */
export interface GraphRunProgress {
  summary: string | null
  steps: RunProgressStep[]
}

/** 由进行中的 AgentRun 投影出的仅浏览器端卡片。 */
export interface GraphPendingProjection {
  routeId: string
  sourceNodeId: string | null
  runId: string
  status: GraphRuntimeStatus
  phase: string | null
  message: string | null
  operation?: string | null
  progress?: GraphRunProgress | null
  /** 起草/续跑家族的失败任务:占位卡携带服务端判定的恢复身份,
   *  重试按钮据此提交,绝不猜测全局目标。 */
  failure?: UnresolvedFailure | null
}

/** 投影到已有 canonical 节点上的运行时覆盖层。 */
export interface GraphNodeRuntimeState {
  status: GraphRuntimeStatus | null
  phase: string | null
  progress: GraphRunProgress | null
}

/** 节点上的未解决失败恢复状态(服务端判定,按路线精确绑定)。 */
export interface GraphRecoveryState {
  failures: UnresolvedFailure[]
}

/*
 * 上下文 AI 动作同时标识 canonical 节点和用户实际操作的视觉实例。
 * 前者锚定查询;后者在同一个 canonical 节点有多个视觉实例时保留用户
 * 点击的那条分支。
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
  /** 此视觉节点是其当前阅读路线的 canonical 末端时为 true——这是对真正
   * 未回答的问题启用"激活归属路线并作答"的唯一合法场景。 */
  isTipOfReadingRoute?: boolean
  isCurrent: boolean
  canAnswer: boolean
  isExpanded: boolean
  isShared: boolean
  isLatest: boolean
  qLabel: string | null
  routeMembership?: GraphRouteMembershipPresentation[]
  visualWeight: GraphVisualWeight
  /** 运行时事实刻意设为可选,绝不替代 knowledgeStatus。 */
  runtimeStatus?: GraphRuntimeStatus | null
  runtimePhase?: string | null
  runtimeMessage?: string | null
  /** 此节点上进行中 run 的过程内容(步骤 + 摘要)。 */
  runtimeProgress?: GraphRunProgress | null
  /** 该节点(绑定当前阅读路线)上的未解决失败恢复状态。 */
  recovery?: GraphRecoveryState | null
  /** Pending 占位卡携带的失败任务身份(仅占位卡使用)。 */
  pendingFailure?: UnresolvedFailure | null
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
    /** 临时的"只看这条路线"镜头:生效时该路线是唯一可见路线。这是显式的
     * 单路线用户意图,因此优先于生命周期筛选、手动弱化/隐藏以及 Active
     * 路线强制可见规则。绝不持久化。 */
    isolatedRouteId?: string | null
    /** 默认 false。Inspector 仍是关系的权威查看器。 */
    showRelationLayer?: boolean
    /** 已选中的节点 id(视觉 key):即使全局关系层关闭,它们的直接 1-hop
     * 关系也会投影到画布上。 */
    selectedNodeIds?: string[]
  }
  savedPositions: Record<string, GraphPosition>
  /** 任务级失败恢复:键为 `${canonicalNodeId}::${readingRouteId ?? '*'}`,
   *  共享节点不同路线的失败互不覆盖。 */
  recoveryByNode?: Record<string, GraphRecoveryState>
  /** 已有节点上进行中 run 的逐 canonical 节点运行时覆盖层。 */
  runtimeByNode?: Record<string, GraphNodeRuntimeState>
  /** 目标节点尚不存在时,为这些 run 生成仅浏览器端卡片。 */
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

/*
 * 路线可见的条件:若单路线镜头存在则由它选择;没有镜头时,是 Active 路线、
 * 通过生命周期筛选且未被手动隐藏。
 *
 * 镜头先检查且直接获胜:它是一条显式的单路线用户命令,因此可以隐藏
 * Active 路线。所有更弱的规则(生命周期筛选、手动弱化/隐藏、Active 强制
 * 可见)只在无镜头时生效。在此规则之前,对非 Active 路线执行"只看这条
 * 路线"总把运行路线留在画布上,于是第二次只看看起来像无操作。
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

/** canonical 归属关系仍供非视觉消费方使用。 */
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

/** 旧版 canonical 边辅助函数;视觉投影使用 V2 版本。 */
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

/** 物理 lineage 边按视觉端点去重。 */
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
  // 一个 canonical 问题节点全项目只携带一个不可变的回答身份
  // (SHARED_STATE_DIVERGENCE 属于不变式违例)。回答内容绝不依赖
  // Focus/阅读路线——Focus 只改变阅读上下文。始终返回唯一的 canonical
  // 回答,共享的已答问题在 主路线/分支路线/无 Focus 下显示同一回答。
  return nodeAnswers[0] ?? null
}

function fallbackRouteLabel(route: Pick<GraphWorkspaceRouteView, 'branchType' | 'isActive'>): string {
  if (route.branchType === 'fork') return '分支路线'
  if (route.branchType === 'reanswer') return '重新回答路线'
  if (route.branchType === 'regenerate') return '换题路线'
  if (route.branchType === 'continuation') return '探索分支'
  return route.isActive ? '主路线' : '路线'
}

/*
 * 注册表式的节点类型解析:稳定的节点 kind 映射到已注册的卡片组件
 * (见 GraphCanvas 的 nodeTypes)。新的子类型复用既有 kind 的卡片,
 * 绝不为业务单独新增卡片类。
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

/** 计算 AnswerPresentationMode。共享 canonical 节点只携带一个不可变的
 * 回答身份,健康图里不可能出现"分歧摘要"模式;该模式只是纯粹的阅读
 * 上下文信号。 */
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

/*
 * 确定性的卡片高度估算,仅在卡片从未被实测时使用(Vue Flow 会在一帧之后
 * 报告真实高度;"重新自动布局"届时使用实测值)。
 *
 * 为什么需要它:卡片尺寸随内容自适应,固定行距会把一条长笔记堆到下一张
 * 卡片上。首次布局在任何实测之前运行,且其结果会立即持久化——所以没有
 * 估算值时,长笔记项目的首次布局就会重叠。
 *
 * 校准(在真实画布上测量):320px 知识卡片 482 字符 / 20 个换行渲染为
 * 802px 高;320px 交互卡片(148 字符问题 + 40 字符 purpose + 自由文本框)
 * 渲染为 463px。常量刻意略微高估:多留空间只是难看点,空间不足则是
 * 可见的重叠。
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

  /** 文本块的渲染行数:显式换行加上软换行。 */
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

  // 浮动想法(不属于任何路线的灵感)从当前布局右侧的空闲槽位开始,而不是
  // 根列——根列会被画布工具栏遮住,新想法必须立即可见、可点。
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

  // 计算"最新"标记:运行路线末端且未回答。
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
        recovery: input.recoveryByNode?.[
          instance.canonicalNodeId + '::' + (readingRouteId ?? '*')
        ] ?? null,
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

  // Pending 卡片是进行中 AgentRun 的展示投影。它们绝不加入 canonical
  // GraphWorkspaceView,并在 run 完成后被真实持久化的节点取代。
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
    // 注册该槽位,确保第二张并发卡片绝不与第一张重叠。
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
        pendingFailure: pending.failure ?? null,
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

/*
 * 解析关系端点挂在哪个视觉实例上。三态规则,在共享歧义下绝不回退到
 * Active/第一个/最新:
 *
 *  1. 若设置了焦点路线,且 canonical 节点恰好有一个包含该路线的可见
 *     实例 → 使用该实例。
 *  2. 否则,若 canonical 节点恰好有一个可见实例 → 使用它。
 *  3. 否则 → 返回 null(无法确定性地选出视觉实例,关系边就不能画出;
 *     canonical 事实仍保留在读模型与 Inspector 中)。
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
