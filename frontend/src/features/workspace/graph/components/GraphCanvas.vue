<!--
  文件名:GraphCanvas.vue
  用途:Graph-first 工作台画布:基于 Vue Flow 渲染投影后的节点/边,负责选择镜像、拖拽位置持久化、路线/节点定位、空项目占位与显式自动布局;绝不修改运行时(Runtime)状态。
-->
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import {
  VueFlow,
  useVueFlow,
  ConnectionMode,
  type Connection,
  type Dimensions,
  type Node,
  type Edge,
  type NodeChange,
  type NodeDragEvent,
  type NodeProps,
  type NodeMouseEvent,
  type EdgeMouseEvent,
} from '@vue-flow/core'
import AdaptiveGraphEdge from './AdaptiveGraphEdge.vue'
import UiConfirmDialog from '@/shared/ui/UiConfirmDialog.vue'
import GraphKnowledgeNode from './GraphKnowledgeNode.vue'
import GraphQuestionNode from './GraphQuestionNode.vue'
import GraphStartPlaceholder from './GraphStartPlaceholder.vue'
import GraphToolbar from './GraphToolbar.vue'
import { projectGraph, type SpecAgentGraphNodeData } from '@/features/workspace/graph/graphProjection'
import { computeInitialLayout } from '@/features/workspace/graph/graphLayout'
import {
  selectEdgeHandles,
  type NodeGeometry,
} from '@/features/workspace/graph/graphEdgeRouting'
import {
  computeFitNodeViewport,
  computeFitViewport,
  getNodeSize,
  type ViewportNode,
  type ViewportTransform,
} from '@/features/workspace/graph/graphViewport'
import type { GraphPosition } from '@/features/workspace/graph/graphTypes'
import type {
  ContextualAiTarget,
  GraphNodeRuntimeState,
  GraphPendingProjection,
  GraphRecoveryState,
  UnresolvedFailure,
} from '@/features/workspace/graph/graphProjection'
import { useGraphUiStore } from '@/features/workspace/state/graphUiStore'
import type { GraphWorkspaceView, SubmitAnswerRequest } from '@/shared/contracts/types'
import { resolveRouteFocusIntent } from '@/features/workspace/graph/graphInteraction'

/*
 * Graph-first 工作台画布(Phase 7.3)。
 *
 * Vue Flow 负责视口/渲染/选中/拖拽;本组件只负责浏览器侧的接线:canonical
 * 图数据投影、选中镜像、拖拽结束时的成组位置持久化、路线/节点定位、
 * 空项目占位与显式自动布局命令。它绝不修改运行时状态。
 *
 * 所有 fit 类操作都是确定性的:从当前投影出的节点坐标加已知/安全兜底尺寸
 * 计算视口 transform,再用 setViewport 应用。刻意不使用依赖节点测量的
 * Vue Flow `fitView`,这样刷新绝不会基于过期/缺失的节点测量做适配。
 */
const props = defineProps<{
  view: GraphWorkspaceView | null
  activeNodeId: string | null
  submitting: boolean
  drafting: boolean
  pending: boolean
  /** 已有节点上进行中 run 的逐节点运行时覆盖层。 */
  runtimeByNode?: Record<string, GraphNodeRuntimeState>
  /** 任务级失败恢复:键 `${nodeId}::${routeId ?? '*'}`(共享节点不互覆盖)。 */
  recoveryByNode?: Record<string, GraphRecoveryState>
  /** 目标节点尚不存在时,为这些 run 生成仅浏览器端卡片。 */
  pendings?: GraphPendingProjection[]
  safeRegion?: import('@/features/workspace/graph/graphViewport').FitViewportRegion | null
}>()

const emit = defineEmits<{
  draft: []
  'submit-answer': [payload: SubmitAnswerRequest]
  fork: [nodeId: string]
  reanswer: [nodeId: string]
  regenerate: [nodeId: string]
  disconnect: [nodeId: string]
  'add-idea': []
  'add-resource': []
  'contextual-ai': [target: ContextualAiTarget]
  'retry-failure': [failure: UnresolvedFailure]
  'viewport-settled': []
  'activate-route': [routeId: string]
  /** 已回答的路线末端：沿该路线起草下一个问题（显式路线模式）。 */
  'draft-next': [routeId: string]
  // 画布拖线(源 handle → 目标 handle)只抛出一个 PENDING 关系提案;在用户
  // 确认类型与方向之前不持久化任何东西。这取代了旧的"拖线 => 立即
  // RELATED_TO"行为。
  'relation-proposal': [payload: { sourceNodeId: string; targetNodeId: string }],
  /** 浮动节点与已接入路线节点之间拖线:接入意图。 */
  'connect-floating': [payload: { floatingNodeId: string; anchorNodeId: string }]
  // Vue Flow 会把原始 'connect' 事件通过 <VueFlow @connect> 透传;在这里
  // 声明它可以消除 Vue 的"既未在 emits 选项声明也未作为 onConnect prop"
  // 警告,同时记录这层桥接。
  connect: [connection: Connection]
  undo: []
  redo: []
}>()

const graphUi = useGraphUiStore()
const vf = useVueFlow('spec-agent-graph-canvas')

