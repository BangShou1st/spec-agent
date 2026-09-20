<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { routerKey } from 'vue-router'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import AgentProposalCard from '@/components/workspace/AgentProposalCard.vue'
import RecoveryNotice from '@/components/workspace/RecoveryNotice.vue'
import SpecDock from '@/components/workspace/SpecDock.vue'
import ConfirmRouteActionDialog from '@/components/ConfirmRouteActionDialog.vue'
import RouteActionDialog from '@/components/RouteActionDialog.vue'
import ResourceDialog from '@/components/ResourceDialog.vue'
import RegenerateNodeDialog from '@/components/RegenerateNodeDialog.vue'
import GraphCanvas from '@/components/graph/GraphCanvas.vue'
import RelationProposalDialog from '@/components/graph/RelationProposalDialog.vue'
import ResizableSidebar from '@/components/workspace/ResizableSidebar.vue'
import RouteSidebar from '@/components/workspace/RouteSidebar.vue'
import WorkspaceInspector from '@/components/workspace/WorkspaceInspector.vue'
import {
  projectGraph,
  getVisibleRouteIds,
  type ContextualAiTarget,
  type GraphNodeRuntimeState,
  type GraphPendingProjection,
  type GraphRunProgress,
  type SpecAgentGraphNodeData,
} from '@/graph/graphProjection'
import { resolveReadingRouteId } from '@/graph/graphInteraction'
import {
  recoveryNoticeFromState,
  type RecoveryAction,
  type RecoveryNoticeModel,
} from '@/presentation/recoveryPresentation'
import { productErrorMessage, requiresModelSettings } from '@/api/errorCopy'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useRunRegistryStore, type RunRegistryEntry } from '@/stores/runRegistryStore'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { SpecExportVariant } from '@/api/spec'
import type { RegenerateNodeRequest, SubmitAnswerRequest } from '@/api/types'

/**
 * Graph-first workspace shell with fixed Route / Graph / Inspector regions.
 *
 * Layout: left RouteSidebar + center GraphCanvas + right WorkspaceInspector,
 * composed with the existing ResizableSidebar infrastructure. Runtime commands
 * go through workspaceStore (Active-route only); Focus/Dim/Hide/positions/
 * sidebars live in graphUiStore (browser-only). Focus drives shared-node
 * reading; Active remains runtime-only.
 */
const props = defineProps<{ projectId: string }>()

const store = useWorkspaceStore()
const graphUi = useGraphUiStore()
const runRegistry = useRunRegistryStore()
const router = inject(routerKey, null)
const canvasRef = ref<InstanceType<typeof GraphCanvas> | null>(null)

const forkDialogOpen = ref(false)
const resourceDialogOpen = ref(false)
const reanswerDialogOpen = ref(false)
const regenerateDialogOpen = ref(false)
const confirmAction = ref<'archive' | null>(null)
const confirmRouteId = ref<string | null>(null)
const forkNodeId = ref<string | null>(null)
const regenerateNodeId = ref<string | null>(null)
const reanswerNodeId = ref<string | null>(null)

/**
 * 项目身份必须在 setup 阶段就建立，不能等 onMounted。
 *
 * 本组件与 WorkspaceInspector 里所有 `{ immediate: true }` 的 watcher 都在
 * setup 中执行（早于 onMounted）。若等到 onMounted 才切换身份，它们会用
 * **上一个项目**的 projectId / graphView 去发请求：上一个项目还在时只是
 * 一次脏读，已被删除时就是 404 PROJECT_NOT_FOUND，且错误会盖在新项目上。
 */
graphUi.initProject(props.projectId)
store.beginProject(props.projectId)

onMounted(() => {
  void store.loadWorkspace(props.projectId).then(() => {
    void store.refreshUndoRedoAvailability()
  })
  window.addEventListener('keydown', handleGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
})

/**
 * 全局撤销/重做快捷键（Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y）。输入框、下拉等
 * 可编辑元素聚焦时不劫持——文本编辑的原生撤销优先于图撤销。
 */
function handleGlobalKeydown(event: KeyboardEvent): void {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return
  const target = event.target as HTMLElement | null
  if (target
    && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
      || target.tagName === 'SELECT' || target.isContentEditable)) {
    return
  }
  const key = event.key.toLowerCase()
  if (key === 'z') {
    event.preventDefault()
    if (event.shiftKey) void store.redoGraph()
    else void store.undoGraph()
  } else if (key === 'y') {
    event.preventDefault()
    void store.redoGraph()
  }
}

// 每次 canonical 刷新后，浏览器视图状态与后端 graph 对齐。
watch(
  () => store.graphView,
  (view) => {
    graphUi.reconcile(view)
  },
)

const selectedNodeData = computed<SpecAgentGraphNodeData | null>(() => {
  if (!store.graphView || !graphUi.primarySelectedNodeId) {
    return null
  }
  const projection = projectGraph({
    view: store.graphView,
    activeNodeId: store.activeState?.activeNode?.id ?? null,
    uiState: {
      focusRouteId: graphUi.focusRouteId,
      lifecycleFilters: graphUi.lifecycleFilters,
      routeDisplayStates: graphUi.routeDisplayStates,
      isolatedRouteId: graphUi.isolatedRouteId,
      expandedNodeIds: graphUi.expandedNodeIds,
      showRelationLayer: graphUi.showRelationLayer,
    },
    savedPositions: graphUi.nodePositions,
  })
  return projection.nodes.find((node) => node.id === graphUi.primarySelectedNodeId)?.data ?? null
})

