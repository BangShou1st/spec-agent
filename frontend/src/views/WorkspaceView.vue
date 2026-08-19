<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import ConfirmRouteActionDialog from '@/components/ConfirmRouteActionDialog.vue'
import ForkRouteDialog from '@/components/ForkRouteDialog.vue'
import RegenerateNodeDialog from '@/components/RegenerateNodeDialog.vue'
import GraphCanvas from '@/components/graph/GraphCanvas.vue'
import ResizableSidebar from '@/components/workspace/ResizableSidebar.vue'
import RouteSidebar from '@/components/workspace/RouteSidebar.vue'
import WorkspaceInspector from '@/components/workspace/WorkspaceInspector.vue'
import { projectGraph, type SpecAgentGraphNodeData } from '@/graph/graphProjection'
import { LEFT_SIDEBAR_RANGE, RIGHT_SIDEBAR_RANGE } from '@/graph/graphLayoutStorage'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { RegenerateNodeRequest, SubmitAnswerRequest } from '@/api/types'

/**
 * Graph-first workspace shell (Phase 7.3).
 *
 * Layout: resizable route sidebar | GraphCanvas | resizable inspector.
 * Runtime commands go through workspaceStore (Active-route only); Focus/
 * Dim/Hide/positions/sidebars live in graphUiStore (browser-only).
 * `readingRouteId = focus ?? active` drives the inspector reads.
 */
const props = defineProps<{ projectId: string }>()

const store = useWorkspaceStore()
const graphUi = useGraphUiStore()
const canvasRef = ref<InstanceType<typeof GraphCanvas> | null>(null)

const forkDialogOpen = ref(false)
const regenerateDialogOpen = ref(false)
const confirmAction = ref<'archive' | 'delete' | null>(null)
const confirmRouteId = ref<string | null>(null)
const forkNodeId = ref<string | null>(null)
const regenerateNodeId = ref<string | null>(null)

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

function handleRegenerate(nodeId: string): void {
  regenerateNodeId.value = nodeId
  regenerateDialogOpen.value = true
}

async function handleForkSubmit(label: string | null): Promise<void> {
  if (!forkNodeId.value) {
    return
  }
  const ok = await store.forkNode(forkNodeId.value, label)
  forkDialogOpen.value = false
  if (ok) {
    forkNodeId.value = null
  }
}

/** Fork prerequisites are explicit, local dialog actions. Each command
 * refreshes canonical state while the dialog remains open; no command is
 * chained into an implicit Fork. */
async function handleForkRestore(routeId: string): Promise<void> {
  await store.restoreRoute(routeId)
}

async function handleForkActivate(routeId: string): Promise<void> {
  await store.activateRoute(routeId)
}

async function handleRegenerateSubmit(payload: RegenerateNodeRequest): Promise<void> {
  if (!regenerateNodeId.value) {
    return
  }
  const ok = await store.regenerateNode(regenerateNodeId.value, payload)
  regenerateDialogOpen.value = false
  if (ok) {
    regenerateNodeId.value = null
  }
}

function handleLocateRoute(routeId: string): void {
  void canvasRef.value?.locateRoute(routeId)
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
      <ResizableSidebar
        side="left"
        :open="graphUi.leftSidebarOpen"
        :width="graphUi.leftSidebarWidth"
        :min-width="LEFT_SIDEBAR_RANGE.min"
        :max-width="LEFT_SIDEBAR_RANGE.max"
        @update:open="graphUi.setLeftSidebar({ open: $event, width: graphUi.leftSidebarWidth })"
        @update:width="graphUi.setLeftSidebar({ open: graphUi.leftSidebarOpen, width: $event })"
      >
        <RouteSidebar
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
      </ResizableSidebar>

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
        @regenerate="handleRegenerate"
      />

      <ResizableSidebar
        side="right"
        :open="graphUi.rightSidebarOpen"
        :width="graphUi.rightSidebarWidth"
        :min-width="RIGHT_SIDEBAR_RANGE.min"
        :max-width="RIGHT_SIDEBAR_RANGE.max"
        @update:open="graphUi.setRightSidebar({ open: $event, width: graphUi.rightSidebarWidth })"
        @update:width="graphUi.setRightSidebar({ open: graphUi.rightSidebarOpen, width: $event })"
      >
        <WorkspaceInspector
          :node-data="selectedNodeData"
          @fork="handleFork"
          @regenerate="handleRegenerate"
        />
      </ResizableSidebar>
    </div>

    <div class="workspace-shell__toast-layer">
      <p v-if="store.refreshing" class="muted workspace-shell__refreshing" data-test="refreshing">
        正在刷新工作区…
      </p>
      <p v-if="store.feedback" class="feedback-line" data-test="feedback">{{ store.feedback }}</p>
    </div>

    <ForkRouteDialog
      :open="forkDialogOpen"
      :node="forkNodeData"
      :routes="store.graphView?.routes ?? []"
      :active-route-id="store.activeRoute?.id ?? null"
      :pending="store.routeCommandPending"
      @close="forkDialogOpen = false"
      @submit="handleForkSubmit"
      @restore-base-route="handleForkRestore"
      @activate-base-route="handleForkActivate"
    />

    <RegenerateNodeDialog
      :open="regenerateDialogOpen"
      :node="regenerateNodeData"
      :pending="store.pendingRouteCommand === 'regenerate'"
      @close="regenerateDialogOpen = false"
      @submit="handleRegenerateSubmit"
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
