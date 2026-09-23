/**
 * Spec dock domain: the route-scoped requirement-state and spec-snapshot
 * reads, spec generation/export, and the fail-closed reconciliation of a
 * generation whose outcome could not be observed.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 */
import { createAgentRun } from '@/features/workspace/api/agentRuns'
import { toDisplayError } from '@/shared/http/displayError'
import { classifyModelFailure } from '@/shared/http/errorCopy'
import { getRouteRequirementState } from '@/features/workspace/api/requirementState'
import { downloadSpecMarkdown, listRouteSpecs } from '@/features/workspace/api/spec'
import type { SpecExportVariant } from '@/features/workspace/api/spec'
import type { RequirementStateView, SpecSnapshotResponse } from '@/shared/contracts/types'
import { useRunRegistryStore } from './runRegistryStore'
import type { SpecDockSlice } from './slices'
import type { ManualModelRetryIntent } from './types'
import { captureProjectSession } from './shared'

/**
 * Per-store request-generation counters for route-scoped reads.
 *
 * Session identity alone cannot resolve ownership WITHIN one project:
 * two overlapping reads of the same route must not let the OLDER response
 * overwrite the newer one or release the newer request's loading marker.
 * The latest generation per route owns the cache write, the error write,
 * and the marker release.
 */
const requirementLoadGenerations = new WeakMap<object, Map<string, number>>()
const specListGenerations = new WeakMap<object, Map<string, number>>()
/** Monotonic token for the single-slot `loadingSpecs` flag. */
const specListFlagTokens = new WeakMap<object, { token: number }>()

/**
 * Loads (and caches) the requirement state for an explicit route. The
 * cache is indexed by route id; no global selection decides ownership.
 * The response is validated against the project session before the cache
 * write: a slow read must not poison a newer project era's cache.
 */
