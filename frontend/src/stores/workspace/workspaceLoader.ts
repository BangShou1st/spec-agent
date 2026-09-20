/**
 * Workspace loader domain: project identity, the canonical workspace reads that
 * mirror backend state, and the recovery affordances rebuilt from them.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 */
import { listActiveRuns } from '@/api/agentRuns'
import { toDisplayError } from '@/api/displayError'
import { getProjectGraph } from '@/api/graph'
import { getProject } from '@/api/projects'
import { getRequirementState } from '@/api/requirementState'
import { getActiveState, listRoutes } from '@/api/workspace'
import { useRunRegistryStore } from '@/stores/runRegistryStore'
import type { WorkspaceStore } from '../workspaceStore'
import {
  loadConfirmableProposalsSafely,
  loadNodeQueryProposalsSafely,
} from './shared'

/**
 * Establishes the project identity SYNCHRONOUSLY.
 *
 * The store is a singleton and outlives the workspace component. When the
 * user leaves one workspace and enters another, every `{ immediate: true }`
 * watcher of the new component runs during setup — i.e. BEFORE `onMounted`
 * — and would otherwise read the PREVIOUS project's `projectId` and
 * `graphView`, issuing a cross-project read (404 PROJECT_NOT_FOUND when
 * that previous project has since been deleted). Clearing the identity
 * during setup, not during mount, makes such a read impossible.
 *
 * Locks and in-flight flags are deliberately preserved: this never hides
 * work that is already running.
 */
export function beginProjectAction(store: WorkspaceStore, projectId: string): void {
  useRunRegistryStore().clear()
  store.projectId = projectId
  store.project = null
  store.routes = []
  store.activeState = null
  store.requirementState = null
  store.feedback = null
  store.error = null
  store.repairableAnswerId = null
  store.resubmitAnswerPayload = null
  store.pendingAnswerNodeId = null
  store.answerOutcomeUnknown = false
  store.answerRunId = null
  store.answerRunPhase = null
  store.answerRunStatus = null
  store.lastSubmittedAnswerPayload = null
  store.answerRunsInFlight = []
  store.manualModelRetry = null
  store.forkDraftRetryRouteId = null
  store.pendingRouteProjection = null
  store.pendingDraftRespondMessage = null
  store.focusAfterMutation = null
  store.submittedRouteIdForCleanup = null
  store.graphView = null
  store.requirementStatesByRoute = {}
  store.loadingRequirementRouteId = null
  store.selectedSpecIdByRoute = {}
  store.specsByRoute = {}
  store.nodeQuery = null
  store.nodeQueryProposals = []
  store.pendingConfirmableProposals = []
  store.undoRedo = { canUndo: false, canRedo: false }
}

export async function loadWorkspaceAction(store: WorkspaceStore, projectId: string): Promise<void> {
  store.beginProject(projectId)
  store.loading = true
  try {
    const [project, activeState, routes, requirementState, graphView, proposals, confirmable] = await Promise.all([
      getProject(projectId),
      getActiveState(projectId),
      listRoutes(projectId),
      getRequirementState(projectId),
      getProjectGraph(projectId),
      loadNodeQueryProposalsSafely(projectId),
      loadConfirmableProposalsSafely(projectId),
    ])
    store.project = project
    store.activeState = activeState
    store.routes = routes
    store.requirementState = requirementState
    store.graphView = graphView
    store.nodeQueryProposals = proposals ?? []
    store.pendingConfirmableProposals = confirmable ?? []
    store.restoreCanonicalRecoveryCheckpoints()
    store.requirementStatesByRoute = {}
    store.specsByRoute = {}
    store.selectedSpecIdByRoute = {}
    await store.rebuildRunRegistry()
  } catch (err) {
    store.error = toDisplayError(err)
  } finally {
    store.loading = false
    // beginProject resets the undo/redo affordance; re-read it from the
    // backend so 刷新状态 never leaves stale greyed-out buttons.
    await store.refreshUndoRedoAvailability()
  }
}

/** Re-reads canonical backend-derived workspace views after a command. */
export async function refreshWorkspaceAction(store: WorkspaceStore): Promise<boolean> {
  if (!store.projectId || store.refreshing) {
    return false
  }
  store.refreshing = true
  store.error = null
  try {
    const [project, activeState, routes, requirementState, graphView, proposals, confirmable] = await Promise.all([
      getProject(store.projectId),
      getActiveState(store.projectId),
      listRoutes(store.projectId),
      getRequirementState(store.projectId),
      getProjectGraph(store.projectId),
      loadNodeQueryProposalsSafely(store.projectId),
      loadConfirmableProposalsSafely(store.projectId),
    ])
    store.project = project
    store.activeState = activeState
    store.routes = routes
    store.requirementState = requirementState
    store.graphView = graphView
    store.nodeQueryProposals = proposals ?? []
    store.pendingConfirmableProposals = confirmable ?? []
    store.restoreCanonicalRecoveryCheckpoints()
    // RequirementState is derived and answers/patches change it: drop the
    // route-scoped cache on every canonical refresh so the reading UI
    // reloads it from the backend.
    store.requirementStatesByRoute = {}
    await store.rebuildRunRegistry()
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    return false
  } finally {
    store.refreshing = false
  }
}

/** Rebuilds recovery affordances from canonical reads after reload/refresh. */
export function restoreCanonicalRecoveryCheckpointsAction(store: WorkspaceStore): void {
  store.repairableAnswerId = store.findFinalizedAnswerForActiveTip()
  store.forkDraftRetryRouteId = store.findForkDraftRetryRouteId()
}

/** Reconciles the in-flight run registry with the backend active-runs
 * listing so run cards survive a page reload. Failures here are
 * non-fatal: the registry only drives progress display. */
export async function rebuildRunRegistryAction(store: WorkspaceStore): Promise<void> {
  if (!store.projectId) return
  try {
    useRunRegistryStore().rebuild(await listActiveRuns(store.projectId))
  } catch {
    // Progress display is best-effort; canonical state is unaffected.
  }
}

/** Resolves the route memberships of a canonical node from the graph read. */
export function nodeRouteIdsAction(store: WorkspaceStore, nodeId: string): string[] {
  const routes = store.graphView?.routes ?? []
  const ids = new Set<string>()
  for (const route of routes) {
    if (route.lineageNodeIds.includes(nodeId)) ids.add(route.id)
  }
  return [...ids]
}
