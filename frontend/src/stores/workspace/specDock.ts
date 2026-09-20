/**
 * Spec dock domain: the route-scoped requirement-state and spec-snapshot
 * reads, spec generation/export, and the fail-closed reconciliation of a
 * generation whose outcome could not be observed.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 */
import { createAgentRun } from '@/api/agentRuns'
import { toDisplayError } from '@/api/displayError'
import { classifyModelFailure } from '@/api/errorCopy'
import { getRouteRequirementState } from '@/api/requirementState'
import { downloadSpecMarkdown, listRouteSpecs } from '@/api/spec'
import type { SpecExportVariant } from '@/api/spec'
import type { RequirementStateView, SpecSnapshotResponse } from '@/api/types'
import { useRunRegistryStore } from '@/stores/runRegistryStore'
import type { WorkspaceStore } from '../workspaceStore'
import type { ManualModelRetryIntent } from './types'

/**
 * Loads (and caches) the requirement state for an explicit route. The
 * cache is indexed by route id; no global selection decides ownership.
 */
export async function ensureRequirementStateAction(
  store: WorkspaceStore,
  routeId: string,
): Promise<RequirementStateView | null> {
  if (!store.projectId) {
    return null
  }
  const cached = store.requirementStatesByRoute[routeId]
  if (cached) {
    return cached
  }
  store.loadingRequirementRouteId = routeId
  try {
    const state = await getRouteRequirementState(store.projectId, routeId)
    store.requirementStatesByRoute = {
      ...store.requirementStatesByRoute,
      [routeId]: state,
    }
    return state
  } catch (err) {
    store.error = toDisplayError(err)
    return null
  } finally {
    store.loadingRequirementRouteId = null
  }
}

/** Selects the displayed spec snapshot for one explicit route. */
export function selectSpecForRouteAction(
  store: WorkspaceStore,
  routeId: string,
  snapshotId: string | null,
): void {
  store.selectedSpecIdByRoute = {
    ...store.selectedSpecIdByRoute,
    [routeId]: snapshotId,
  }
}

/** Loads the snapshot list for a route from the backend. */
export async function loadRouteSpecsAction(store: WorkspaceStore, routeId: string): Promise<void> {
  if (!store.projectId) {
    return
  }
  store.loadingSpecs = true
  try {
    const specs = await listRouteSpecs(store.projectId, routeId)
    store.specsByRoute = { ...store.specsByRoute, [routeId]: specs }
  } catch (err) {
    store.error = toDisplayError(err)
  } finally {
    store.loadingSpecs = false
  }
}

/**
 * Generates a spec snapshot for the ACTIVE route through the backend.
 * After success the canonical snapshot list is reloaded and the new
 * snapshot is selected in that route's cache; the frontend never
 * synthesizes a spec locally and never sets Focus here. Returns whether
 * a new snapshot landed on this route.
 */
export async function generateSpecAction(store: WorkspaceStore): Promise<boolean> {
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
  let baselineSpecs: SpecSnapshotResponse[]
  try {
    // This read is the mutation baseline. If it fails, do not start a
    // generation request whose outcome could no longer be reconciled.
    baselineSpecs = await listRouteSpecs(store.projectId, routeId)
    store.specsByRoute = { ...store.specsByRoute, [routeId]: baselineSpecs }
  } catch (err) {
    store.error = toDisplayError(err)
    store.manualModelRetry = null
    return false
  }
  const beforeSpecIds = baselineSpecs.map((snapshot) => snapshot.id)
  try {
    const created = await createAgentRun(store.projectId, {
      operation: 'GENERATE_ARTIFACT',
    })
    useRunRegistryStore().register({
      runId: created.runId,
      operation: 'GENERATE_ARTIFACT',
      routeId,
      sourceNodeId: activeRoute.tipNodeId ?? null,
    })
    const outcome = await store.pollRunChainToTerminal(created.runId)
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
    const specs = await listRouteSpecs(store.projectId, routeId)
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
    store.generatingSpec = false
  }
}

/**
 * Downloads one snapshot's Markdown export (browser save). The backend
 * renders the stored snapshot deterministically; this action only owns
 * the pending/error/feedback state around the download.
 */
export async function exportSpecMarkdownAction(
  store: WorkspaceStore,
  snapshotId: string,
  variant: SpecExportVariant,
): Promise<boolean> {
  if (store.exportingSpec) return false
  store.exportingSpec = true
  store.error = null
  try {
    await downloadSpecMarkdown(snapshotId, variant)
    store.feedback =
      variant === 'delivery' ? '已导出开发需求文档' : '已导出规格快照 Markdown'
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    return false
  } finally {
    store.exportingSpec = false
  }
}

export async function reconcileSpecRetryAction(
  store: WorkspaceStore,
  intent: Extract<ManualModelRetryIntent, { kind: 'spec' }>,
): Promise<boolean> {
  const previousError = store.error
  const reconciled = await store.refreshWorkspace()
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
    specs = await listRouteSpecs(store.projectId!, intent.routeId)
  } catch {
    store.error = previousError
    return false
  }
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
