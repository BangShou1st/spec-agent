<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
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
import AdaptiveGraphEdge from '@/components/graph/AdaptiveGraphEdge.vue'
import GraphKnowledgeNode from '@/components/graph/GraphKnowledgeNode.vue'
import GraphQuestionNode from '@/components/graph/GraphQuestionNode.vue'
import GraphStartPlaceholder from '@/components/graph/GraphStartPlaceholder.vue'
import GraphToolbar from '@/components/graph/GraphToolbar.vue'
import { projectGraph, type SpecAgentGraphNodeData } from '@/graph/graphProjection'
import { computeInitialLayout } from '@/graph/graphLayout'
import {
  selectEdgeHandles,
  type NodeGeometry,
} from '@/graph/graphEdgeRouting'
import {
  computeFitNodeViewport,
  computeFitViewport,
  getNodeSize,
  type ViewportNode,
  type ViewportTransform,
} from '@/graph/graphViewport'
import type { GraphPosition } from '@/graph/graphTypes'
import type {
  ContextualAiTarget,
  GraphPendingProjection,
  GraphRuntimeStatus,
} from '@/graph/graphProjection'
import { useGraphUiStore } from '@/stores/graphUiStore'
import type { GraphWorkspaceView, SubmitAnswerRequest } from '@/api/types'
import { resolveRouteFocusIntent } from '@/graph/graphInteraction'

/**
 * Graph-first workspace canvas (Phase 7.3).
 *
 * Vue Flow owns viewport/rendering/selection/dragging; this component owns
 * the browser-only wiring: projection of canonical graph data, selection
 * mirroring, group-move persistence on drag stop, route/node location,
 * the empty-project placeholder and the explicit auto-layout command.
 * It never mutates Runtime state.
 *
 * All fit-style operations are deterministic: they compute the viewport
 * transform from the current projected node coordinates plus known/safe
 * fallback dimensions and apply it with setViewport. Vue Flow's
 * measurement-dependent `fitView` is deliberately not used so a refresh
 * can never fit against stale/absent node measurements.
 */
const props = defineProps<{
  view: GraphWorkspaceView | null
  activeNodeId: string | null
  submitting: boolean
  drafting: boolean
  pending: boolean
  runtimeNodeId?: string | null
  runtimeStatus?: GraphRuntimeStatus | null
  runtimePhase?: string | null
  pendingProjection?: GraphPendingProjection | null
  safeRegion?: import('@/graph/graphViewport').FitViewportRegion | null
}>()

const emit = defineEmits<{
  draft: []
  'submit-answer': [payload: SubmitAnswerRequest]
  fork: [nodeId: string]
  reanswer: [nodeId: string]
  regenerate: [nodeId: string]
  'add-idea': []
  'add-resource': []
  'contextual-ai': [target: ContextualAiTarget]
  'retry-pending': []
  'viewport-settled': []
  'activate-route': [routeId: string]
  // A canvas drag (source handle → target handle) only raises a PENDING
  // relation proposal; nothing is persisted until the user confirms a type
  // and direction. This replaced the old "drag => immediate RELATED_TO".
  'relation-proposal': [payload: { sourceNodeId: string; targetNodeId: string }]
  // Vue Flow forwards the raw 'connect' event through <VueFlow @connect>;
  // declaring it here silences the Vue "neither declared in the emits option
  // nor as an onConnect prop" warning and documents the bridge.
  connect: [connection: Connection]
  undo: []
  redo: []
  routes: []
  inspector: []
  'reset-windows': []
}>()

const graphUi = useGraphUiStore()
const vf = useVueFlow('spec-agent-graph-canvas')