const selectedEdgeData = computed(() => {
  if (!graphUi.selectedEdgeId) return null
  if (graphUi.selectedEdgeId.startsWith('relation:')) {
    const relationId = graphUi.selectedEdgeId.slice('relation:'.length)
    const relation = store.graphView?.relations.find((entry) => entry.id === relationId) ?? null
    return {
      id: graphUi.selectedEdgeId,
      kind: 'relation' as const,
      relationType: relation?.relationType ?? null,
      routeIds: [] as string[],
    }
  }
  return {
    id: graphUi.selectedEdgeId,
    kind: graphUi.selectedEdgeId.startsWith('replacement:') ? 'replacement' as const : 'lineage' as const,
    relationType: null,
    routeIds: [...graphUi.selectedSharedEdgeRouteIds],
  }
})

const forkNodeData = computed(() =>
  forkNodeId.value
    ? store.graphView?.nodes.find((node) => node.id === forkNodeId.value) ?? null
    : null,
)

const regenerateNodeData = computed(() =>
  regenerateNodeId.value
    ? store.graphView?.nodes.find((node) => node.id === regenerateNodeId.value) ?? null
    : null,
)

const reanswerNodeData = computed(() =>
  reanswerNodeId.value
    ? store.graphView?.nodes.find((node) => node.id === reanswerNodeId.value) ?? null
    : null,
)

/**
 * Resolves the source route for a node command (fork / reanswer / regenerate).
 *
 * Uses the SAME deterministic resolver as the canvas projection, so a node can
 * never read under one route while its commands have no source route at all.
 * 只看这条路线 / 点卡片定下的 Focus 直接生效；当其它归属路线都被隐藏/筛掉时，
 * 唯一可见的那条也被视为已确定；只有真正歧义（多归属且都可见且无 Focus）时
 * 才返回 null，此时卡片会显式要求用户选一条。
 */
function sourceRouteForNode(nodeId: string | null) {
  if (!nodeId || !store.graphView) return null
  const memberships = store.graphView.routes.filter((route) => route.lineageNodeIds.includes(nodeId))
  const visible = getVisibleRouteIds(store.graphView, {
    lifecycleFilters: graphUi.lifecycleFilters,
    routeDisplayStates: graphUi.routeDisplayStates,
    isolatedRouteId: graphUi.isolatedRouteId,
  })
  const resolved = resolveReadingRouteId({
    membershipRouteIds: memberships.map((route) => route.id),
    visibleRouteIds: visible,
    focusRouteId: graphUi.focusRouteId,
  })
  return memberships.find((route) => route.id === resolved) ?? null
}

const forkSourceRoute = computed(() => sourceRouteForNode(forkNodeId.value))
const reanswerSourceRoute = computed(() => sourceRouteForNode(reanswerNodeId.value))
const regenerateSourceRoute = computed(() => sourceRouteForNode(regenerateNodeId.value))
const workspaceErrorMessage = computed(() =>
  productErrorMessage(store.error?.code ?? 'UNKNOWN_ERROR', store.error?.message),
)
const workspaceRetryLabel = computed(() => {
  if (store.error && requiresModelSettings(store.error.code)) return '前往模型设置'
  if (store.answerOutcomeUnknown) return '刷新状态'
  if (store.repairableAnswerId) return '重新请求'
  if (store.resubmitAnswerPayload) return '再次提交'
  if (store.manualModelRetry?.state === 'needs_reconcile'
    || store.manualModelRetry?.state === 'ambiguous') return '刷新状态'
  if (store.manualModelRetry?.state === 'ready') return '重新请求'
  return '刷新状态'
})
const workspaceRetrying = computed(() => store.loading || store.refreshing
  || store.submitting || store.repairingAnswer || store.drafting
  || store.routeCommandPending || store.generatingSpec)

/**
 * 统一恢复提示：一次最多一个。model settings 错误仍走普通错误条；
 * 已被恢复模型覆盖的状态不再渲染旧的三块恢复按钮。
 */
const recoveryModel = computed<RecoveryNoticeModel | null>(() => {
  if (store.error && requiresModelSettings(store.error.code)) return null
  return recoveryNoticeFromState({
    answerOutcomeUnknown: store.answerOutcomeUnknown,
    repairableAnswerId: store.repairableAnswerId,
    resubmitAnswerPayload: store.resubmitAnswerPayload,
    manualRetryState: store.manualModelRetry?.state ?? null,
    errorCode: store.error?.code ?? null,
  })
})

/**
 * 恢复提示与错误条各自独立渲染：恢复提示描述"待恢复的旧状态"（如可重试的
 * 失败运行），错误条描述"用户最新一次操作的失败"。历史行为是恢复提示独占
 * 状态层，导致用户后续任何操作失败（拖线被拒、路线命令 409 等）完全无反馈，
 * 表现为"点了没反应"。
 */