export async function ensureRequirementStateAction(
  store: SpecDockSlice,
  routeId: string,
): Promise<RequirementStateView | null> {
  if (!store.projectId) {
    return null
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  const cached = store.requirementStatesByRoute[routeId]
  if (cached) {
    return cached
  }
  // Request identity: the latest generation of THIS route decides who may
  // write the cache, the error, and the loading marker.
  const generations = requirementLoadGenerations.get(store) ?? new Map<string, number>()
  requirementLoadGenerations.set(store, generations)
  const myGeneration = (generations.get(routeId) ?? 0) + 1
  generations.set(routeId, myGeneration)
  const isLatestForRoute = (): boolean => generations.get(routeId) === myGeneration
  store.loadingRequirementRouteId = routeId
  try {
    const state = await getRouteRequirementState(projectId, routeId)
    if (!isCurrent() || !isLatestForRoute()) return state
    store.requirementStatesByRoute = {
      ...store.requirementStatesByRoute,
      [routeId]: state,
    }
    return state
  } catch (err) {
    if (!isCurrent() || !isLatestForRoute()) return null
    store.error = toDisplayError(err)
    return null
  } finally {
    // Release the marker only for the still-current session AND only when
    // no newer request of the same route (or another route) owns it now.
    if (
      isCurrent()
      && isLatestForRoute()
      && store.loadingRequirementRouteId === routeId
    ) {
      store.loadingRequirementRouteId = null
    }
  }
}

/** Selects the displayed spec snapshot for one explicit route. */
export function selectSpecForRouteAction(
  store: SpecDockSlice,
  routeId: string,
  snapshotId: string | null,
): void {
  store.selectedSpecIdByRoute = {
    ...store.selectedSpecIdByRoute,
    [routeId]: snapshotId,
  }
}

/** Loads the snapshot list for a route from the backend. */
export async function loadRouteSpecsAction(store: SpecDockSlice, routeId: string): Promise<void> {
  if (!store.projectId) {
    return
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  const generations = specListGenerations.get(store) ?? new Map<string, number>()
  specListGenerations.set(store, generations)
  const myGeneration = (generations.get(routeId) ?? 0) + 1
  generations.set(routeId, myGeneration)
  const isLatestForRoute = (): boolean => generations.get(routeId) === myGeneration
  const flags = specListFlagTokens.get(store) ?? { token: 0 }
  specListFlagTokens.set(store, flags)
  flags.token += 1
  const myToken = flags.token
  store.loadingSpecs = true
  try {
    const specs = await listRouteSpecs(projectId, routeId)
    if (!isCurrent() || !isLatestForRoute()) return
    store.specsByRoute = { ...store.specsByRoute, [routeId]: specs }
  } catch (err) {
    if (!isCurrent() || !isLatestForRoute()) return
    store.error = toDisplayError(err)
  } finally {
    // Session guard: a stale load's cleanup must not release the NEW
    // session's flag (beginProject already reset it there). Token guard:
    // within one session, an older concurrent load must not release the
    // flag of a newer one.
    if (isCurrent() && flags.token === myToken) {
      store.loadingSpecs = false
    }
  }
}

/**
 * Generates a spec snapshot for the ACTIVE route through the backend.
 * After success the canonical snapshot list is reloaded and the new
 * snapshot is selected in that route's cache; the frontend never
 * synthesizes a spec locally and never sets Focus here. Returns whether
 * a new snapshot landed on this route.
 */
export async function generateSpecAction(store: SpecDockSlice): Promise<boolean> {
  if (!store.projectId || store.generatingSpec || store.routeCommandPending) {
    return false
  }
  const activeRoute = store.activeState?.activeRoute
  if (!activeRoute || !activeRoute.tipNodeId) {
    store.error = {
      code: 'NO_ACTIVE_TIP_NODE',
      message: 'The active route has no tip node to generate a spec from.',
    }
    return false
  }
  store.generatingSpec = true
  store.error = null
  const routeId = activeRoute.id
  const { projectId, isCurrent } = captureProjectSession(store)
  // ONE try/catch/finally covers the WHOLE generation flow — including the
  // baseline read. A baseline failure returns through this finally, so the
  // generation lock is always released for the owning session and later
  // generations of the same session are never blocked by residue.
  let baselineSpecs: SpecSnapshotResponse[]
  let beforeSpecIds: string[] = []
  try {
    try {
      // This read is the mutation baseline. If it fails, do not start a
      // generation request whose outcome could no longer be reconciled.
      baselineSpecs = await listRouteSpecs(projectId, routeId)
    } catch (err) {
      if (!isCurrent()) return false
      store.error = toDisplayError(err)
      store.manualModelRetry = null
      return false
    }
    if (!isCurrent()) return false
    store.specsByRoute = { ...store.specsByRoute, [routeId]: baselineSpecs }
    beforeSpecIds = baselineSpecs.map((snapshot) => snapshot.id)
    const created = await createAgentRun(projectId, {
      operation: 'GENERATE_ARTIFACT',
    })
    if (!isCurrent()) return false
    useRunRegistryStore().register({
      runId: created.runId,
      operation: 'GENERATE_ARTIFACT',
      routeId,
      sourceNodeId: activeRoute.tipNodeId ?? null,
    })
    const outcome = await store.pollRunChainToTerminal(created.runId)
    if (!isCurrent()) return false
    if (outcome === 'unknown' || outcome === 'failed') {
      // FAILED or outcome unknown: reconcile canonical reads through the
      // shared fail-closed reconciliation (exactly-one-new-snapshot rule).
      const intent: Extract<ManualModelRetryIntent, { kind: 'spec' }> = {
        kind: 'spec',
        routeId,
        beforeSpecIds,
        state: outcome === 'failed' ? 'ready' : 'needs_reconcile',
      }
      if (outcome === 'unknown') {
        store.manualModelRetry = intent
        const recovered = await store.reconcileSpecRetry(intent)
        if (recovered) return true
        return false
      }
      store.error = {
        code: 'AGENT_RUN_FAILED',
        message: '生成规格快照的运行失败，请重试',
      }
      store.manualModelRetry = intent
      return false
    }
    // COMPLETED: select the produced snapshot from the canonical backend
    // list — never built up locally.
    const producedId = outcome.producedSpecSnapshotId
    const specs = await listRouteSpecs(projectId, routeId)
    if (!isCurrent()) return false
    store.specsByRoute = { ...store.specsByRoute, [routeId]: specs }
    const produced = specs.find((snapshot) => snapshot.id === producedId)
    if (!produced) {
      store.error = {
        code: 'SPEC_SNAPSHOT_NOT_FOUND',
        message: '生成的规格快照无法读取',
      }
      return false
    }
    store.selectedSpecIdByRoute = {
      ...store.selectedSpecIdByRoute,
      [routeId]: produced.id,
    }
    store.feedback = '已生成规格快照'
    store.manualModelRetry = null
    return true
  } catch (err) {
    // The create-run request itself failed or its outcome is unknown;
    // reconcile canonical reads before any retry affordance.
    if (!isCurrent()) return false
    const safeError = toDisplayError(err)
    store.error = safeError
    const disposition = classifyModelFailure(safeError.code, safeError.status)
    if (disposition === 'none') {
      store.manualModelRetry = null
      return false
    }
    const intent: Extract<ManualModelRetryIntent, { kind: 'spec' }> = {
      kind: 'spec',
      routeId,
      beforeSpecIds,
      state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
    }
    store.manualModelRetry = intent
    if (intent.state === 'needs_reconcile') {
      const recovered = await store.reconcileSpecRetry(intent)
      if (recovered) return true
    }
    return false
  } finally {
    // Only the owning session releases the generation lock: a stale
    // generation's cleanup must not release the NEW session's flag.
    if (isCurrent()) {
      store.generatingSpec = false
    }
  }
}

/**
 * Downloads one snapshot's Markdown export (browser save). The backend
 * renders the stored snapshot deterministically; this action only owns
 * the pending/error/feedback state around the download.
 */
export async function exportSpecMarkdownAction(
  store: SpecDockSlice,
  snapshotId: string,
  variant: SpecExportVariant,
): Promise<boolean> {
  if (store.exportingSpec) return false
  const { isCurrent } = captureProjectSession(store)
  store.exportingSpec = true
  store.error = null
  try {
    await downloadSpecMarkdown(snapshotId, variant)
    if (!isCurrent()) return false
    store.feedback =
      variant === 'delivery' ? '已导出开发需求文档' : '已导出规格快照 Markdown'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the flag; beginProject resets it
    // on switch so the new project is never frozen by the old export.
    if (isCurrent()) {
      store.exportingSpec = false
    }
  }
}

export async function reconcileSpecRetryAction(
  store: SpecDockSlice,
  intent: Extract<ManualModelRetryIntent, { kind: 'spec' }>,
): Promise<boolean> {
  const { projectId, isCurrent } = captureProjectSession(store)
  const previousError = store.error
  const reconciled = await store.refreshWorkspace()
  if (!isCurrent()) return false
  if (!reconciled) {
    store.error = previousError
    return false
  }
  if (store.activeState?.activeRoute?.id !== intent.routeId) {
    store.manualModelRetry = { ...intent, state: 'ambiguous' }
    store.error = {
      code: 'RECOVERY_AMBIGUOUS',
      message: '请求结果无法安全确认，请刷新状态后人工核对',
    }
    return false
  }
  let specs: SpecSnapshotResponse[]
  try {
    specs = await listRouteSpecs(projectId, intent.routeId)
  } catch {
    if (!isCurrent()) return false
    store.error = previousError
    return false
  }
  if (!isCurrent()) return false
  store.specsByRoute = { ...store.specsByRoute, [intent.routeId]: specs }
  const newSpecs = specs.filter((snapshot) => !intent.beforeSpecIds.includes(snapshot.id))
  if (newSpecs.length === 1) {
    store.selectedSpecIdByRoute = {
      ...store.selectedSpecIdByRoute,
      [intent.routeId]: newSpecs[0].id,
    }
    store.manualModelRetry = null
    store.error = null
    store.feedback = '已生成规格快照'
    return true
  }
  if (newSpecs.length === 0) {
    store.manualModelRetry = { ...intent, state: 'ready' }
    store.error = previousError
    return false
  }
  store.manualModelRetry = { ...intent, state: 'ambiguous' }
  store.error = {
    code: 'RECOVERY_AMBIGUOUS',
    message: '请求结果无法安全确认，请刷新状态后人工核对',
  }
  return false
}
