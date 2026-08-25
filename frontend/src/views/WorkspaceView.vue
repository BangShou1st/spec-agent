<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { routerKey } from 'vue-router'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import ConfirmRouteActionDialog from '@/components/ConfirmRouteActionDialog.vue'
import ForkRouteDialog from '@/components/ForkRouteDialog.vue'
import ResourceDialog from '@/components/ResourceDialog.vue'
import ReanswerRouteDialog from '@/components/ReanswerRouteDialog.vue'
import RegenerateNodeDialog from '@/components/RegenerateNodeDialog.vue'
import GraphCanvas from '@/components/graph/GraphCanvas.vue'
import FloatingWindow from '@/components/workspace/FloatingWindow.vue'
import RouteNavigator from '@/components/workspace/RouteNavigator.vue'
import WorkspaceInspector from '@/components/workspace/WorkspaceInspector.vue'
import {
  projectGraph,
  type ContextualAiTarget,
  type SpecAgentGraphNodeData,
} from '@/graph/graphProjection'
import { phaseToCopy } from '@/graph/phaseCopy'
import { FLOATING_WINDOW_RANGES } from '@/graph/graphLayoutStorage'
import { computeAutoFloatingWindowLayout, type FloatingRect } from '@/graph/floatingWindowLayout'
import { productErrorMessage, requiresModelSettings } from '@/api/errorCopy'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { RegenerateNodeRequest, SubmitAnswerRequest } from '@/api/types'

/**
 * Graph-first workspace shell (Phase 7.3).
 *
 * Layout: GraphCanvas with floating route navigation and inspector windows.
 * Runtime commands go through workspaceStore (Active-route only); Focus/
 * Dim/Hide/positions/sidebars live in graphUiStore (browser-only).
 * Focus drives shared-node reading; Active remains runtime-only.
 */
const props = defineProps<{ projectId: string }>()

const store = useWorkspaceStore()
const graphUi = useGraphUiStore()
const router = inject(routerKey, null)
const canvasRef = ref<InstanceType<typeof GraphCanvas> | null>(null)
const workspaceBodyRef = ref<HTMLElement | null>(null)
let floatingLayoutFrame: number | null = null
let floatingLayoutSettledTimer: number | null = null
let workspaceResizeObserver: ResizeObserver | null = null

const forkDialogOpen = ref(false)
const resourceDialogOpen = ref(false)
const reanswerDialogOpen = ref(false)
const regenerateDialogOpen = ref(false)
const confirmAction = ref<'archive' | 'delete' | null>(null)
const confirmRouteId = ref<string | null>(null)
const forkNodeId = ref<string | null>(null)
const regenerateNodeId = ref<string | null>(null)
const reanswerNodeId = ref<string | null>(null)