const showPlainErrorBanner = computed(() =>
  store.error !== null && !requiresModelSettings(store.error.code),
)

/** 待确认提案的节点上下文：来源问题的可读标题（截断）。 */
const confirmingProposalId = ref<string | null>(null)
const confirmableNodeContext = (inputNodeId: string | null): string | null => {
  if (!inputNodeId) return null
  const node = store.graphView?.nodes.find((candidate) => candidate.id === inputNodeId)
  const raw: unknown = node?.question ?? node?.content?.text
  const title = typeof raw === 'string' ? raw : null
  return title ? title.slice(0, 30) : null
}
async function handleAcceptConfirmable(proposalId: string): Promise<void> {
  confirmingProposalId.value = proposalId
  try {
    await store.acceptConfirmableProposal(proposalId)
  } finally {
    confirmingProposalId.value = null
  }
}
async function handleRejectConfirmable(proposalId: string): Promise<void> {
  confirmingProposalId.value = proposalId
  try {
    await store.rejectConfirmableProposal(proposalId)
  } finally {
    confirmingProposalId.value = null
  }
}

/**
 * tip 是"未回答问题"的路线集合：在这些路线上起草下一个问题注定被后端
 * UNANSWERED_QUESTION_HAS_CHILD 不变式拒绝，入口必须前置置灰（D1）。
 * 判定与后端一致：tip 存在、是问题节点、且该路线上没有它的已确认回答。
 */
const draftBlockedRouteIds = computed<string[]>(() => {
  const view = store.graphView
  if (!view) return []
  const answeredNodeIds = new Set(
    view.answers.map((answer) => `${answer.routeId}:${answer.nodeId}`),
  )
  return view.routes
    .filter((route) => {
      if (!route.tipNodeId) return false
      const tip = view.nodes.find((node) => node.id === route.tipNodeId)
      if (!tip || tip.kind !== 'INTERACTION') return false
      return !answeredNodeIds.has(`${route.id}:${route.tipNodeId}`)
    })
    .map((route) => route.id)
})

/** Registry 条目的白名单过程内容（summary + steps）。 */
function runProgressOf(entry: RunRegistryEntry | undefined): GraphRunProgress | null {
  if (!entry) return null
  return { summary: entry.summary, steps: entry.steps }
}

/**
 * 画布 pending 卡列表：run 注册表是唯一权威来源；draft 流程的
 * pendingRouteProjection（带失败文案等临时状态）优先生效，其余未绑定到
 * 具体节点的 in-flight run（重新起草、后台续跑、刷新后重建等）各自成卡。
 * 多条路线并发生成时每条路线一张卡。
 */
const pendingProjections = computed<GraphPendingProjection[]>(() => {
  const projections: GraphPendingProjection[] = []
  const seen = new Set<string>()
  const legacy = store.pendingRouteProjection
  if (legacy) {
    seen.add(legacy.runId)
    projections.push({
      ...legacy,
      operation: 'DRAFT_QUESTION',
      progress: runProgressOf(runRegistry.runs[legacy.runId]),
    })
  }
  for (const entry of runRegistry.list) {
    if (seen.has(entry.runId)) continue
    if (entry.status === 'SUCCEEDED') continue
    if (entry.sourceNodeId) continue // 绑定到既有节点：走 runtimeByNode 叠加
    if (!entry.routeId) continue
    projections.push({
      routeId: entry.routeId,
      sourceNodeId: entry.sourceNodeId,
      runId: entry.runId,
      status: entry.status,
      phase: entry.phase,
      message: null,
      operation: entry.operation,
      progress: runProgressOf(entry),
    })
  }
  return projections
})

/** 既有节点上的运行时叠加：源节点已知的 in-flight run（回答/重生成/续修）。 */
const runtimeByNode = computed<Record<string, GraphNodeRuntimeState>>(() => {
  const map: Record<string, GraphNodeRuntimeState> = {}
  for (const entry of runRegistry.list) {
    if (!entry.sourceNodeId || entry.status === 'SUCCEEDED') continue
    map[entry.sourceNodeId] = {
      status: entry.status,
      phase: entry.phase,
      progress: runProgressOf(entry),
    }
  }
  return map
})

/**
 * 中央一行状态已移除：过程展示收敛到画布节点内（pending 卡与既有节点的
 * runtime 叠加，见 pendingProjections / runtimeByNode），中央不再有第二个
 * 并行的过程展示面。终态反馈仍由 toast / Recovery / 横幅承担。
 */

const forkFinalizedRouteIds = computed(() => {
  if (!forkNodeId.value || !store.graphView) return []
  return store.graphView.answers
    .filter((answer) => answer.nodeId === forkNodeId.value)
    .map((answer) => answer.routeId)
})

