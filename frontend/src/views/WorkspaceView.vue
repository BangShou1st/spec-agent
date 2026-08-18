<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import ClarificationPanel from '@/components/ClarificationPanel.vue'
import ConfirmRouteActionDialog from '@/components/ConfirmRouteActionDialog.vue'
import ForkRouteDialog from '@/components/ForkRouteDialog.vue'
import HistoricalNodePanel from '@/components/HistoricalNodePanel.vue'
import RegenerateNodeDialog from '@/components/RegenerateNodeDialog.vue'
import RouteWorkspacePanel from '@/components/RouteWorkspacePanel.vue'
import WorkspaceRightPanel from '@/components/WorkspaceRightPanel.vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { RegenerateNodeRequest, SubmitAnswerRequest } from '@/api/types'

/**
 * Workspace page. Left: route workspace (routes + historical node lineages +
 * lifecycle actions). Center: the active clarification workflow, or the
 * historical node inspector when a historical node is selected. Right:
 * requirement state / spec snapshots tabs. Route mutations go through the
 * store, which always refreshes canonical backend reads afterwards.
 */
const props = defineProps<{ projectId: string }>()

const store = useWorkspaceStore()

const forkDialogOpen = ref(false)
const regenerateDialogOpen = ref(false)
const confirmAction = ref<'archive' | 'delete' | null>(null)
const confirmRouteId = ref<string | null>(null)

onMounted(() => {
  void store.loadWorkspace(props.projectId)
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

function handleSelectRoute(routeId: string | null): void {
  store.selectRoute(routeId)
}

function handleSelectNode(nodeId: string): void {
  store.selectHistoricalNode(nodeId)
}

async function handleActivate(routeId: string): Promise<void> {
  await store.activateRoute(routeId)
}

async function handleRestore(routeId: string): Promise<void> {
  await store.restoreRoute(routeId)
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
  } else {
    // Keep the dialog open so the user sees the safe backend error banner.
    confirmAction.value = null
    confirmRouteId.value = null
  }
}

async function handleForkSubmit(label: string | null): Promise<void> {
  const nodeId = store.selectedNodeId
  if (!nodeId) {
    return
  }
  const ok = await store.forkNode(nodeId, label)
  forkDialogOpen.value = false
  if (!ok) {
    return
  }
}

async function handleRegenerateSubmit(payload: RegenerateNodeRequest): Promise<void> {
  const nodeId = store.selectedNodeId
  if (!nodeId) {
    return
  }
  const ok = await store.regenerateNode(nodeId, payload)
  regenerateDialogOpen.value = false
  if (!ok) {
    return
  }
}
</script>

<template>
  <div>
    <h1 style="margin-top: 0">{{ store.project?.title ?? 'Workspace' }}</h1>

    <ApiErrorBanner
      v-if="store.error"
      :message="store.error.message"
      :code="store.error.code"
      retry-label="Retry"
      :retrying="store.loading"
      @retry="retry"
    />

    <p v-if="store.loading" class="muted">Loading workspace…</p>

    <div v-else class="workspace-layout">
      <RouteWorkspacePanel
        :project-title="store.project?.title ?? null"
        :routes="store.routes"
        :selected-route-id="store.selectedRouteId"
        :selected-node-id="store.selectedNodeId"
        :command-pending="store.routeCommandPending"
        :pending-route-command="store.pendingRouteCommand"
        @select-route="handleSelectRoute"
        @select-node="handleSelectNode"
        @activate="handleActivate"
        @restore="handleRestore"
        @archive="openConfirm('archive', $event)"
        @delete="openConfirm('delete', $event)"
      />

      <HistoricalNodePanel
        v-if="store.selectedHistoricalNode && store.selectedRoute"
        :node="store.selectedHistoricalNode"
        :route="store.selectedRoute"
        :is-tip="store.selectedHistoricalNode.id === store.selectedLineage?.tipNodeId"
        :command-pending="store.routeCommandPending"
        :pending-route-command="store.pendingRouteCommand"
        :is-active-route="(store.selectedRoute?.id ?? null) === (store.activeRoute?.id ?? null)"
        @back-to-active="store.clearHistoricalSelection()"
        @fork="forkDialogOpen = true"
        @regenerate="regenerateDialogOpen = true"
      />

      <ClarificationPanel
        v-else
        :active-route="store.activeState?.activeRoute ?? null"
        :active-node="store.activeState?.activeNode ?? null"
        :drafting="store.drafting"
        :submitting="store.submitting"
        :feedback="store.feedback"
        @draft="handleDraft"
        @answer="handleAnswer"
      />

      <WorkspaceRightPanel :requirement-state="store.requirementState" />
    </div>

    <p v-if="store.refreshing" class="muted" data-test="refreshing">Refreshing workspace…</p>

    <ForkRouteDialog
      :open="forkDialogOpen"
      :node="store.selectedHistoricalNode"
      :pending="store.pendingRouteCommand === 'fork'"
      @close="forkDialogOpen = false"
      @submit="handleForkSubmit"
    />

    <RegenerateNodeDialog
      :open="regenerateDialogOpen"
      :node="store.selectedHistoricalNode"
      :pending="store.pendingRouteCommand === 'regenerate'"
      @close="regenerateDialogOpen = false"
      @submit="handleRegenerateSubmit"
    />

    <ConfirmRouteActionDialog
      :open="confirmAction !== null && confirmRouteId !== null"
      :kind="confirmAction ?? 'archive'"
      :route-label="store.selectedRoute?.label ?? (confirmRouteId ? store.routes.find(r => r.id === confirmRouteId)?.label ?? null : null)"
      :pending="store.routeCommandPending"
      @cancel="confirmAction = null; confirmRouteId = null"
      @confirm="confirmDestructive"
    />
  </div>
</template>