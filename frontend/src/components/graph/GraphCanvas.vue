<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { VueFlow, useVueFlow, type Dimensions, type Node, type Edge, type NodeChange, type NodeDragEvent, type NodeProps } from '@vue-flow/core'
import GraphQuestionNode from '@/components/graph/GraphQuestionNode.vue'
import GraphStartPlaceholder from '@/components/graph/GraphStartPlaceholder.vue'
import GraphToolbar from '@/components/graph/GraphToolbar.vue'
import { projectGraph, type SpecAgentGraphNodeData } from '@/graph/graphProjection'
import { computeInitialLayout } from '@/graph/graphLayout'
import type { GraphPosition } from '@/graph/graphTypes'
import { useGraphUiStore } from '@/stores/graphUiStore'
import type { GraphWorkspaceView, SubmitAnswerRequest } from '@/api/types'

/**
 * Graph-first workspace canvas (Phase 7.3).
 *
 * Vue Flow owns viewport/rendering/selection/dragging; this component owns
 * the browser-only wiring: projection of canonical graph data, selection
 * mirroring, group-move persistence on drag stop, route/node location,
 * the empty-project placeholder and the explicit auto-layout command.
 * It never mutates Runtime state.
 */
const props = defineProps<{
  view: GraphWorkspaceView | null
  activeNodeId: string | null
  submitting: boolean
  drafting: boolean
  pending: boolean
}>()

const emit = defineEmits<{
  draft: []
  'submit-answer': [payload: SubmitAnswerRequest]
  fork: [nodeId: string]
  regenerate: [nodeId: string]
}>()

const graphUi = useGraphUiStore()
const vf = useVueFlow('spec-agent-graph-canvas')

type FlowCanvasNode = Node<SpecAgentGraphNodeData, Record<string, never>, string>
const flowNodes = ref<FlowCanvasNode[]>([])
const flowEdges = ref<Edge[]>([])
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
    },
    savedPositions: graphUi.nodePositions,
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
  },
  { immediate: true },
)

// 新出现的当前节点（回答/起草后）如果不在视口内，平滑带进视口；已有节点
// 坐标绝不被移动（只改 viewport 变换）。
let activeNodeFitTimer: number | null = null
watch(
  () => props.activeNodeId,
  (nodeId, wasNodeId) => {
    if (!nodeId || nodeId === wasNodeId || !props.view) {
      return
    }
    // 新节点刚加入时尺寸尚未测量：延迟到 Vue Flow 完成测量后再带进视口。
    if (activeNodeFitTimer !== null) {
      window.clearTimeout(activeNodeFitTimer)
    }
    activeNodeFitTimer = window.setTimeout(() => {
      activeNodeFitTimer = null
      manualFitNode(nodeId)
    }, 250)
  },
)


/** 初始套用一次适应视图（只改 viewport 变换，不动节点坐标）。 */
function onInit(): void {
  void nextTick(() => {
    manualFitView()
  })
}

/**
 * 自研适应视图：直接基于投影坐标计算 viewport 变换（setViewport），只改
 * 视口、绝不移动节点坐标。
 */
function manualFitView(): void {
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!canvasWidth || !canvasHeight) {
    return
  }
  let minX = Number.POSITIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const node of flowNodes.value) {
    const measured = (node as FlowCanvasNode & { dimensions?: Dimensions }).dimensions
    const width = measured?.width ?? 320
    const height = measured?.height ?? 220
    minX = Math.min(minX, node.position.x)
    minY = Math.min(minY, node.position.y)
    maxX = Math.max(maxX, node.position.x + width)
    maxY = Math.max(maxY, node.position.y + height)
  }
  if (!Number.isFinite(minX)) {
    return
  }
  const padding = 48
  const boundsWidth = Math.max(maxX - minX, 1)
  const boundsHeight = Math.max(maxY - minY, 1)
  const zoom = Math.min(
    (canvasWidth - padding) / boundsWidth,
    (canvasHeight - padding) / boundsHeight,
    1,
  )
  const centerX = (minX + maxX) / 2
  const centerY = (minY + maxY) / 2
  void vf.setViewport(
    { x: canvasWidth / 2 - centerX * zoom, y: canvasHeight / 2 - centerY * zoom, zoom },
    { duration: 300 },
  )
}