/** Spec Dock 阅读路线：显式 Focus，单路线回退；绝不隐式改 Focus/Active。 */
const specReadingRouteId = computed<string | null>(() => {
  const focused = graphUi.readingRouteId()
  if (focused) return focused
  const routes = store.graphView?.routes ?? []
  return routes.length === 1 ? routes[0].id : null
})
const specReadingRouteLabel = computed(() => {
  if (!specReadingRouteId.value) return '未选择'
  return store.graphView?.routes.find((route) => route.id === specReadingRouteId.value)?.label?.trim() || '当前路线'
})
const specActiveRouteLabel = computed(() =>
  store.activeRoute?.label?.trim() || '当前路线',
)
const specSnapshots = computed(() =>
  specReadingRouteId.value ? store.specsByRoute[specReadingRouteId.value] ?? [] : [],
)
const specSelectedId = computed(() =>
  specReadingRouteId.value ? store.selectedSpecIdByRoute[specReadingRouteId.value] ?? null : null,
)

// 读取路线变化时，规格历史从后端加载（与 Inspector 的需求加载同语义）。
// 只有当 store 已经属于当前项目时才读：这是一条显式不变量，任何一次
// 用别的项目的 id 发起的路线级读取都是 bug（旧项目被删就是 404）。
watch(
  specReadingRouteId,
  (routeId) => {
    if (routeId && store.projectId === props.projectId) {
      void store.loadRouteSpecs(routeId)
    }
  },
  { immediate: true },
)

async function handleGenerateSpec(): Promise<void> {
  const generated = await store.generateSpec()
  if (generated) {
    graphUi.setFocusRoute(store.activeRoute?.id ?? null)
  }
}

async function handleExportSpec(snapshotId: string, variant: SpecExportVariant): Promise<void> {
  await store.exportSpecMarkdown(snapshotId, variant)
}

function handleSelectSpec(snapshotId: string): void {
  if (specReadingRouteId.value) {
    store.selectSpecForRoute(specReadingRouteId.value, snapshotId)
  }
}

/**
 * Spec Dock 展开/折叠会改变 Graph 区域高度。等 Vue Flow 重新测量画布尺寸
 * 后，若当前 answerable node 被 Dock 顶部裁切，则只在 viewport 层把它带
 * 回可视区域 —— 绝不移动节点坐标或已保存位置。
 */
/**
 * Spec Dock 展开/折叠会改变 Graph 区域高度。Vue Flow 的 canvas 尺寸由
 * ResizeObserver 异步测量；等测量完成后，若当前 answerable node 被 Dock
 * 顶部裁切，则只在 viewport 层把它带回可视区域 —— 绝不移动节点坐标或已保存位置。
 *
 * 延迟定时器在组件卸载时必须清掉：否则它会跨越测试环境销毁继续执行
 * （jsdom 拆掉后 window/rAF 都不存在），变成 unhandled rejection。
 */
let specDockResizeTimer: number | null = null

function clearSpecDockResizeTimer(): void {
  if (specDockResizeTimer !== null) {
    window.clearTimeout(specDockResizeTimer)
    specDockResizeTimer = null
  }
}

function handleSpecDockExpandedChange(): void {
  clearSpecDockResizeTimer()
  specDockResizeTimer = window.setTimeout(() => {
    specDockResizeTimer = null
    void nextTick(() => {
      // jsdom (unit tests) has no requestAnimationFrame; fall back to a timer so
      // the callback never becomes an unhandled rejection in the test run.
      const scheduleFrame = typeof window.requestAnimationFrame === 'function'
        ? window.requestAnimationFrame.bind(window)
        : (callback: FrameRequestCallback) => window.setTimeout(() => callback(0), 0)
      scheduleFrame(() => {
        const canvas = canvasRef.value as { ensureActiveNodeInView?: () => void } | null
        if (typeof canvas?.ensureActiveNodeInView === 'function') {
          canvas.ensureActiveNodeInView()
        }
      })
    })
  }, 160)
}

onUnmounted(clearSpecDockResizeTimer)

const reanswerFinalized = computed(() => {
  if (!reanswerNodeId.value || !reanswerSourceRoute.value || !store.graphView) return false
  return store.graphView.answers.some((answer) => answer.nodeId === reanswerNodeId.value
    && answer.routeId === reanswerSourceRoute.value!.id)
})

/** 恢复 CTA 的语义意图 → 已有 store 命令，不新增语义。 */
async function handleRecoveryAction(action: RecoveryAction): Promise<void> {
  if (action === 'reconcile-answer') {
    await store.reconcileAnswerOutcome()
  } else if (action === 'resume-answer') {
    if (store.repairableAnswerId) {
      await store.repairAnswerForActiveFlow(store.repairableAnswerId)
    }
  } else if (action === 'resubmit-answer') {
    await store.resubmitFailedAnswer()
  } else if (action === 'retry-model-operation') {
    const retryKind = store.manualModelRetry?.kind
    const ok = await store.retryManualModelOperation()
    if (ok && retryKind === 'regenerate') {
      await focusAfterMutation()
    }
  } else {
    await store.refreshWorkspace()
  }
}