type FlowCanvasNode = Node<SpecAgentGraphNodeData, Record<string, never>, string>
// shallowRef:Vue Flow 已在内部保存节点/边(并且每次变更批次都会整体换数组),
// 而递归的 Edge/GraphEdge 类型会让 Vue 的深度 UnwrapRef 实例化在把 .value
// 传给辅助函数时爆栈(TS2589)。shallowRef 既保留精确类型,也符合 Vue Flow
// 对节点/边集合的官方建议。
const flowNodes = shallowRef<FlowCanvasNode[]>([])
const flowEdges = shallowRef<Edge[]>([])
const shiftSelecting = ref(false)
const rootEl = ref<HTMLElement | null>(null)

onMounted(() => {
  startContainerResizeObserver(rootEl.value)
})

onUnmounted(() => {
  stopContainerResizeObserver()
})

/*
 * 一次性的显式 Fit 重校验意图(临时的,组件级)。
 *
 * 声明在下方的投影 watcher 之前:watcher 的 immediate 首次运行可能调用
 * maybeRevalidatePendingFit,而后者会读取这个绑定。
 */
interface PendingFitIntent {
  runId: string
  activeNodeIdAtFit: string | null
}

let pendingFitIntent: PendingFitIntent | null = null

function clearPendingFitIntent(): void {
  pendingFitIntent = null
}

const projection = computed(() => {
  if (!props.view) {
    return { nodes: [], edges: [] }
  }
  return projectGraph({
    view: props.view,
    activeNodeId: props.activeNodeId,
    uiState: {
      focusRouteId: graphUi.focusRouteId,
      lifecycleFilters: graphUi.lifecycleFilters,
      routeDisplayStates: graphUi.routeDisplayStates,
      isolatedRouteId: graphUi.isolatedRouteId,
      expandedNodeIds: graphUi.expandedNodeIds,
      showRelationLayer: graphUi.showRelationLayer,
      selectedNodeIds: graphUi.selectedNodeIds,
    },
    savedPositions: graphUi.nodePositions,
    runtimeByNode: props.runtimeByNode,
    recoveryByNode: props.recoveryByNode,
    pendings: props.pendings,
  })
})

// canonical 刷新绝不移动已有节点,也绝不把 Vue Flow 的运行时状态回写给
// Vue Flow。
//
// `next.nodes` 是纯投影描述符(id / type / position / data / dragHandle /
// class)。Vue Flow 用 `Object.assign(internalNode, descriptor)` 把每个描述
// 符合并进自己的内部节点,因此我们没有发过去的每个 key 都会在内部节点上
// 保留:dimensions、handleBounds、computedPosition、initialized、selected、
// dragging。
//
// 把上一次的本地快照合并回去(`{ ...existing, ...node }`)正是 BUG-02 的
// 根因。那个快照是 `flowNodes` 当前持有的内容,可能早于 Vue Flow 的测量
// ——此时它带着 `dimensions: { width: 0, height: 0 }`,写回去会把真实测量
// 清零。NodeWrapper 于是以 `visibility: hidden` 渲染节点,而元素盒子之后
// 不再变化,ResizeObserver 也就永远不会重新测量,节点从此永久隐藏
// (刷新、Fit View、缩放都无法恢复)。
watch(
  projection,
  (next) => {
    flowNodes.value = next.nodes.map((node) => ({ ...node }))
    flowEdges.value = next.edges.map((edge) => ({ ...edge }))
    adoptProjectedPositions()
    // pending → 真实节点的替换经 canonical 投影落地;等真实几何可测量后
    // 再重新校验显式 Fit。
    maybeRevalidatePendingFit()
  },
  { immediate: true },
)

// 投影替换后,真实的节点测量经由 Vue Flow 到达(不设固定超时):尺寸变化
// 时重新检查已武装的意图。测量是 Vue Flow 自己的状态(见 measuredSizeById),
// 所以这里监听 Vue Flow 的 store;update-node-internals 处理器覆盖显式的
// Vue Flow 测量事件(jsdom 安全:没有武装意图时两者都是空操作)。
watch(
  () => vf.nodes.value.map((node) => {
    const measured = (node as { dimensions?: Dimensions }).dimensions
    return `${String(node.id)}:${measured?.width ?? 0}x${measured?.height ?? 0}`
  }),
  () => {
    maybeRevalidatePendingFit()
  },
)

/*
 * Vue Flow 在渲染/测量后通过 update-node-internals 报告实测节点几何。
 * 测量值保留在 Vue Flow 自己的 store 里——刻意不复制进本地 flow 节点,
 * 因为那些描述符会原样交还给 Vue Flow,一旦带测量就会覆盖它们复制自的
 * 测量值(BUG-02)。这个处理器只重新检查已武装的意图。
 */
function onUpdateNodeInternals(_ids?: string[]): void {
  maybeRevalidatePendingFit()
}

/*
 * 把投影刚刚首次分配的位置(首次布局或增量新节点放置)保存到浏览器本地,
 * 这样后续的 canonical 刷新会把这些节点识别为"已存在",绝不再重新布局。
 * 位置绝不发送到后端。
 */
