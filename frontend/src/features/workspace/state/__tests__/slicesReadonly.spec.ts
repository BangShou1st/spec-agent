/**
 * Compile-time proof of the narrow-slice read-only contract
 * (`state/slices.ts`).
 *
 * The negative cases use `@ts-expect-error`: if a forbidden cross-domain
 * write ever type-checks again (i.e. the read-only contract regresses),
 * vue-tsc fails with "Unused '@ts-expect-error' directive". The positive
 * case proves legal reads and owned writes still compile and the real store
 * satisfies every slice structurally.
 *
 * The negative helpers ARE executed (noUnusedLocals), but only against a
 * freshly created store whose canonical data is null, so the runtime effect
 * of the (never-type-checking) statements is a no-op.
 */
import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/features/workspace/state/workspaceStore'
import { makeRoute } from '@/test/fixtures'
import type {
  AnswerRunSlice,
  GraphUndoSlice,
  ProposalSlice,
  ProjectSessionSlice,
  ResourceSlice,
  RouteCommandSlice,
  SpecDockSlice,
} from '@/features/workspace/state/slices'

describe('narrow slice read-only contract (compile-time)', () => {
  it('the real store satisfies every slice and legal reads/owned writes compile', () => {
    setActivePinia(createPinia())
    const store = useWorkspaceStore()

    // Structural assignability: the store is used AS each slice, no wrapper.
    const answerRun: AnswerRunSlice = store
    const proposal: ProposalSlice = store
    const resource: ResourceSlice = store
    const specDock: SpecDockSlice = store
    const graphUndo: GraphUndoSlice = store
    const routeCommand: RouteCommandSlice = store
    const session: ProjectSessionSlice = store

    // Legal reads of read-only inputs.
    expect(answerRun.graphView).toBeNull()
    expect(routeCommand.activeState).toBeNull()
    expect(specDock.routeCommandPending).toBe(false)
    expect(resource.graphView).toBeNull()
    expect(graphUndo.undoRedo).toEqual({ canUndo: false, canRedo: false })
    expect(proposal.nodeQuery).toBeNull()
    expect(session.projectId).toBeNull()

    // Domain-owned writable state stays writable.
    answerRun.pendingRouteProjection = null
    answerRun.feedback = null
    answerRun.error = null
    specDock.loadingRequirementRouteId = null
    specDock.manualModelRetry = null
    graphUndo.undoRedo = { canUndo: false, canRedo: false }
    routeCommand.pendingRouteCommand = null
    routeCommand.forkDraftRetryRouteId = null

    // Cross-domain commands still go through the store facade.
    void answerRun.refreshWorkspace()
    void resource.refreshWorkspace()
    void graphUndo.refreshUndoRedoAvailability()

    expect(store.feedback).toBeNull()
  })

  it('forbidden cross-domain writes fail at compile time', () => {
    setActivePinia(createPinia())
    const store = useWorkspaceStore()
    const answerRun: AnswerRunSlice = store
    const routeCommand: RouteCommandSlice = store
    const specDock: SpecDockSlice = store
    const session: ProjectSessionSlice = store

    // (1) Top-level reassignment of a read-only input.
    // @ts-expect-error — canonical graph is a read-only input for the run domain
    answerRun.graphView = null
    // @ts-expect-error — Active state is read-only for the route-command domain
    routeCommand.activeState = null
    // @ts-expect-error — project identity is immutable through a slice
    session.projectId = 'other-project'
    // @ts-expect-error — the run-orchestration lock is read-only for specDock
    specDock.routeCommandPending = true

    // (2) Nested mutation of canonical graph data: arrays are readonly, so
    //     push/splice do not exist, and entries' properties are readonly.
    // @ts-expect-error — canonical graph arrays are deeply read-only
    answerRun.graphView?.routes.push(makeRoute())
    // @ts-expect-error — nested graph objects are deeply read-only
    if (answerRun.graphView?.routes[0]) answerRun.graphView.routes[0].tipNodeId = 'moved'
    // @ts-expect-error — Active pointer state is deeply read-only too
    if (routeCommand.activeState?.activeRoute) routeCommand.activeState.activeRoute.isActive = false

    expect(store.graphView).toBeNull()
  })
})