async function retry(): Promise<void> {
  if (store.error && requiresModelSettings(store.error.code)) {
    if (router) await router.push({ name: 'settings' })
  } else if (store.answerOutcomeUnknown) {
    await store.reconcileAnswerOutcome()
  } else if (store.repairableAnswerId) {
    await store.repairAnswerForActiveFlow(store.repairableAnswerId)
  } else if (store.resubmitAnswerPayload) {
    await store.resubmitFailedAnswer()
  } else if (store.manualModelRetry) {
    const retryKind = store.manualModelRetry.kind
    const ok = await store.retryManualModelOperation()
    if (ok && retryKind === 'regenerate') {
      await focusAfterMutation()
    }
  } else {
    await store.loadWorkspace(props.projectId)
  }
}

async function focusAfterMutation(): Promise<void> {
  const target = store.consumeFocusAfterMutation()
  const routeId = target?.routeId ?? store.activeState?.activeRoute?.id ?? null
  const nodeId = target?.nodeId ?? store.activeState?.activeNode?.id ?? null
  if (!routeId) return
  graphUi.setFocusRoute(routeId)
  await nextTick()
  if (nodeId) await canvasRef.value?.locateNode(nodeId)
}

async function handleDraft(): Promise<void> {
  await store.draftQuestion()
}

/** 显式路线的"起草下一个问题"：路线卡菜单 / 已回答末端节点 / 末端知识卡发起。 */
async function handleDraftNext(routeId: string): Promise<void> {
  await store.draftQuestion(routeId)
}

/** "+ 想法"：创建独立想法（不与任何节点连接），聚焦并直接进入编辑。 */
async function handleAddIdea(): Promise<void> {
  const nodeId = await store.createIdea()
  if (!nodeId) return
  await nextTick()
  graphUi.selectNode(nodeId)
  graphUi.requestNodeEdit(nodeId)
  await canvasRef.value?.locateNode(nodeId)
}

/**
 * 图上拖线 = Pending Relation Proposal。只打开确认器，不调用 backend；
 * 用户选择类型/方向并确认后才持久化。Cancel/Esc/click-away 保持 0 关系。
 */
function handleRelationProposal(payload: {
  sourceNodeId: string
  targetNodeId: string
}): void {
  graphUi.setPendingRelation(payload)
}

function relationNodeLabel(nodeId: string | null | undefined): string {
  if (!nodeId) return ''
  const node = store.graphView?.nodes.find((candidate) => candidate.id === nodeId)
  if (!node) return nodeId.slice(0, 8)
  if (node.question) return node.question.slice(0, 24)
  const text = node.content?.text
  return typeof text === 'string' && text ? text.slice(0, 24) : nodeId.slice(0, 8)
}

/**
 * 把画布上"浮动节点 ↔ 路线节点"的一条连线变成接入命令。
 *
 * 路线来源必须是**显式**的：先看该锚点属于哪些 OPEN 路线（锚点通常就是路线
 * 末端），再用同一套确定性解析（Focus / 只看这条路线 / 唯一可见归属）坍缩成
 * 单值。解析不出来就明确失败，绝不猜 Active/first/latest —— 与共享节点的
 * 来源路线规则保持一致，也避免把资源接到用户没在看的那条链上。
 */
async function handleConnectFloating(payload: {
  floatingNodeId: string
  anchorNodeId: string
}): Promise<void> {
  if (!store.graphView) return
  const view = store.graphView
  const anchorNode = view.nodes.find((node) => node.id === payload.anchorNodeId)
  const anchorLabel = anchorNode?.question?.slice(0, 24) ?? '该节点'
  // 后端只接受"当前末端"作为父节点，所以候选路线必须是该锚点为 tip 的路线。
  const candidates = view.routes.filter(
    (route) => route.lifecycleStatus === 'open' && route.tipNodeId === payload.anchorNodeId,
  )
  if (candidates.length === 0) {
    store.error = {
      code: 'CONNECT_REQUIRES_ROUTE_TIP',
      message: `只能接入「${anchorLabel}」所在路线的末端；请连到某条路线最末端的节点`,
    }
    return
  }
  const visible = getVisibleRouteIds(view, {
    lifecycleFilters: graphUi.lifecycleFilters,
    routeDisplayStates: graphUi.routeDisplayStates,
    isolatedRouteId: graphUi.isolatedRouteId,
  })
  const routeId = resolveReadingRouteId({
    membershipRouteIds: candidates.map((route) => route.id),
    visibleRouteIds: visible,
    focusRouteId: graphUi.focusRouteId,
  })
  const route = candidates.find((candidate) => candidate.id === routeId) ?? null
  if (!route) {
    store.error = {
      code: 'SOURCE_ROUTE_REQUIRED',
      message: '该节点是多条路线的末端：请先点击要接入的路线卡（或使用「只看这条路线」），再连线',
    }
    return
  }
  await store.connectFloatingNode(payload.floatingNodeId, route.id, payload.anchorNodeId)
}

async function handleRelationConfirm(payload: {
  sourceNodeId: string
  targetNodeId: string
  relationType: string
}): Promise<void> {
  // 先关 proposal(用户已确认方向/类型),再持久化;失败由 store.error 呈现。
  graphUi.clearPendingRelation()
  const ok = await store.createSemanticRelation(
    payload.sourceNodeId,
    payload.targetNodeId,
    payload.relationType as 'RELATED_TO' | 'DEPENDS_ON' | 'DERIVED_FROM' | 'CONFLICTS_WITH' | 'SUPPORTS',
  )
  if (ok) {
    // Endpoints stay selected so the just-created relation is immediately
    // visible without the global Show All toggle.
    graphUi.selectNode(payload.sourceNodeId)
  }
}