function adoptProjectedPositions(): void {
  const toSave: Record<string, GraphPosition> = {}
  for (const node of flowNodes.value) {
    if (!graphUi.nodePositions[node.id]) {
      toSave[node.id] = { x: node.position.x, y: node.position.y }
    }
  }
  const entries = Object.entries(toSave)
  if (entries.length > 0) {
    graphUi.setNodePositions(toSave)
  }
}

// 新出现的当前节点（回答/起草后）如果完全不在视口内，平滑带进视口；已有
// 节点坐标绝不被移动（只改 viewport 变换）。已经（部分）可见的节点绝不
// 触发 reveal，避免刷新后视口跳变。
let activeNodeFitTimer: number | null = null

function clearActiveNodeFitTimer(): void {
  if (activeNodeFitTimer !== null) {
    window.clearTimeout(activeNodeFitTimer)
    activeNodeFitTimer = null
  }
}

/** 运行时命令标识的是 canonical 节点;Vue Flow 渲染的是视觉实例。通过
 * Active 路线解析命令目标,这样重新回答/替换分支也能被定位而不必猜测路线。 */
function resolveFlowNodeId(nodeId: string): string {
  if (flowNodes.value.some((node) => node.id === nodeId)) {
    return nodeId
  }
  const activeRouteId = props.view?.activeRouteId ?? null
  return projection.value.nodes.find((node) =>
    node.data?.canonicalNodeId === nodeId
      && (activeRouteId === null || node.data?.routeIds.includes(activeRouteId)),
  )?.id ?? nodeId
}

/**
 * 该节点当前在画布视口内的可见面积比例（0..1）。几何未知时保守返回 1，
 * 避免在加载中途自作主张移动视口。
 */
function nodeVisibilityFraction(nodeId: string): number {
  const vp = vf.viewport.value
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!canvasWidth || !canvasHeight) {
    return 1
  }
  const target = collectViewportNodes(new Set([resolveFlowNodeId(nodeId)]))[0]
  if (!target) {
    return 1
  }
  const { width, height } = getNodeSize(target)
  const left = target.position.x * vp.zoom + vp.x
  const top = target.position.y * vp.zoom + vp.y
  const right = left + width * vp.zoom
  const bottom = top + height * vp.zoom
  // 浮动窗口覆盖在画布上且从不预留布局空间,因此 reveal 以画布实际视口
  // 为准,而不是旧版侧栏几何。
  const visibleWidth = Math.max(0, Math.min(right, canvasWidth) - Math.max(left, 0))
  const visibleHeight = Math.max(0, Math.min(bottom, canvasHeight) - Math.max(top, 0))
  const total = width * height * vp.zoom * vp.zoom
  return total > 0 ? (visibleWidth * visibleHeight) / total : 1
}

/** 新当前节点明显被裁剪（可见比例低于阈值）时，平滑带进视口。 */
const REVEAL_VISIBILITY_THRESHOLD = 0.6
const REVEAL_DELAY_MS = 500

watch(
  () => props.activeNodeId,
  (nodeId, wasNodeId) => {
    if (!nodeId || nodeId === wasNodeId || !props.view) {
      return
    }
    // 新节点刚加入时尺寸尚未测量：延迟到 Vue Flow 完成测量后再判断。
    if (activeNodeFitTimer !== null) {
      window.clearTimeout(activeNodeFitTimer)
    }
    activeNodeFitTimer = window.setTimeout(() => {
      activeNodeFitTimer = null
      if (nodeVisibilityFraction(nodeId) < REVEAL_VISIBILITY_THRESHOLD) {
        manualFitNode(nodeId)
      }
    }, REVEAL_DELAY_MS)
  },
)

/**
 * 当画布实际尺寸变化（例如 Spec Dock 展开会压缩 Graph 区域、或在已展开时
 * 内容增高使 Dock 达到 45%）时，等 Vue Flow 重新测量完成再检查当前节点是否
 * 被 Dock 裁切。只调整 viewport，绝不移动节点坐标；只有当前节点真的越出
 * 画布边界时才动作。jsdom 测试环境没有 ResizeObserver，守卫后为空操作。
 */
let containerResizeObserver: ResizeObserver | null = null
let containerResizeFrame: number | null = null

function onContainerResize(): void {
  if (containerResizeFrame !== null) {
    window.cancelAnimationFrame(containerResizeFrame)
  }
  containerResizeFrame = window.requestAnimationFrame(() => {
    containerResizeFrame = null
    void nextTick(() => ensureActiveNodeInView())
  })
}

function startContainerResizeObserver(el: HTMLElement | null): void {
  if (typeof ResizeObserver === 'undefined' || !el) {
    return
  }
  containerResizeObserver = new ResizeObserver(() => onContainerResize())
  containerResizeObserver.observe(el)
}

function stopContainerResizeObserver(): void {
  containerResizeObserver?.disconnect()
  containerResizeObserver = null
  if (containerResizeFrame !== null) {
    window.cancelAnimationFrame(containerResizeFrame)
    containerResizeFrame = null
  }
}

/**
 * Measured node sizes, keyed by node id, taken from Vue Flow's own store.
 *
 * Vue Flow owns node measurement; the local flow nodes only carry the
 * projection (see the projection watcher). Geometry must therefore always be
 * read here — never from the descriptors we hand back to Vue Flow, which by
 * contract carry no measurement at all.
 */
