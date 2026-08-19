<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import ConfirmRouteActionDialog from '@/components/ConfirmRouteActionDialog.vue'
import ForkRouteDialog from '@/components/ForkRouteDialog.vue'
import ReanswerRouteDialog from '@/components/ReanswerRouteDialog.vue'
import RegenerateNodeDialog from '@/components/RegenerateNodeDialog.vue'
import GraphCanvas from '@/components/graph/GraphCanvas.vue'
import FloatingWindow from '@/components/workspace/FloatingWindow.vue'
import RouteNavigator from '@/components/workspace/RouteNavigator.vue'
import WorkspaceInspector from '@/components/workspace/WorkspaceInspector.vue'
import { projectGraph, type SpecAgentGraphNodeData } from '@/graph/graphProjection'
import { FLOATING_WINDOW_RANGES } from '@/graph/graphLayoutStorage'
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
const canvasRef = ref<InstanceType<typeof GraphCanvas> | null>(null)

const forkDialogOpen = ref(false)
const reanswerDialogOpen = ref(false)
const regenerateDialogOpen = ref(false)
const confirmAction = ref<'archive' | 'delete' | null>(null)
const confirmRouteId = ref<string | null>(null)
const forkNodeId = ref<string | null>(null)
const regenerateNodeId = ref<string | null>(null)
const reanswerNodeId = ref<string | null>(null)

onMounted(() => {
  graphUi.initProject(props.projectId)
  void store.loadWorkspace(props.projectId)
})

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
  await store.loadWorkspace(props.projectId)
}

async function handleDraft(): Promise<void> {
  await store.draftQuestion()
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

async function handleForkSubmit(label: string | null): Promise<void> {
  if (!forkNodeId.value) {
    return
  }
  const sourceRouteId = forkSourceRoute.value?.id
  if (!sourceRouteId) return
  const ok = await store.forkNode(forkNodeId.value, sourceRouteId, label)
  forkDialogOpen.value = false
  if (ok) {
    graphUi.setFocusRoute(store.activeState?.activeRoute?.id ?? null)
    await nextTick()
    await canvasRef.value?.locateNode(store.activeState?.activeNode?.id ?? '')
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
    graphUi.setFocusRoute(store.activeState?.activeRoute?.id ?? null)
    await nextTick()
    await canvasRef.value?.locateNode(store.activeState?.activeNode?.id ?? '')
    regenerateNodeId.value = null
  }
}

function handleLocateRoute(routeId: string): void {
  void canvasRef.value?.locateRoute(routeId)
}

function openWindow(name: 'routes' | 'inspector'): void {
  graphUi.setFloatingWindow(name, { open: true })
  graphUi.bringWindowToFront(name)
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
        :message="store.error.message"
        :code="store.error.code"
        retry-label="重试"
        :retrying="store.loading"
        @retry="retry"
      />
    </div>

    <p v-if="store.loading" class="muted workspace-shell__loading">正在加载工作区…</p>

    <div v-else class="workspace-shell__body">
      <GraphCanvas
        ref="canvasRef"
        class="workspace-shell__canvas"
        :view="store.graphView"
        :active-node-id="store.activeState?.activeNode?.id ?? null"
        :submitting="store.submitting"
        :drafting="store.drafting"
        :pending="store.routeCommandPending"
        @draft="handleDraft"
        @submit-answer="handleAnswer"
        @fork="handleFork"
        @reanswer="handleReanswer"
        @regenerate="handleRegenerate"
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
      <p v-if="store.refreshing" class="muted workspace-shell__refreshing" data-test="refreshing">
        正在刷新工作区…
      </p>
      <p v-if="store.feedback" class="feedback-line" data-test="feedback">{{ store.feedback }}</p>
      <button v-if="store.forkDraftRetryRouteId" class="btn btn-primary workspace-shell__retry-draft" data-test="retry-fork-draft" @click="store.retryForkDraft()">重试起草</button>
    </div>

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