/**
 * 添加资源 = 创建一个**独立**资源节点（零模型调用、不依赖 Active 路线）。
 * 归属路线由用户在画布上连线决定（见 handleConnectFloating）。
 */
async function handleCreateFloatingResource(
  subtype: 'TEXT' | 'URL' | 'FILE',
  content: Record<string, unknown>,
): Promise<void> {
  const ok = await store.createFloatingResource(subtype, content)
  if (ok) resourceDialogOpen.value = false
}

async function handleAnswer(payload: SubmitAnswerRequest): Promise<void> {
  await store.submitAnswer(payload)
}

function handleFork(nodeId: string): void {
  forkNodeId.value = nodeId
  forkDialogOpen.value = true
}

function handleReanswer(nodeId: string): void {
  reanswerNodeId.value = nodeId
  reanswerDialogOpen.value = true
}

/**
 * 断开接入：路线末端或挂在谱系下的出处节点都可断开（后端同规则校验）。
 * 归属路线沿 parentNodeId 向上解析（出处子节点不在 lineageNodeIds 父链上）；
 * 多条路线共享时要求先选定查看路线，绝不猜测。
 */
async function handleDisconnect(nodeId: string): Promise<void> {
  const view = store.graphView
  if (!view) return
  const node = view.nodes.find((candidate) => candidate.id === nodeId)
  const ancestors = new Set<string>()
  let parent = node?.parentNodeId ?? null
  while (parent != null && !ancestors.has(parent)) {
    ancestors.add(parent)
    parent = view.nodes.find((candidate) => candidate.id === parent)?.parentNodeId ?? null
  }
  const candidates = view.routes.filter(
    (route) => route.lifecycleStatus === 'open'
      && ((route.lineageNodeIds ?? []).includes(nodeId)
        || (route.lineageNodeIds ?? []).some((id) => ancestors.has(id))),
  )
  if (candidates.length === 0) {
    store.error = {
      code: 'DISCONNECT_REQUIRES_ROUTE_TIP',
      message: '只有已接入路线的节点可以断开',
    }
    return
  }
  if (candidates.length > 1) {
    store.error = {
      code: 'SOURCE_ROUTE_REQUIRED',
      message: '该节点属于多条路线：请先在「当前查看路线」中选择要断开的那条',
    }
    return
  }
  await store.disconnectNode(nodeId, candidates[0]!.id)
}

function handleRegenerate(nodeId: string): void {
  regenerateNodeId.value = nodeId
  regenerateDialogOpen.value = true
}

/**
 * 回答历史未答问题 = 激活其显式所属路线。用户必须先通过查看路线选择器
 * 选定一条明确的路线（graphUi.focusRouteId）；共享/多归属节点绝不回退到
 * Active/first/latest。激活是纯 route activation，不创建 RESUME 分支。
 */
async function handleActivateRouteForAnswer(routeId: string): Promise<void> {
  if (!routeId) {
    store.error = {
      code: 'SOURCE_ROUTE_REQUIRED',
      message: '请先选择明确的来源路线',
    }
    return
  }
  const ok = await store.activateRoute(routeId)
  if (ok) {
    await focusAfterMutation()
  }
}

function resolveContextualAiTarget(target: ContextualAiTarget): string | null {
  if (!store.graphView || !target.canonicalNodeId || !target.visualNodeKey) return null
  const projection = projectGraph({
    view: store.graphView,
    activeNodeId: store.activeState?.activeNode?.id ?? null,
    uiState: {
      focusRouteId: graphUi.focusRouteId,
      lifecycleFilters: graphUi.lifecycleFilters,
      routeDisplayStates: graphUi.routeDisplayStates,
      isolatedRouteId: graphUi.isolatedRouteId,
      expandedNodeIds: graphUi.expandedNodeIds,
      showRelationLayer: graphUi.showRelationLayer,
    },
    savedPositions: graphUi.nodePositions,
  })
  const targetNode = projection.nodes.find((node) =>
    node.id === target.visualNodeKey
      && node.data?.canonicalNodeId === target.canonicalNodeId,
  )
  return targetNode?.id ?? null
}

function handleContextualAi(target: ContextualAiTarget): void {
  const visualNodeKey = resolveContextualAiTarget(target)
  if (!visualNodeKey) return
  graphUi.selectNode(visualNodeKey)
  graphUi.setRightSidebar({ open: true, width: graphUi.rightSidebarWidth })
}

async function handleForkSubmit(label: string | null): Promise<void> {
  if (!forkNodeId.value) {
    return
  }
  const sourceRouteId = forkSourceRoute.value?.id
  if (!sourceRouteId) return
  const ok = await store.forkNode(forkNodeId.value, sourceRouteId, label)
  forkDialogOpen.value = false
  if (ok || store.forkDraftRetryRouteId) {
    await focusAfterMutation()
    forkNodeId.value = null
  }
}