function measuredSizeById(): Map<string, Dimensions> {
  const measured = new Map<string, Dimensions>()
  for (const node of vf.nodes.value) {
    const dimensions = (node as { dimensions?: Dimensions }).dimensions
    if (dimensions && dimensions.width > 0 && dimensions.height > 0) {
      measured.set(String(node.id), dimensions)
    }
  }
  return measured
}

/** 把当前 flow 节点转换为视口计算输入(已知时用实测尺寸)。 */
function collectViewportNodes(ids?: Set<string> | null): ViewportNode[] {
  const measured = measuredSizeById()
  const result: ViewportNode[] = []
  for (const node of flowNodes.value) {
    if (ids && !ids.has(node.id)) {
      continue
    }
    const size = measured.get(node.id)
    result.push({
      id: node.id,
      position: { x: node.position.x, y: node.position.y },
      width: size?.width,
      height: size?.height,
    })
  }
  return result
}

/*
 * 真实的视口落定(settled)契约。
 *
 * - data-viewport-settled 表示最近一次请求的视口过渡已经"完成"(而非开始)。
 * - applyViewport 立即递增单调的请求 id,但只在 setViewport 的 Promise
 *   resolve 后才暴露/推进 settled 版本号。Promise 挂起期间 settled 不变。
 * - 过期的 Promise(重叠请求)绝不能把更新的请求标记为 settled——通过
 *   单调请求 id 检查。
 * - 用户驱动的 viewport-change-end(平移/缩放)是正交的,独立推进 settled;
 *   程序化 setViewport 过渡绝不通过该事件对同一请求 id 重复计数。
 */
const viewportSettledRevision = ref(0)
const viewportRequestRevision = ref(0)
const latestSettledRequestId = ref(0)

function writeSettledRevision(revision: number): void {
  viewportSettledRevision.value = revision
}

function markSettledForRequest(requestId: number): void {
  if (requestId < viewportRequestRevision.value) return
  if (requestId < latestSettledRequestId.value) {
    return
  }
  if (requestId === latestSettledRequestId.value && viewportSettledRevision.value > 0) {
    return
  }
  latestSettledRequestId.value = requestId
  writeSettledRevision(requestId)
  emit('viewport-settled')
}

function applyViewport(transform: ViewportTransform | null, duration: number): void {
  if (!transform) {
    return
  }
  const requestId = ++viewportRequestRevision.value
  void Promise.resolve(vf.setViewport(transform, { duration })).then(
    () => markSettledForRequest(requestId),
    () => markSettledForRequest(requestId),
  )
}

function onViewportChangeEnd(): void {
  // 程序化 setViewport 走的 d3 transition 没有 sourceEvent,永远到不了这个
  // 处理器(对照过打包的 Vue Flow 源码:`if (!event.sourceEvent) return null`)。
  // 能进到这里就说明是真实的用户平移/缩放手势——一个新的视口意图,应使
  // 已武装的 pending-fit 重校验失效。
  clearPendingFitIntent()
  const requestId = ++viewportRequestRevision.value
  latestSettledRequestId.value = requestId
  writeSettledRevision(requestId)
  emit('viewport-settled')
}

function onInit(): void {
  void nextTick(() => {
    // 初始 fit 必须是瞬时的：任何动画残留都会在后续交互测量期间悄悄改变
    // viewport，造成已有节点“看似移动”。
    clearActiveNodeFitTimer()
    const canvasWidth = vf.dimensions.value.width
    const canvasHeight = vf.dimensions.value.height
    if (!canvasWidth || !canvasHeight) {
      return
    }
    // 初始 fit 与显式适应视图使用相同的可用矩形：左侧 toolbar 与顶部
    // 标题是覆盖在画布上的 overlay，初始视口同样不应把节点放在它们下方。
    applyViewport(
      computeFitViewport(collectViewportNodes(), canvasWidth, canvasHeight, { padding: 22, region: props.safeRegion ?? fitRegion(canvasWidth, canvasHeight) }),
      0,
    )
  })
}

/**
 * 自研适应视图：直接基于投影坐标计算 viewport 变换（setViewport），只改
 * 视口、绝不移动节点坐标。绝不依赖 Vue Flow 的节点测量。
 */

/**
 * 固定 overlay gutter：左侧 toolbar 与顶部 workspace 标题是覆盖在画布上
 * 的 overlay（不占布局宽度）。fit 类操作把图放进剩余可用矩形，避免 fit
 * 后节点落在 toolbar 下方无法 hover/拖拽。纯视口计算，不改变任何节点
 * 坐标、Focus/Active 语义。
 */
function fitRegion(canvasWidth: number, canvasHeight: number): import('@/features/workspace/graph/graphViewport').FitViewportRegion {
  const left = 104
  const top = 44
  return {
    x: left,
    y: top,
    width: Math.max(canvasWidth - left, 1),
    height: Math.max(canvasHeight - top, 1),
  }
}

function performFitView(): void {
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!canvasWidth || !canvasHeight) {
    return
  }
  applyViewport(
    computeFitViewport(collectViewportNodes(), canvasWidth, canvasHeight, { padding: 22, region: props.safeRegion ?? fitRegion(canvasWidth, canvasHeight) }),
    300,
  )
}