type FlowCanvasNode = Node<SpecAgentGraphNodeData, Record<string, never>, string>
// shallowRef: Vue Flow already stores nodes/edges internally (and swaps the
// array on every change batch), and the recursive Edge/GraphEdge types make
// Vue's deep UnwrapRef instantiation explode (TS2589) when a .value is
// passed to a helper. shallowRef keeps the exact types and matches Vue
// Flow's own guidance for node/edge collections.
const flowNodes = shallowRef<FlowCanvasNode[]>([])
const flowEdges = shallowRef<Edge[]>([])
const shiftSelecting = ref(false)

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
      expandedNodeIds: graphUi.expandedNodeIds,
      showRelationLayer: graphUi.showRelationLayer,
      selectedNodeIds: graphUi.selectedNodeIds,
    },
    savedPositions: graphUi.nodePositions,
    runtime: {
      nodeId: props.runtimeNodeId ?? null,
      status: props.runtimeStatus ?? null,
      phase: props.runtimePhase ?? null,
    },
    pending: props.pendingProjection ?? null,
  })
})

// Canonical refresh must never move existing nodes: keep the runtime
// properties Vue Flow owns (selected, dragging, dimensions) on reused ids
// and only refresh position/data for nodes that changed.
watch(
  projection,
  (next) => {
    const current = new Map(flowNodes.value.map((n): [string, FlowCanvasNode] => [n.id, n]))
    flowNodes.value = next.nodes.map((node) => {
      const existing = current.get(node.id)
      return existing ? { ...existing, ...node } : node
    })
    const ids = new Set(next.nodes.map((n) => n.id))
    flowNodes.value = flowNodes.value.filter((n) => ids.has(n.id))
    flowEdges.value = next.edges.map((edge) => ({ ...edge }))
    adoptProjectedPositions()
  },
  { immediate: true },
)

/**
 * Stores browser-locally any position the projection just assigned for the
 * first time (first-ever layout or incremental new-node placement), so a
 * later canonical refresh recognizes those nodes as "existing" and never
 * re-lays them out. Positions are never sent to the backend.
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

/** Runtime commands identify canonical nodes; Vue Flow renders visual
 * instances. Resolve a command target through the Active route so a
 * Re-answer/Replace branch can still be located without guessing a route. */
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
  // Floating windows overlay the canvas and never reserve layout space. Reveal
  // therefore uses the actual canvas viewport rather than legacy sidebar
  // geometry.
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

/** Converts current flow nodes into viewport inputs (measured size when known). */
function collectViewportNodes(ids?: Set<string> | null): ViewportNode[] {
  const result: ViewportNode[] = []
  for (const node of flowNodes.value) {
    if (ids && !ids.has(node.id)) {
      continue
    }
    const measured = (node as FlowCanvasNode & { dimensions?: Dimensions }).dimensions
    result.push({
      id: node.id,
      position: { x: node.position.x, y: node.position.y },
      width: measured?.width,
      height: measured?.height,
    })
  }
  return result
}

/**
 * True viewport settlement contract.
 *
 * - data-viewport-settled means the latest requested viewport transition
 *   has COMPLETED (not started).
 * - applyViewport increments a monotonic request id immediately but only
 *   exposes/advances the settled revision after setViewport's Promise
 *   resolves. While the Promise is pending, settled remains unchanged.
 * - A stale Promise (overlapping request) never marks a newer request as
 *   settled — checked via monotonic request ids.
 * - User-driven viewport-change-end (pan/zoom) is orthogonal and advances
 *   settled independently; programmatic setViewport transitions never
 *   double-count via that event for the same request id.
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
    // 初始 fit 使用全画布语义：safeRegion 只属于显式适应视图。初始视口
    // 决定浮窗自动布局的输入几何，若在此消费 safeRegion 会形成双向耦合。
    applyViewport(
      computeFitViewport(collectViewportNodes(), canvasWidth, canvasHeight, { padding: 48 }),
      0,
    )
  })
}

/**
 * 自研适应视图：直接基于投影坐标计算 viewport 变换（setViewport），只改
 * 视口、绝不移动节点坐标。绝不依赖 Vue Flow 的节点测量。
 */