async function handleReanswerSubmit(label: string | null): Promise<void> {
  if (!reanswerNodeId.value) return
  const sourceRouteId = reanswerSourceRoute.value?.id
  if (!sourceRouteId) return
  const ok = await store.reanswerNode(reanswerNodeId.value, sourceRouteId, label)
  if (ok) {
    graphUi.setFocusRoute(store.activeState?.activeRoute?.id ?? null)
    await nextTick()
    await canvasRef.value?.locateNode(store.activeState?.activeNode?.id ?? '')
    reanswerDialogOpen.value = false
    reanswerNodeId.value = null
  }
}

/** Fork prerequisites are explicit, local dialog actions. Each command
 * refreshes canonical state while the dialog remains open; no command is
 * chained into an implicit Fork. */
async function handleForkRestore(routeId: string): Promise<void> {
  await store.restoreRoute(routeId)
}

async function handleRegenerateSubmit(payload: RegenerateNodeRequest): Promise<void> {
  if (!regenerateNodeId.value) {
    return
  }
  const ok = await store.regenerateNode(regenerateNodeId.value, payload)
  regenerateDialogOpen.value = false
  if (ok) {
    await focusAfterMutation()
    regenerateNodeId.value = null
  }
}

async function retryForkDraft(): Promise<void> {
  const ok = await store.retryForkDraft()
  if (ok) await focusAfterMutation()
}

function handleLocateRoute(routeId: string): void {
  void canvasRef.value?.locateRoute(routeId)
}

function openConfirm(kind: 'archive', routeId: string): void {
  confirmAction.value = kind
  confirmRouteId.value = routeId
}

/** 归档是唯一的"收起路线"动作（软删除入口已移除）；归档后默认从视图隐藏。 */
async function confirmDestructive(): Promise<void> {
  if (!confirmAction.value || !confirmRouteId.value) {
    return
  }
  const ok = await store.archiveRoute(confirmRouteId.value)
  if (ok) {
    confirmAction.value = null
    confirmRouteId.value = null
  }
}
</script>