/** 当前画布上实测过(非兜底、非 pending)的 flow 节点 id 集合。 */
function measuredRealNodeIds(): Set<string> {
  const ids = new Set<string>()
  const measured = measuredSizeById()
  for (const node of flowNodes.value) {
    if (node.id.startsWith('pending:')) {
      continue
    }
    if (measured.has(node.id)) {
      ids.add(node.id)
    }
  }
  return ids
}

/*
 * 当武装意图的 pending run 已被实测过的真实节点取代时,消费该意图。
 * 在投影刷新与节点测量更新之后调用;所有门槛不满足时是空操作。
 */
function maybeRevalidatePendingFit(): void {
  const intent = pendingFitIntent
  if (!intent) {
    return
  }
  const pendings = props.pendings ?? []
  const sameRun = pendings.find((entry) => entry.runId === intent.runId) ?? null
  // 被 fit 的 pending run 仍在进行:保持意图武装。
  if (sameRun && sameRun.status !== 'FAILED') {
    return
  }
  // 任何在途 pending(另一个 run,或同一 run 的再次轮询)都说明替换尚未
  // 完成:只有当它永远不可能再匹配时才过期。已 FAILED 且无替换的 run
  // 直接过期意图,不做重校验。
  if (pendings.some((entry) => entry.status !== 'FAILED')) {
    if (!sameRun) {
      pendingFitIntent = null
    }
    return
  }
  if (pendings.length > 0) {
    pendingFitIntent = null
    return
  }
  const stillPending = flowNodes.value.some((node) => node.id === `pending:${intent.runId}`)
  if (stillPending) {
    return
  }
  const measured = measuredRealNodeIds()
  if (measured.size === 0) {
    return
  }
  pendingFitIntent = null
  clearActiveNodeFitTimer()
  performFitView()
}

function manualFitView(): void {
  clearActiveNodeFitTimer()
  performFitView()
  const pending = (props.pendings ?? []).find((entry) => entry.status !== 'FAILED') ?? null
  if (pending) {
    pendingFitIntent = { runId: pending.runId, activeNodeIdAtFit: props.activeNodeId }
  } else {
    clearPendingFitIntent()
  }
}

/** 把单个节点平滑带进视口（只改 viewport，不动节点坐标）。 */
function manualFitNode(nodeId: string): void {
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!canvasWidth || !canvasHeight) {
    return
  }
  const target = collectViewportNodes(new Set([resolveFlowNodeId(nodeId)]))[0] ?? null
  applyViewport(
    computeFitNodeViewport(target, canvasWidth, canvasHeight, { padding: 22, region: props.safeRegion ?? fitRegion(canvasWidth, canvasHeight) }),
    400,
  )
}

/*
 * 确保当前可作答节点完整留在 Graph 区域内。兄弟区域(Spec Dock)改变画布
 * 尺寸时调用,保证活跃节点绝不被它裁切。只调整视口 transform;绝不移动
 * 节点坐标或已保存位置。
 */
function ensureActiveNodeInView(): void {
  const activeNodeId = props.activeNodeId
  if (!activeNodeId) {
    return
  }
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!canvasWidth || !canvasHeight) {
    return
  }
  const target = collectViewportNodes(new Set([resolveFlowNodeId(activeNodeId)]))[0]
  if (!target) {
    return
  }
  const { width, height } = getNodeSize(target)
  const vp = vf.viewport.value
  // 1px 容差:只是贴到边缘的节点(例如新布局中位于左上角的根节点)不算
  // 被裁切;而真正被推出画布边界的节点(例如被展开的 Spec Dock 挡住)才
  // 重新适配。
  const tolerance = 1
  const left = target.position.x * vp.zoom + vp.x
  const top = target.position.y * vp.zoom + vp.y
  const right = left + width * vp.zoom
  const bottom = top + height * vp.zoom
  const fullyVisible = left >= -tolerance && top >= -tolerance
    && right <= canvasWidth + tolerance && bottom <= canvasHeight + tolerance
  if (!fullyVisible) {
    clearActiveNodeFitTimer()
    manualFitNode(activeNodeId)
  }
}

function onNodesChange(changes: NodeChange[]): void {
  // Vue Flow 对每个批次里的每个受影响节点各发一次 select change;这里
  // 镜像累计结果,不依赖内部 store 状态。
  const selected = new Set(graphUi.selectedNodeIds)
  let touched = false
  for (const change of changes) {
    if (change.type !== 'select') {
      continue
    }
    touched = true
    if (change.selected) {
      selected.add(change.id)
    } else {
      selected.delete(change.id)
    }
  }
  if (touched) {
    graphUi.setSelection([...selected])
  }
}

/*
 * 仅浏览器端的拖拽中重路由:节点拖动期间,每条边的端点都从"当前 flow 位置"
 * 重新选择 source/target handle,让边立刻跟随自然的象限方向(A 在 B 右侧
 * 就切到 A 左锚点,横向关系切到纵向……)。
 *
 * 契约:绝不写 localStorage(位置只在拖拽结束时持久化),绝不触发 canonical
 * 刷新,绝不修改运行时状态——只重新推导既有 flow 边的 handle id。
 */
