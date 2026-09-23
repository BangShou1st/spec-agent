/**
 * Workspace loader domain: project identity, the canonical workspace reads that
 * mirror backend state, and the recovery affordances rebuilt from them.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 *
 * Project-session identity: `beginProject` bumps `store.projectSessionId`.
 * Every async read captures `(projectSessionId, projectId)` when it starts
 * and re-validates AFTER each await, BEFORE writing store state — including
 * catch and finally paths. A slow request for project A therefore can never
 * overwrite project B's canonical state, error, or flags, and can never
 * release B's `loading` flag. The counter (not just `projectId`) also
 * covers A→B→A switches and same-project reloads with out-of-order
 * responses. Canonical refreshes are additionally serialized: a refresh
 * requested while another is running is queued behind it, never dropped.
 *
 * NOTE — intentionally NOT sliced: unlike the other domain modules, the
 * loader IS the assembly point that resets and rebuilds every domain's state
 * (answer sessions, spec state, undo, proposals, projections…). A narrow
 * slice here would just re-list `WorkspaceStore`. Its width is its job.
 */
import { listActiveRuns } from '@/features/workspace/api/agentRuns'
import { toDisplayError } from '@/shared/http/displayError'
import { getProjectGraph } from '@/features/workspace/api/graph'
import { getProject } from '@/features/projects/api/projects'
import { getRequirementState } from '@/features/workspace/api/requirementState'
import { getActiveState, listRoutes } from '@/features/workspace/api/workspace'
import { useRunRegistryStore } from './runRegistryStore'
import type { WorkspaceStore } from './workspaceStore'
import {
  loadConfirmableProposalsSafely,
  loadNodeQueryProposalsSafely,
} from './shared'

/**
 * Per-store tail of the refresh chain (WeakMap so concurrent pinia
 * instances — e.g. tests — never observe each other's queues).
 *
 * The chain only ever serializes refreshes of the SAME project session:
 * `beginProject` severs it, so a refresh of the NEW session never queues
 * behind the old session's slow in-flight request.
 */
const refreshTails = new WeakMap<WorkspaceStore, Promise<boolean>>()

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
 * Locks and in-flight flags are reset, not preserved: the OLD session's
 * busy flags belong to actions whose guarded `finally` blocks will never
 * release them (they validate the session first), so leaving them set
 * would freeze the NEW project permanently. Answer-run sessions and the
 * canonical repair checkpoint belong to the OLD project era and are
 * dropped; their in-flight poll loops detach and stop observing.
 */
export function beginProjectAction(store: WorkspaceStore, projectId: string): void {
  useRunRegistryStore().clear()
  store.projectSessionId += 1
  store.projectId = projectId
  // Sever the refresh queue: the NEW session's refresh must never wait
  // behind the old session's slow in-flight (or queued) refresh.
  refreshTails.delete(store)
  store.project = null
  store.routes = []
  store.activeState = null
  store.requirementState = null
  store.feedback = null
  store.error = null
  store.canonicalRepairableAnswerId = null
  store.answerRunSessions = []
  store.manualModelRetry = null
  store.forkDraftRetryRouteId = null
  store.pendingRouteProjection = null
  store.pendingDraftRespondMessage = null
  store.focusAfterMutation = null
  store.graphView = null
  store.requirementStatesByRoute = {}
  store.loadingRequirementRouteId = null
  store.selectedSpecIdByRoute = {}
  store.specsByRoute = {}
  store.nodeQuery = null
  store.nodeQueryProposals = []
  store.pendingConfirmableProposals = []
  store.undoRedo = { canUndo: false, canRedo: false }
  // Busy flags of the OLD session are reset here: every async action's
  // cleanup validates the session before clearing its flag, so without
  // this reset the old action would never release the new project's UI.
  store.loading = false
  store.refreshing = false
  store.drafting = false
  store.repairingAnswer = false
  store.routeCommandPending = false
  store.pendingRouteCommand = null
  store.graphCommandPending = false
  store.generatingSpec = false
  store.exportingSpec = false
  store.loadingSpecs = false
}

export async function loadWorkspaceAction(store: WorkspaceStore, projectId: string): Promise<void> {
  store.beginProject(projectId)
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
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
    // Stale-load guard: a newer beginProject (project switch, or a second
    // load of the same project) owns the store now — drop this response.
    if (!isCurrent()) return
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
    // A stale load must not surface ITS error into the new project.
    if (!isCurrent()) return
    store.error = toDisplayError(err)
  } finally {
    // A stale load must not release the NEW load's `loading` flag either.
    if (isCurrent()) {
      store.loading = false
      // beginProject resets the undo/redo affordance; re-read it from the
      // backend so 刷新状态 never leaves stale greyed-out buttons.
      await store.refreshUndoRedoAvailability()
    }
  }
}

/** Re-reads canonical backend-derived workspace views after a command. */
export async function refreshWorkspaceAction(store: WorkspaceStore): Promise<boolean> {
  // Identity is captured SYNCHRONOUSLY at call time — NOT when the queued
  // body starts, which may be a microtask (or later, behind a slow refresh)
  // after the project already switched. The queued body validates against
  // THIS identity before every write.
  const projectId = store.projectId
  const session = store.projectSessionId
  // Serialize refreshes: a refresh requested while another is running is
  // queued behind it, so a necessary post-command refresh can never be
  // dropped just because `refreshing` was still true.
  const tail = (refreshTails.get(store) ?? Promise.resolve(true)).catch(() => false)
  const run = tail.then(() => runWorkspaceRefresh(store, projectId, session))
  refreshTails.set(store, run.then(
    () => true,
    () => false,
  ))
  return run
}

async function runWorkspaceRefresh(
  store: WorkspaceStore,
  projectId: string | null,
  session: number,
): Promise<boolean> {
  if (!projectId) return false
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  // Stale QUEUED task: exit BEFORE writing any state and BEFORE sending
  // its request. A queued refresh of project A that reaches the head of
  // the chain after the user switched to B must not clear B's error, flip
  // B's `refreshing` flag, or issue another request for A.
  if (!isCurrent()) return false
  store.refreshing = true
  store.error = null
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
    // Stale-refresh guard: the response may belong to a project era that
    // has already been replaced — never write it into the new era.
    if (!isCurrent()) return false
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
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owner clears the flag: a stale refresh settling while the
    // NEW session's refresh is in flight must not release it.
    if (isCurrent()) {
      store.refreshing = false
    }
  }
}

/** Rebuilds recovery affordances from canonical reads after reload/refresh. */
export function restoreCanonicalRecoveryCheckpointsAction(store: WorkspaceStore): void {
  store.canonicalRepairableAnswerId = store.findFinalizedAnswerForActiveTip()
  store.forkDraftRetryRouteId = store.findForkDraftRetryRouteId()
}

/** Reconciles the in-flight run registry with the backend active-runs
 * listing so run cards survive a page reload. Failures here are
 * non-fatal: the registry only drives progress display. */
export async function rebuildRunRegistryAction(store: WorkspaceStore): Promise<void> {
  const projectId = store.projectId
  const session = store.projectSessionId
  if (!projectId) return
  try {
    const views = await listActiveRuns(projectId)
    // The listing may resolve after a project switch; `beginProject`
    // cleared the registry for the NEW project — never repopulate it
    // with the old one's runs.
    if (store.projectSessionId !== session || store.projectId !== projectId) return
    useRunRegistryStore().rebuild(views)
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