/** 把单个节点平滑带进视口（只改 viewport，不动节点坐标）。 */
function manualFitNode(nodeId: string): void {
  let target: FlowCanvasNode | undefined
  const all = flowNodes.value as unknown as { id: string }[]
  for (const node of all) {
    if (node.id === nodeId) {
      target = node as FlowCanvasNode
      break
    }
  }
  const canvasWidth = vf.dimensions.value.width
  const canvasHeight = vf.dimensions.value.height
  if (!target || !canvasWidth || !canvasHeight) {
    return
  }
  const measured = (target as FlowCanvasNode & { dimensions?: Dimensions }).dimensions
  const width = measured?.width ?? 320
  const height = measured?.height ?? 220
  const centerX = target.position.x + width / 2
  const centerY = target.position.y + height / 2
  const zoom = Math.min(canvasWidth / (width + 96), canvasHeight / (height + 96), 1)
  void vf.setViewport(
    { x: canvasWidth / 2 - centerX * zoom, y: canvasHeight / 2 - centerY * zoom, zoom },
    { duration: 400 },
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

function onPaneClick(): void {
  graphUi.clearSelection()
}

/** Brings one node into view without changing Focus or Active. */
async function locateNode(nodeId: string): Promise<void> {
  await vf.fitView({ nodes: [nodeId], padding: 0.35, duration: 400 })
}

/**
 * Fits/centers only the visible nodes of one route. Never sets Focus and
 * never changes Active.
 */
async function locateRoute(routeId: string): Promise<void> {
  const route = props.view?.routes.find((r) => r.id === routeId)
  if (!route) {
    return
  }
  const visibleIds = new Set<string>()
  for (const node of flowNodes.value) {
    visibleIds.add(node.id)
  }
  const ids = route.lineageNodeIds.filter((id) => visibleIds.has(id))
  if (ids.length === 0) {
    return
  }
  await vf.fitView({ nodes: ids, padding: 0.35, duration: 400 })
}

defineExpose({ locateNode, locateRoute })

async function zoomIn(): Promise<void> {
  await vf.zoomIn()
}

async function zoomOut(): Promise<void> {
  await vf.zoomOut()
}

async function fitView(): Promise<void> {
  await vf.fitView({ padding: 0.2, duration: 300 })
}

/**
 * Explicit user command: recompute every visible node position from scratch
 * and persist the result. Requires confirmation because it overwrites the
 * user's manual layout. Runtime history never changes.
 */
async function autoLayout(): Promise<void> {
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
  const nodes = props.view.nodes.filter((n) => visibleIds.has(n.id))
  const positions = computeInitialLayout(nodes, {})
  graphUi.setNodePositions(positions)
  await vf.fitView({ padding: 0.2, duration: 300 })
}

function showAll(): void {
  graphUi.showAll()
}

const isEmptyProject = computed(() =>
  props.view !== null && props.view.nodes.length === 0,
)
</script>

<template>
  <div class="graph-canvas" data-test="graph-canvas">
    <GraphToolbar
      @zoom-in="zoomIn"
      @zoom-out="zoomOut"
      @fit-view="fitView"
      @auto-layout="autoLayout"
      @show-all="showAll"
    />

    <div v-if="view && !isEmptyProject" class="graph-canvas__flow">
      <VueFlow
        v-model:nodes="flowNodes"
        v-model:edges="flowEdges"
        :nodes-connectable="false"
        :edges-updatable="false"
        :multi-selection-key-code="['Meta', 'Control']"
        :selection-key-code="'Shift'"
        :pan-on-drag="true"
        :min-zoom="0.15"
        :max-zoom="2.5"
        data-test="graph-flow"
        @init="onInit"
        @nodes-change="onNodesChange"
        @node-drag-stop="onNodeDragStop"
        @pane-click="onPaneClick"
        @selection-start="shiftSelecting = true"
        @selection-end="shiftSelecting = false"
      >
        <template #node-question="nodeProps: NodeProps<SpecAgentGraphNodeData>">
          <GraphQuestionNode
            :data="nodeProps.data"
            :submitting="submitting"
            :pending="pending"
            @submit-answer="(payload) => emit('submit-answer', payload)"
            @toggle-expanded="graphUi.toggleExpanded"
            @fork="(id) => emit('fork', id)"
            @regenerate="(id) => emit('regenerate', id)"
          />
        </template>
      </VueFlow>
    </div>

    <GraphStartPlaceholder
      v-else-if="isEmptyProject"
      :drafting="drafting"
      @draft="emit('draft')"
    />

    <p v-if="!view" class="muted graph-canvas__loading">正在加载工作区…</p>
  </div>
</template>