function onNodeDrag(event: NodeDragEvent): void {
  // Vue Flow 已经移动了 flow 节点(v-model);应用事件快照是为了测试与
  // 多节点拖拽的健壮性。
  const moved = new Map<string, GraphPosition>()
  for (const dragged of event.nodes) {
    moved.set(dragged.id, { x: dragged.position.x, y: dragged.position.y })
  }
  for (const node of flowNodes.value) {
    const position = moved.get(node.id)
    if (position) {
      node.position = { x: position.x, y: position.y }
    }
  }
  flowEdges.value = rerouteEdgeHandles(flowNodes.value, flowEdges.value)
}

/*
 * 从当前 flow 节点位置(已知时用实测尺寸)重新推导每条边的 source/target
 * handle。独立成函数,让边循环不必把深度泛型的 NodeDragEvent 类型拉进
 * 作用域。
 */
function rerouteEdgeHandles(nodes: FlowCanvasNode[], edges: Edge[]): Edge[] {
  const byId = new Map(nodes.map((n): [string, FlowCanvasNode] => [n.id, n]))
  const measured = measuredSizeById()
  const nextEdges: Edge[] = []
  for (const edge of edges) {
    const source = byId.get(edge.source)
    const target = byId.get(edge.target)
    if (!source || !target) {
      nextEdges.push(edge)
      continue
    }
    const handles = selectEdgeHandles(
      toNodeGeometry(source, measured),
      toNodeGeometry(target, measured),
    )
    if (edge.sourceHandle === handles.sourceHandle && edge.targetHandle === handles.targetHandle) {
      nextEdges.push(edge)
      continue
    }
    nextEdges.push({ ...edge, sourceHandle: handles.sourceHandle, targetHandle: handles.targetHandle })
  }
  return nextEdges
}

/** flow 节点 → 路由几何:已知时用实测尺寸,否则安全兜底。 */
function toNodeGeometry(node: FlowCanvasNode, measured?: Map<string, Dimensions>): NodeGeometry {
  const size = (measured ?? measuredSizeById()).get(node.id)
  return {
    position: { x: node.position.x, y: node.position.y },
    width: size?.width,
    height: size?.height,
  }
}

/*
 * 只在拖拽真正停止时持久化位置。拖拽中途的移动留在 Vue Flow 内;
 * localStorage 绝不随每次指针移动写入。
 */
function onNodeDragStop(event: NodeDragEvent): void {
  const positions: Record<string, GraphPosition> = {}
  for (const node of event.nodes) {
    positions[node.id] = { x: node.position.x, y: node.position.y }
  }
  graphUi.setNodePositions(positions)
}

function hasSelectionModifier(event: MouseEvent | TouchEvent | undefined): boolean {
  if (!event || !('ctrlKey' in event)) {
    return false
  }
  return event.ctrlKey || event.metaKey || event.shiftKey
}

/** 普通点击选中节点并解析浏览器 Focus;带修饰键的点击只做多选,
 * 绝不移动阅读上下文。 */
function onNodeClick(event: NodeMouseEvent): void {
  if (hasSelectionModifier(event.event)) {
    return
  }
  graphUi.selectNode(event.node.id)
  const visibleRouteIds =
    (event.node.data as { visibleRouteIds?: string[] } | undefined)?.visibleRouteIds ?? []
  const intent = resolveRouteFocusIntent(
    visibleRouteIds,
    graphUi.focusRouteId,
  )
  if (intent !== null) {
    graphUi.setFocusRoute(intent)
  }
}

/** 边点击与节点使用同一套确定性路线解析。 */
function onEdgeClick(event: EdgeMouseEvent): void {
  const allRouteIds = [...new Set(
    ((event.edge.data as { routeIds?: string[] } | undefined)?.routeIds ?? []),
  )]
  if (allRouteIds.length > 1) {
    // 共享物理边是有歧义的路线段。选中它只是浏览器行为;Focus 绝不猜测
    // 成员路线。
    graphUi.selectEdge(event.edge.id, allRouteIds)
    return
  }
  const visibleRouteIds =
    (event.edge.data as { visibleRouteIds?: string[] } | undefined)?.visibleRouteIds ?? []
  const intent = resolveRouteFocusIntent(
    visibleRouteIds,
    graphUi.focusRouteId,
  )
  if (intent !== null) {
    graphUi.setFocusRoute(intent)
  }
}

function onPaneClick(event?: MouseEvent): void {
  if (hasSelectionModifier(event)) {
    return
  }
  graphUi.clearSelection()
  graphUi.clearFocusRoute()
}

/*
 * 手动节点间连线(从源 handle 拖到目标 handle)。区分两种意图:
 *
 *  - 浮动 ↔ 已接入:把独立节点接入谱系。画布只上报这一对;工作台解析出
 *    显式路线(绝不猜测 Active/第一个/最新)并执行运行时接入命令。一次
 *    放下的连接绝不会单独持久化。
 *  - 已接入 ↔ 已接入:关系提案。在提案选择器里用户确认类型与方向之前,
 *    不持久化任何关系。
 *
 * 取消/Esc/点击空白会清掉待定提案,零后端调用。Pending 投影卡与自连接
 * 被忽略。
 */