<template>
  <div class="workspace-shell" data-test="workspace-shell">
    <div v-if="!store.loading" class="workspace-shell__body workspace-shell__body--fixed" data-test="workspace-center">
      <ResizableSidebar
        side="left"
        :open="graphUi.leftSidebarOpen"
        :width="graphUi.leftSidebarWidth"
        :min-width="220"
        :max-width="420"
        @update:open="graphUi.setLeftSidebar({ open: $event, width: graphUi.leftSidebarWidth })"
        @update:width="graphUi.setLeftSidebar({ open: graphUi.leftSidebarOpen, width: $event })"
      >
        <RouteSidebar
          :routes="store.graphView?.routes ?? []"
          :active-route-id="store.activeRoute?.id ?? null"
          :command-pending="store.routeCommandPending"
          :pending-route-command="store.pendingRouteCommand"
          :draft-blocked-route-ids="draftBlockedRouteIds"
          @locate-route="handleLocateRoute"
          @activate="store.activateRoute($event)"
          @restore="store.restoreRoute($event)"
          @archive="openConfirm('archive', $event)"
          @draft-next="handleDraftNext"
        />
      </ResizableSidebar>

      <div class="workspace-shell__center">
        <header class="workspace-shell__header" data-test="workspace-project-badge">
          <h1 class="workspace-shell__title">{{ store.project?.title ?? '工作区' }}</h1>
        </header>

        <div class="workspace-shell__status-layer">
          <RecoveryNotice
            v-if="recoveryModel"
            :model="recoveryModel"
            @action="handleRecoveryAction"
          />
          <ApiErrorBanner
            v-if="showPlainErrorBanner"
            :message="workspaceErrorMessage"
            :code="store.error!.code"
            :retry-label="workspaceRetryLabel"
            :retrying="workspaceRetrying"
            @retry="retry"
          />
          <!-- D2：回答/决策周期的"待确认提案"全局呈现面。没有它，策略层
               要求确认的意图变更对用户完全不可见，提案只能永远挂在库里。 -->
          <AgentProposalCard
            v-for="proposal in store.pendingConfirmableProposals"
            :key="proposal.proposalId"
            :action-family="proposal.actionFamily"
            :message="null"
            :node-context="confirmableNodeContext(proposal.inputNodeId)"
            :accepting="confirmingProposalId === proposal.proposalId"
            :rejecting="confirmingProposalId === proposal.proposalId"
            @accept="handleAcceptConfirmable(proposal.proposalId)"
            @reject="handleRejectConfirmable(proposal.proposalId)"
          />
        </div>

        <div class="workspace-shell__graph-region">
          <GraphCanvas
            ref="canvasRef"
            class="workspace-shell__canvas"
            :view="store.graphView"
            :active-node-id="store.activeState?.activeNode?.id ?? null"
            :submitting="store.submitting"
            :drafting="store.drafting"
            :pending="store.routeCommandPending"
            :runtime-by-node="runtimeByNode"
            :pendings="pendingProjections"
            @draft="handleDraft"
            @draft-next="handleDraftNext"
            @submit-answer="handleAnswer"
            @fork="handleFork"
            @reanswer="handleReanswer"
            @regenerate="handleRegenerate"
            @disconnect="handleDisconnect"
            @activate-route="handleActivateRouteForAnswer"
            @contextual-ai="handleContextualAi"
            @retry-pending="store.retryPendingAgentRun"
            @add-idea="handleAddIdea"
            @add-resource="resourceDialogOpen = true"
            @relation-proposal="handleRelationProposal"
            @connect-floating="handleConnectFloating"
            @undo="store.undoGraph"
            @redo="store.redoGraph"
          />
          <div class="workspace-shell__toast-layer">
            <p v-if="store.refreshing" class="muted workspace-shell__refreshing" data-test="refreshing">
              正在刷新工作区…
            </p>
            <p v-if="store.feedback" class="feedback-line" data-test="feedback" role="status">{{ store.feedback }}</p>
            <button v-if="store.forkDraftRetryRouteId" class="btn btn-primary workspace-shell__retry-draft" data-test="retry-fork-draft" :disabled="workspaceRetrying" @click="retryForkDraft">重试起草</button>
          </div>
        </div>

        <SpecDock
          :reading-route-id="specReadingRouteId"
          :reading-route-label="specReadingRouteLabel"
          :active-route-id="store.activeRoute?.id ?? null"
          :active-route-label="specActiveRouteLabel"
          :snapshots="specSnapshots"
          :selected-spec-id="specSelectedId"
          :generating="store.generatingSpec"
          :exporting="store.exportingSpec"
          :command-pending="store.routeCommandPending"
          @generate-spec="handleGenerateSpec"
          @export-spec="handleExportSpec"
          @select-snapshot="handleSelectSpec"
          @expanded-change="handleSpecDockExpandedChange"
        />
      </div>

      <ResizableSidebar
        side="right"
        :open="graphUi.rightSidebarOpen"
        :width="graphUi.rightSidebarWidth"
        :min-width="300"
        :max-width="600"
        @update:open="graphUi.setRightSidebar({ open: $event, width: graphUi.rightSidebarWidth })"
        @update:width="graphUi.setRightSidebar({ open: graphUi.rightSidebarOpen, width: $event })"
      >
        <WorkspaceInspector
          :node-data="selectedNodeData"
          :selected-edge="selectedEdgeData"
          @fork="handleFork"
          @reanswer="handleReanswer"
          @regenerate="handleRegenerate"
        />
      </ResizableSidebar>
    </div>

    <p v-if="store.loading" class="muted workspace-shell__loading">正在加载工作区…</p>

    <ResourceDialog
      :open="resourceDialogOpen"
      :pending="store.graphCommandPending"
      :route-empty="(store.activeRoute?.tipNodeId ?? null) === null"
      @close="resourceDialogOpen = false"
      @submit="handleCreateFloatingResource"
    />

    <RouteActionDialog
      mode="fork"
      :open="forkDialogOpen"
      :node="forkNodeData"
      :source-route="forkSourceRoute"
      :pending="store.routeCommandPending"
      :finalized="forkSourceRoute ? forkFinalizedRouteIds.includes(forkSourceRoute.id) : false"
      @close="forkDialogOpen = false"
      @submit="handleForkSubmit"
      @restore-source="handleForkRestore"
    />

      <RegenerateNodeDialog
        :open="regenerateDialogOpen"
        :node="regenerateNodeData"
        :source-route-id="regenerateSourceRoute?.id ?? null"
      :pending="store.pendingRouteCommand === 'regenerate'"
      @close="regenerateDialogOpen = false"
      @submit="handleRegenerateSubmit"
      />

    <RouteActionDialog
      mode="reanswer"
      :open="reanswerDialogOpen"
      :node="reanswerNodeData"
      :source-route="reanswerSourceRoute"
      :pending="store.pendingRouteCommand === 'reanswer'"
      :finalized="reanswerFinalized"
      @close="reanswerDialogOpen = false"
      @submit="handleReanswerSubmit"
      @restore-source="store.restoreRoute($event)"
    />

    <ConfirmRouteActionDialog
      :open="confirmAction !== null && confirmRouteId !== null"
      :kind="confirmAction ?? 'archive'"
      :route-label="confirmRouteId ? store.graphView?.routes.find((r) => r.id === confirmRouteId)?.label ?? null : null"
      :pending="store.routeCommandPending"
      @cancel="confirmAction = null; confirmRouteId = null"
      @confirm="confirmDestructive"
    />

    <RelationProposalDialog
      :open="graphUi.pendingRelation !== null"
      :source-node-id="graphUi.pendingRelation?.sourceNodeId ?? null"
      :target-node-id="graphUi.pendingRelation?.targetNodeId ?? null"
      :source-label="relationNodeLabel(graphUi.pendingRelation?.sourceNodeId)"
      :target-label="relationNodeLabel(graphUi.pendingRelation?.targetNodeId)"
      :pending="store.graphCommandPending"
      @confirm="handleRelationConfirm"
      @cancel="graphUi.clearPendingRelation()"
    />

  </div>
</template>