function manualFitView(): void {
  clearActiveNodeFitTimer()
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!canvasWidth || !canvasHeight) {
    return
  }
  applyViewport(
    computeFitViewport(collectViewportNodes(), canvasWidth, canvasHeight, { padding: 48, region: props.safeRegion ?? undefined }),
    300,
  )
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
    computeFitNodeViewport(target, canvasWidth, canvasHeight, { padding: 48 }),
    400,
  )
}

function onNodesChange(changes: NodeChange[]): void {
  // Vue Flow emits one select change per affected node per batch; mirror the
  // accumulated result without depending on the internal store state.
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

/**
 * Browser-only drag-time rerouting: while a node is being dragged, every
 * edge endpoint re-selects its source/target handle from the CURRENT flow
 * positions, so the edge follows the natural quadrant immediately (A right
 * of B switches to A left, horizontal switches to vertical, ...).
 *
 * Contract: this never writes localStorage (positions persist only on drag
 * stop), never triggers a canonical graph refresh and never mutates Runtime
 * state — it only re-derives the handle ids of the existing flow edges.
 */
function onNodeDrag(event: NodeDragEvent): void {
  // Vue Flow already moved the flow nodes (v-model); the event snapshot is
  // applied for robustness in tests and multi-node drags.
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

/**
 * Re-derives every edge's source/target handles from the current flow-node
 * positions (measured size when known). Standalone so the edge loop never
 * needs the deeply generic NodeDragEvent type in scope.
 */
function rerouteEdgeHandles(nodes: FlowCanvasNode[], edges: Edge[]): Edge[] {
  const byId = new Map(nodes.map((n): [string, FlowCanvasNode] => [n.id, n]))
  const nextEdges: Edge[] = []
  for (const edge of edges) {
    const source = byId.get(edge.source)
    const target = byId.get(edge.target)
    if (!source || !target) {
      nextEdges.push(edge)
      continue
    }
    const handles = selectEdgeHandles(toNodeGeometry(source), toNodeGeometry(target))
    if (edge.sourceHandle === handles.sourceHandle && edge.targetHandle === handles.targetHandle) {
      nextEdges.push(edge)
      continue
    }
    nextEdges.push({ ...edge, sourceHandle: handles.sourceHandle, targetHandle: handles.targetHandle })
  }
  return nextEdges
}

/** Flow node -> routing geometry: measured size when known, safe fallback otherwise. */
function toNodeGeometry(node: FlowCanvasNode): NodeGeometry {
  const measured = (node as FlowCanvasNode & { dimensions?: Dimensions }).dimensions
  return {
    position: { x: node.position.x, y: node.position.y },
    width: measured?.width,
    height: measured?.height,
  }
}

/**
 * Persists positions only when a drag actually stops. Mid-drag moves stay
 * inside Vue Flow; localStorage is never written per pointer-move.
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

/** A normal node click selects and resolves browser Focus; modified clicks
 * remain pure multi-selection and never move the reading context. */
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

/** Edge clicks use the same deterministic route resolution as nodes. */
function onEdgeClick(event: EdgeMouseEvent): void {
  const allRouteIds = [...new Set(
    ((event.edge.data as { routeIds?: string[] } | undefined)?.routeIds ?? []),
  )]
  if (allRouteIds.length > 1) {
    // A shared physical edge is an ambiguous route segment. Selecting it is
    // browser-only; Focus must not guess a member route.
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

/**
 * Manual node-to-node connection (drag from a source handle to a target
 * handle). The drop ONLY creates a pending relation proposal: the backend is
 * not called, no GraphOperation is appended, and no relation is persisted
 * until the user confirms a specific type and direction in the proposal
 * chooser. Lineage is never rewritten by hand. Pending projection cards and
 * self-connections are ignored.
 */
function onConnect(connection: Connection): void {
  const canonicalOf = (flowNodeId: string | null | undefined): string | null => {
    if (!flowNodeId) return null
    const node = flowNodes.value.find((candidate) => candidate.id === flowNodeId)
    const canonical = (node?.data as { canonicalNodeId?: string } | undefined)?.canonicalNodeId
    if (!canonical || canonical.startsWith('pending:')) return null
    return canonical
  }
  const sourceNodeId = canonicalOf(connection.source)
  const targetNodeId = canonicalOf(connection.target)
  if (!sourceNodeId || !targetNodeId || sourceNodeId === targetNodeId) {
    return
  }
  emit('relation-proposal', { sourceNodeId, targetNodeId })
}

function emitContextualAi(nodeId: string, visualNodeKey?: string): void {
  emit('contextual-ai', {
    canonicalNodeId: nodeId,
    visualNodeKey: visualNodeKey ?? nodeId,
  })
}

/** Brings one node into view without changing Focus or Active. */
async function locateNode(nodeId: string): Promise<void> {
  clearActiveNodeFitTimer()
  manualFitNode(nodeId)
}

/**
 * Fits/centers only the visible nodes of one route. Never sets Focus and
 * never changes Active.
 */
async function locateRoute(routeId: string): Promise<void> {
  clearActiveNodeFitTimer()
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
      { padding: 48 },
    ),
    400,
  )
}

defineExpose({ locateNode, locateRoute })

async function zoomIn(): Promise<void> {
  clearActiveNodeFitTimer()
  await vf.zoomIn()
}

async function zoomOut(): Promise<void> {
  clearActiveNodeFitTimer()
  await vf.zoomOut()
}

/** 适应视图：与初始 fit 相同的确定性实现。 */
async function fitView(): Promise<void> {
  clearActiveNodeFitTimer()
  manualFitView()
}

/**
 * Explicit user command: recompute every visible node position from scratch
 * and persist the result. Requires confirmation because it overwrites the
 * user's manual layout. Runtime history never changes. The follow-up fit is
 * computed from the fresh positions, never from Vue Flow measurements.
 */
async function autoLayout(): Promise<void> {
  clearActiveNodeFitTimer()
  if (!props.view) {
    return
  }
  const confirmed = window.confirm(
    '重新自动布局将覆盖当前项目手工调整过的节点位置。Runtime 历史不会改变。',
  )
  if (!confirmed) {
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
  const positions = computeInitialLayout(nodes, {})
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
    computeFitViewport(viewportNodes, canvasWidth, canvasHeight, { padding: 48 }),
    300,
  )
}

function showAll(): void {
  graphUi.showAll()
}

const isEmptyProject = computed(() =>
  props.view !== null && props.view.nodes.length === 0 && !props.pendingProjection,
)
</script>

<template>
  <div class="graph-canvas" data-test="graph-canvas" :data-viewport-settled="viewportSettledRevision > 0 ? String(viewportSettledRevision) : undefined">
    <GraphToolbar
      @zoom-in="zoomIn"
      @zoom-out="zoomOut"
      @fit-view="fitView"
      @auto-layout="autoLayout"
      @show-all="showAll"
      @add-idea="emit('add-idea')"
      @add-resource="emit('add-resource')"
      @undo="emit('undo')"
      @redo="emit('redo')"
      @routes="emit('routes')"
      @inspector="emit('inspector')"
      @reset-windows="emit('reset-windows')"
    />

    <div v-if="view && !isEmptyProject" class="graph-canvas__flow">
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
        data-test="graph-flow"
        @init="onInit"
         @nodes-change="onNodesChange"
         @node-click="onNodeClick"
         @edge-click="onEdgeClick"
         @node-drag="onNodeDrag"
        @node-drag-stop="onNodeDragStop"
        @pane-click="onPaneClick"
        @connect="onConnect"
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
            @contextual-ai="(id) => emitContextualAi(id, nodeProps.data.visualNodeKey)"
            @retry-pending="emit('retry-pending')"
            @activate-route="(routeId) => emit('activate-route', routeId)"
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
  </div>
</template>