function onConnect(connection: Connection): void {
  const endpointOf = (
    flowNodeId: string | null | undefined,
  ): { canonicalNodeId: string; floating: boolean } | null => {
    if (!flowNodeId) return null
    const node = flowNodes.value.find((candidate) => candidate.id === flowNodeId)
    const data = node?.data as { canonicalNodeId?: string; routeIds?: string[] } | undefined
    const canonical = data?.canonicalNodeId
    if (!canonical || canonical.startsWith('pending:')) return null
    return { canonicalNodeId: canonical, floating: (data?.routeIds?.length ?? 0) === 0 }
  }
  const source = endpointOf(connection.source)
  const target = endpointOf(connection.target)
  if (!source || !target || source.canonicalNodeId === target.canonicalNodeId) {
    return
  }
  if (source.floating !== target.floating) {
    // 恰好一侧属于某条路线:这次拖线的含义是"接入路线"。
    emit('connect-floating', {
      floatingNodeId: source.floating ? source.canonicalNodeId : target.canonicalNodeId,
      anchorNodeId: source.floating ? target.canonicalNodeId : source.canonicalNodeId,
    })
    return
  }
  emit('relation-proposal', {
    sourceNodeId: source.canonicalNodeId,
    targetNodeId: target.canonicalNodeId,
  })
}

function emitContextualAi(nodeId: string, visualNodeKey?: string): void {
  emit('contextual-ai', {
    canonicalNodeId: nodeId,
    visualNodeKey: visualNodeKey ?? nodeId,
  })
}

/** 把单个节点带进视野,不改变 Focus 或 Active。 */
async function locateNode(nodeId: string): Promise<void> {
  clearActiveNodeFitTimer()
  clearPendingFitIntent()
  manualFitNode(nodeId)
}

/*
 * 只对某条路线的可见节点做适配/居中。绝不设置 Focus,也绝不改变 Active。
 */
async function locateRoute(routeId: string): Promise<void> {
  clearActiveNodeFitTimer()
  clearPendingFitIntent()
  const route = props.view?.routes.find((r) => r.id === routeId)
  if (!route) {
    return
  }
  const visibleIds = new Set(flowNodes.value.map((node) => node.id))
  const ids = projection.value.nodes
    .filter((node) => node.data?.routeIds.includes(routeId) && visibleIds.has(node.id))
    .map((node) => node.id)
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (ids.length === 0 || !canvasWidth || !canvasHeight) {
    return
  }
  applyViewport(
    computeFitViewport(
      collectViewportNodes(new Set(ids)),
      canvasWidth,
      canvasHeight,
      { padding: 22, region: props.safeRegion ?? fitRegion(canvasWidth, canvasHeight) },
    ),
    400,
  )
}

defineExpose({ locateNode, locateRoute, ensureActiveNodeInView })

async function zoomIn(): Promise<void> {
  clearActiveNodeFitTimer()
  clearPendingFitIntent()
  await vf.zoomIn()
}

async function zoomOut(): Promise<void> {
  clearActiveNodeFitTimer()
  clearPendingFitIntent()
  await vf.zoomOut()
}

/** 适应视图：与初始 fit 相同的确定性实现。 */
async function fitView(): Promise<void> {
  clearActiveNodeFitTimer()
  manualFitView()
}

/*
 * 显式用户命令:从头重算所有可见节点位置并持久化结果。需要确认弹窗,
 * 因为它会覆盖用户手工调整的布局。运行时历史绝不改变。后续 fit 基于新
 * 位置计算,绝不基于 Vue Flow 测量。
 */
/** 确认弹窗状态:用站内 UiConfirmDialog 取代原生 window.confirm。 */
const autoLayoutConfirmOpen = ref(false)

function requestAutoLayout(): void {
  autoLayoutConfirmOpen.value = true
}

async function autoLayout(): Promise<void> {
  autoLayoutConfirmOpen.value = false
  clearActiveNodeFitTimer()
  clearPendingFitIntent()
  if (!props.view) {
    return
  }
  const visibleIds = new Set<string>()
  for (const node of flowNodes.value) {
    visibleIds.add(node.id)
  }
  const projected = projection.value
  const parentByVisualKey = new Map(
    projected.edges
      .filter((edge) => edge.data?.kind === 'lineage')
      .map((edge) => [edge.target, edge.source]),
  )
  const nodes = projected.nodes
    .filter((node) => visibleIds.has(node.id))
    .map((node) => ({ id: node.id, parentNodeId: parentByVisualKey.get(node.id) ?? null }))
  // 传入已测量高度：卡片高度由内容决定（长笔记可能是短问题的数倍），
  // 固定行距会让长卡压住下一张卡。测量值缺失时退回旧的字距行为。
  const measured = measuredSizeById()
  const positions = computeInitialLayout(nodes, {}, {
    heightOf: (nodeId) => measured.get(nodeId)?.height,
  })
  graphUi.setNodePositions(positions)

  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!canvasWidth || !canvasHeight) {
    return
  }
  const viewportNodes: ViewportNode[] = collectViewportNodes().map((node) => ({
    ...node,
    position: positions[node.id] ?? node.position,
  }))
  applyViewport(
    computeFitViewport(viewportNodes, canvasWidth, canvasHeight, { padding: 22, region: props.safeRegion ?? fitRegion(canvasWidth, canvasHeight) }),
    300,
  )
}

function showAll(): void {
  graphUi.showAll()
}