onMounted(() => {
  graphUi.initProject(props.projectId)
  window.addEventListener('resize', scheduleFloatingLayout)
  if (workspaceBodyRef.value && 'ResizeObserver' in window) {
    workspaceResizeObserver = new ResizeObserver(scheduleFloatingLayout)
    workspaceResizeObserver.observe(workspaceBodyRef.value)
  }
  scheduleFloatingLayout()
  void store.loadWorkspace(props.projectId).then(() => {
    void store.refreshUndoRedoAvailability()
    scheduleFloatingLayout()
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleFloatingLayout)
  workspaceResizeObserver?.disconnect()
  workspaceResizeObserver = null
  if (floatingLayoutFrame !== null) {
    window.cancelAnimationFrame(floatingLayoutFrame)
    floatingLayoutFrame = null
  }
  if (floatingLayoutSettledTimer !== null) {
    window.clearTimeout(floatingLayoutSettledTimer)
    floatingLayoutSettledTimer = null
  }
})

// 每次 canonical 刷新后，浏览器视图状态与后端 graph 对齐。
watch(
  () => store.graphView,
  (view) => {
    graphUi.reconcile(view)
    scheduleFloatingLayout()
  },
)

watch(
  () => [graphUi.nodePositions, graphUi.routeDisplayStates, graphUi.floatingWindows],
  () => scheduleFloatingLayout(),
  { deep: true, flush: 'post' },
)

function graphObstacles(): FloatingRect[] {
  const body = workspaceBodyRef.value
  if (!body) return []
  const bodyBox = body.getBoundingClientRect()
  return Array.from(body.querySelectorAll<HTMLElement>(
    '[data-layout-role="graph-node"], [data-layout-role="start-placeholder"], '
      + '[data-layout-role="toolbar"]',
  ))
    .map((element) => {
      const box = element.getBoundingClientRect()
      return {
        x: box.left - bodyBox.left,
        y: box.top - bodyBox.top,
        width: box.width,
        height: box.height,
      }
    })
    .filter((box) => box.width > 0 && box.height > 0)
}

function floatingRect(name: 'routes' | 'inspector'): FloatingRect {
  const state = graphUi.floatingWindows[name]
  return { x: state.x, y: state.y, width: state.width, height: state.height }
}

function changedGeometry(
  current: { x: number; y: number; width: number; height: number },
  next: { x: number; y: number; width: number; height: number },
): boolean {
  return Math.abs(current.x - next.x) > 0.5
    || Math.abs(current.y - next.y) > 0.5
    || Math.abs(current.width - next.width) > 0.5
    || Math.abs(current.height - next.height) > 0.5
}

function reflowFloatingWindows(): void {
  const body = workspaceBodyRef.value
  if (!body) return
  const bodyBox = body.getBoundingClientRect()
  if (bodyBox.width <= 0 || bodyBox.height <= 0) return

  const obstacles = graphObstacles()
  const routes = graphUi.floatingWindows.routes
  const inspector = graphUi.floatingWindows.inspector
  const routeObstacles = [
    ...obstacles,
    ...(inspector.open && inspector.positionMode === 'manual' ? [floatingRect('inspector')] : []),
  ]
  if (routes.open && routes.positionMode === 'auto') {
    const nextRoutes = computeAutoFloatingWindowLayout({
      viewportWidth: bodyBox.width,
      viewportHeight: bodyBox.height,
      state: routes,
      range: FLOATING_WINDOW_RANGES.routes,
      obstacles: routeObstacles,
    })
    if (changedGeometry(routes, nextRoutes)) {
      graphUi.setFloatingWindow('routes', nextRoutes)
    }
  }

  const refreshedRoutes = graphUi.floatingWindows.routes
  // Both windows respect the same graph obstacle set: every interactive graph
  // node, the start placeholder's content, and the toolbar. A knowledge draft
  // or historical node is never acceptable cover for an auto window.
  const inspectorObstacles = [
    ...obstacles,
    ...(refreshedRoutes.open ? [floatingRect('routes')] : []),
  ]
  if (inspector.open && inspector.positionMode === 'auto') {
    const nextInspector = computeAutoFloatingWindowLayout({
      viewportWidth: bodyBox.width,
      viewportHeight: bodyBox.height,
      state: inspector,
      range: FLOATING_WINDOW_RANGES.inspector,
      obstacles: inspectorObstacles,
      // Window-to-window overlap blocks the close/titlebar controls just as
      // much as overlap with the current graph interaction does.
      protectedObstacles: inspectorObstacles,
    })
    if (changedGeometry(inspector, nextInspector)) {
      graphUi.setFloatingWindow('inspector', nextInspector)
    }
  }
}

function scheduleFloatingLayout(): void {
  if (typeof window === 'undefined' || floatingLayoutFrame !== null) return
  floatingLayoutFrame = window.requestAnimationFrame(() => {
    floatingLayoutFrame = null
    reflowFloatingWindows()
  })
}

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
      expandedNodeIds: graphUi.expandedNodeIds,
    },
    savedPositions: graphUi.nodePositions,
  })
  return projection.nodes.find((node) => node.id === graphUi.primarySelectedNodeId)?.data ?? null
})

const selectedEdgeData = computed(() => {
  if (!graphUi.selectedEdgeId) return null
  return {
    id: graphUi.selectedEdgeId,
    kind: graphUi.selectedEdgeId.startsWith('replacement:') ? 'replacement' as const : 'lineage' as const,
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

/** Resolves operation source from the visual node plus explicit reading Focus.
 * A shared node with no Focus is intentionally ambiguous and returns null. */
function sourceRouteForNode(nodeId: string | null) {
  if (!nodeId || !store.graphView) return null
  const memberships = store.graphView.routes.filter((route) => route.lineageNodeIds.includes(nodeId))
  if (graphUi.focusRouteId) {
    return memberships.find((route) => route.id === graphUi.focusRouteId) ?? null
  }
  return memberships.length === 1 ? memberships[0] : null
}

const forkSourceRoute = computed(() => sourceRouteForNode(forkNodeId.value))
const reanswerSourceRoute = computed(() => sourceRouteForNode(reanswerNodeId.value))
const regenerateSourceRoute = computed(() => sourceRouteForNode(regenerateNodeId.value))
const workspaceErrorMessage = computed(() =>
  productErrorMessage(store.error?.code ?? 'UNKNOWN_ERROR'),
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

const runtimePhaseCopy = computed(() => {
  if (store.pendingRouteProjection) {
    return phaseToCopy(store.pendingRouteProjection.phase)
  }
  if (store.answerRunId || store.answerRunStatus) {
    return phaseToCopy(store.answerRunPhase)
  }
  return null
})

const forkFinalizedRouteIds = computed(() => {
  if (!forkNodeId.value || !store.graphView) return []
  return store.graphView.answers
    .filter((answer) => answer.nodeId === forkNodeId.value)
    .map((answer) => answer.routeId)
})

const reanswerFinalized = computed(() => {
  if (!reanswerNodeId.value || !reanswerSourceRoute.value || !store.graphView) return false
  return store.graphView.answers.some((answer) => answer.nodeId === reanswerNodeId.value
    && answer.routeId === reanswerSourceRoute.value!.id)
})

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

async function handleAttachResource(
  subtype: 'TEXT' | 'URL' | 'FILE',
  content: Record<string, unknown>,
): Promise<void> {
  const ok = await store.attachResource(subtype, content)
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

function handleRegenerate(nodeId: string): void {
  regenerateNodeId.value = nodeId
  regenerateDialogOpen.value = true
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
      expandedNodeIds: graphUi.expandedNodeIds,
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
  openWindow('inspector')
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

function openWindow(name: 'routes' | 'inspector'): void {
  graphUi.setFloatingWindow(name, { open: true })
  graphUi.bringWindowToFront(name)
  // fit/locate operations animate the Vue Flow viewport. Reflow once more
  // after that animation so an auto window never settles over the node that
  // became visible at the end of the transition.
  scheduleFloatingLayout()
  if (floatingLayoutSettledTimer !== null) window.clearTimeout(floatingLayoutSettledTimer)
  floatingLayoutSettledTimer = window.setTimeout(() => {
    floatingLayoutSettledTimer = null
    scheduleFloatingLayout()
  }, 420)
}

function openConfirm(kind: 'archive' | 'delete', routeId: string): void {
  confirmAction.value = kind
  confirmRouteId.value = routeId
}

async function confirmDestructive(): Promise<void> {
  if (!confirmAction.value || !confirmRouteId.value) {
    return
  }
  const ok = confirmAction.value === 'archive'
    ? await store.archiveRoute(confirmRouteId.value)
    : await store.deleteRoute(confirmRouteId.value)
  if (ok) {
    confirmAction.value = null
    confirmRouteId.value = null
  }
}
</script>

<template>
  <div class="workspace-shell" data-test="workspace-shell">
    <header class="workspace-shell__header" data-test="workspace-project-badge">
      <h1 class="workspace-shell__title">{{ store.project?.title ?? '工作区' }}</h1>
    </header>

    <div class="workspace-shell__status-layer">
      <ApiErrorBanner
        v-if="store.error"
        :message="workspaceErrorMessage"
        :code="store.error.code"
        :retry-label="workspaceRetryLabel"
        :retrying="workspaceRetrying"
        @retry="retry"
      />
      <div v-if="store.repairableAnswerId" class="workspace-answer-retry" data-test="answer-retry">
        <span>回答已保存，后续生成未完成。</span>
        <button class="btn btn-primary" type="button" :disabled="workspaceRetrying" @click="retry">
          {{ workspaceRetrying ? '正在请求…' : '重新请求' }}
        </button>
      </div>
      <div v-else-if="store.answerOutcomeUnknown" class="workspace-answer-retry" data-test="answer-outcome-unknown">
        <span>提交结果未知，请先恢复状态。</span>
        <button class="btn" type="button" :disabled="workspaceRetrying" @click="retry">
          刷新状态
        </button>
      </div>
      <div v-else-if="store.resubmitAnswerPayload" class="workspace-answer-retry" data-test="answer-resubmit">
        <span>回答尚未保存，可以再次提交。</span>
        <button class="btn btn-primary" type="button" :disabled="workspaceRetrying" @click="retry">
          再次提交
        </button>
      </div>
    </div>

    <p v-if="store.loading" class="muted workspace-shell__loading">正在加载工作区…</p>

    <div v-else ref="workspaceBodyRef" class="workspace-shell__body">
      <GraphCanvas
        ref="canvasRef"
        class="workspace-shell__canvas"
        :view="store.graphView"
        :active-node-id="store.activeState?.activeNode?.id ?? null"
        :submitting="store.submitting"
        :drafting="store.drafting"
        :pending="store.routeCommandPending"
        :runtime-node-id="store.pendingAnswerNodeId"
        :runtime-status="store.answerRunStatus"
        :runtime-phase="store.answerRunPhase"
        :pending-projection="store.pendingRouteProjection"
        @draft="handleDraft"
        @submit-answer="handleAnswer"
        @fork="handleFork"
        @reanswer="handleReanswer"
        @regenerate="handleRegenerate"
        @contextual-ai="handleContextualAi"
        @retry-pending="store.retryPendingAgentRun"
        @viewport-settled="scheduleFloatingLayout"
        @add-idea="store.createRootIdea"
        @add-resource="resourceDialogOpen = true"
        @undo="store.undoGraph"
        @redo="store.redoGraph"
        @routes="openWindow('routes')"
        @inspector="openWindow('inspector')"
        @reset-windows="graphUi.resetWindows"
      />

      <FloatingWindow
        name="routes"
        title="路线导航"
        :state="graphUi.floatingWindows.routes"
        :z-index="graphUi.windowZOrder.indexOf('routes') + 20"
        :min-width="FLOATING_WINDOW_RANGES.routes.minWidth"
        :max-width="FLOATING_WINDOW_RANGES.routes.maxWidth"
        :min-height="FLOATING_WINDOW_RANGES.routes.minHeight"
        :max-height="FLOATING_WINDOW_RANGES.routes.maxHeight"
        @update:state="graphUi.setFloatingWindow('routes', $event)"
        @focus="graphUi.bringWindowToFront('routes')"
        @close="graphUi.setFloatingWindow('routes', { open: false })"
        @reset="graphUi.resetWindows"
      >
        <RouteNavigator
          :routes="store.graphView?.routes ?? []"
          :active-route-id="store.activeRoute?.id ?? null"
          :command-pending="store.routeCommandPending"
          :pending-route-command="store.pendingRouteCommand"
          @locate-route="handleLocateRoute"
          @activate="store.activateRoute($event)"
          @restore="store.restoreRoute($event)"
          @archive="openConfirm('archive', $event)"
          @delete="openConfirm('delete', $event)"
        />
      </FloatingWindow>

      <FloatingWindow
        name="inspector"
        title="检查器"
        :state="graphUi.floatingWindows.inspector"
        :z-index="graphUi.windowZOrder.indexOf('inspector') + 20"
        :min-width="FLOATING_WINDOW_RANGES.inspector.minWidth"
        :max-width="FLOATING_WINDOW_RANGES.inspector.maxWidth"
        :min-height="FLOATING_WINDOW_RANGES.inspector.minHeight"
        :max-height="FLOATING_WINDOW_RANGES.inspector.maxHeight"
        @update:state="graphUi.setFloatingWindow('inspector', $event)"
        @focus="graphUi.bringWindowToFront('inspector')"
        @close="graphUi.setFloatingWindow('inspector', { open: false })"
        @reset="graphUi.resetWindows"
      >
        <WorkspaceInspector
          :node-data="selectedNodeData"
          :selected-edge="selectedEdgeData"
          @fork="handleFork"
          @reanswer="handleReanswer"
          @regenerate="handleRegenerate"
        />
      </FloatingWindow>
    </div>

    <div class="workspace-shell__toast-layer">
      <p v-if="runtimePhaseCopy" class="muted workspace-shell__runtime-phase" data-test="runtime-phase">
        {{ runtimePhaseCopy }}
      </p>
      <p v-if="store.refreshing" class="muted workspace-shell__refreshing" data-test="refreshing">
        正在刷新工作区…
      </p>
      <p v-if="store.feedback" class="feedback-line" data-test="feedback">{{ store.feedback }}</p>
      <button v-if="store.forkDraftRetryRouteId" class="btn btn-primary workspace-shell__retry-draft" data-test="retry-fork-draft" :disabled="workspaceRetrying" @click="retryForkDraft">重试起草</button>
    </div>

    <ResourceDialog
      :open="resourceDialogOpen"
      :pending="store.graphCommandPending"
      :route-empty="(store.activeRoute?.tipNodeId ?? null) === null"
      @close="resourceDialogOpen = false"
      @submit="handleAttachResource"
    />

    <ForkRouteDialog
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

    <ReanswerRouteDialog
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

  </div>
</template>