/*
 * "只看这条路线"是模态的视图状态:其它所有路线都移出画布,所以画布必须
 * 说明当前隔离的是哪条路线,并提供一键退出。渲染在这里(而不是工具栏),
 * 因为工具栏只是一条窄轨。
 */
const isolatedRouteLabel = computed<string | null>(() => {
  const routeId = graphUi.isolatedRouteId
  if (!routeId) return null
  const route = props.view?.routes.find((candidate) => candidate.id === routeId)
  return route?.label?.trim() || '所选路线'
})

const isEmptyProject = computed(() =>
  props.view !== null
  && props.view.nodes.length === 0
  && (props.pendings?.length ?? 0) === 0,
)
</script>

<template>
  <div ref="rootEl" class="graph-canvas" data-test="graph-canvas" :data-viewport-settled="viewportSettledRevision > 0 ? String(viewportSettledRevision) : undefined">
    <GraphToolbar
      @zoom-in="zoomIn"
      @zoom-out="zoomOut"
      @fit-view="fitView"
      @auto-layout="requestAutoLayout"
      @show-all="showAll"
      @add-idea="emit('add-idea')"
      @add-resource="emit('add-resource')"
      @undo="emit('undo')"
      @redo="emit('redo')"
    />

    <div v-if="isolatedRouteLabel" class="graph-canvas__isolate-chip" data-test="isolate-chip">
      <span class="graph-canvas__isolate-text" data-test="isolate-chip-label">只看：{{ isolatedRouteLabel }}</span>
      <button
        class="graph-canvas__isolate-exit"
        data-test="isolate-chip-exit"
        title="退出只看，恢复全部路线"
        @click="showAll"
      >显示全部</button>
    </div>

    <div v-if="view && !isEmptyProject" class="graph-canvas__flow">
      <!-- delete-key-code=null：Runtime 里没有"删除单个节点"的命令，节点只能
           通过路线归档/项目删除消失。Vue Flow 默认把 Backspace 绑定为
           removeSelectedNodes，那只删浏览器内存里的 flow 节点（v-model 同步），
           看起来"节点被删了"，但任何 canonical 刷新都会把它重建回来
           （刷新后节点回来）。这里显式关闭，避免制造一个假的删除能力。 -->
      <VueFlow
        v-model:nodes="flowNodes"
        v-model:edges="flowEdges"
        :nodes-connectable="true"
        :connection-mode="ConnectionMode.Loose"
        :edges-updatable="false"
        :multi-selection-key-code="['Meta', 'Control']"
        :pan-on-drag="true"
        :min-zoom="0.15"
        :max-zoom="2.5"
        :delete-key-code="null"
        data-test="graph-flow"
        @init="onInit"
         @nodes-change="onNodesChange"
         @node-click="onNodeClick"
         @edge-click="onEdgeClick"
         @node-drag="onNodeDrag"
        @node-drag-stop="onNodeDragStop"
        @pane-click="onPaneClick"
        @connect="onConnect"
        @update-node-internals="onUpdateNodeInternals"
         @selection-start="shiftSelecting = true"
         @selection-end="shiftSelecting = false"
         @viewport-change-end="onViewportChangeEnd"
      >
        <template #edge-adaptive="edgeProps">
          <AdaptiveGraphEdge v-bind="edgeProps" />
        </template>
        <template #node-question="nodeProps: NodeProps<SpecAgentGraphNodeData>">
          <GraphQuestionNode
            :data="nodeProps.data"
            :selected="nodeProps.selected"
            :submitting="submitting"
            :pending="pending"
            @submit-answer="(payload) => emit('submit-answer', payload)"
            @focus-route="(routeId) => graphUi.setFocusRoute(routeId)"
            @fork="(id) => emit('fork', id)"
            @reanswer="(id) => emit('reanswer', id)"
            @regenerate="(id) => emit('regenerate', id)"
            @disconnect="(id) => emit('disconnect', id)"
            @contextual-ai="(id) => emitContextualAi(id, nodeProps.data.visualNodeKey)"
            @retry-failure="(failure) => emit('retry-failure', failure)"
            @activate-route="(routeId) => emit('activate-route', routeId)"
            @draft-next="(routeId) => emit('draft-next', routeId)"
          />
        </template>
        <template #node-knowledge="nodeProps: NodeProps<SpecAgentGraphNodeData>">
          <GraphKnowledgeNode
            :data="nodeProps.data"
            :selected="nodeProps.selected"
            @contextual-ai="(id) => emitContextualAi(id, nodeProps.data.visualNodeKey)"
          />
        </template>
      </VueFlow>
    </div>

    <GraphStartPlaceholder
      v-else-if="isEmptyProject"
      :drafting="drafting"
      @draft="emit('draft')"
      @add-idea="emit('add-idea')"
    />

    <p v-if="!view" class="muted graph-canvas__loading">正在加载工作区…</p>

    <UiConfirmDialog
      :open="autoLayoutConfirmOpen"
      title="重新自动布局"
      description="重新自动布局将覆盖当前项目手工调整过的节点位置。Runtime 历史不会改变"
      confirm-label="重新布局"
      test-id="auto-layout-confirm"
      @confirm="autoLayout"
      @cancel="autoLayoutConfirmOpen = false"
    />
  </div>
</template>
