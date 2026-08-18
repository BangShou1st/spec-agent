import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeProject,
  makeGraphWorkspaceView,
  makeRegenerateResponse,
  makeRequirementState,
  makeRoute,
  makeRouteLineage,
  makeRouteMutation,
  makeSpecGeneration,
  makeSpecSnapshot,
} from '@/test/fixtures'
import type { ActiveProjectStateResponse, RequirementStateView } from '@/api/types'

vi.mock('@/api/projects', () => ({
  getProject: vi.fn(),
}))

vi.mock('@/api/workspace', () => ({
  draftNextQuestion: vi.fn(),
  getActiveState: vi.fn(),
  listRoutes: vi.fn(),
  submitAnswer: vi.fn(),
}))

vi.mock('@/api/requirementState', () => ({
  getRequirementState: vi.fn(),
  getRouteRequirementState: vi.fn(),
}))

vi.mock('@/api/routes', () => ({
  activateRoute: vi.fn(),
  archiveRoute: vi.fn(),
  deleteRoute: vi.fn(),
  forkNode: vi.fn(),
  getRouteLineage: vi.fn(),
  regenerateNode: vi.fn(),
  restoreRoute: vi.fn(),
}))

vi.mock('@/api/graph', () => ({
  getProjectGraph: vi.fn(),
}))

vi.mock('@/api/spec', () => ({
  generateSpec: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

import { getProject } from '@/api/projects'
import { getActiveState, listRoutes } from '@/api/workspace'
import { getRequirementState } from '@/api/requirementState'
import { getProjectGraph } from '@/api/graph'
import {
  activateRoute as apiActivateRoute,
  archiveRoute as apiArchiveRoute,
  deleteRoute as apiDeleteRoute,
  forkNode as apiForkNode,
  getRouteLineage,
  regenerateNode as apiRegenerateNode,
  restoreRoute as apiRestoreRoute,
} from '@/api/routes'
import { generateSpec as apiGenerateSpec, listRouteSpecs } from '@/api/spec'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedListRoutes = vi.mocked(listRoutes)
const mockedGetRequirementState = vi.mocked(getRequirementState)
const mockedGetProjectGraph = vi.mocked(getProjectGraph)
const mockedGetRouteLineage = vi.mocked(getRouteLineage)
const mockedApiActivateRoute = vi.mocked(apiActivateRoute)
const mockedApiRestoreRoute = vi.mocked(apiRestoreRoute)
const mockedApiArchiveRoute = vi.mocked(apiArchiveRoute)
const mockedApiDeleteRoute = vi.mocked(apiDeleteRoute)
const mockedApiForkNode = vi.mocked(apiForkNode)
const mockedApiRegenerateNode = vi.mocked(apiRegenerateNode)
const mockedApiGenerateSpec = vi.mocked(apiGenerateSpec)
const mockedListRouteSpecs = vi.mocked(listRouteSpecs)

describe('workspaceStore route workspace', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  function mockBackendViews(active: ActiveProjectStateResponse, state: RequirementStateView): void {
    mockedGetProject.mockResolvedValue({ ...active.project })
    mockedGetActiveState.mockResolvedValue(active)
    mockedListRoutes.mockResolvedValue(active.activeRoute ? [active.activeRoute] : [])
    mockedGetRequirementState.mockResolvedValue(state)
    mockedGetRouteLineage.mockResolvedValue(makeRouteLineage())
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView())
  }

  /** Active state whose route has a tip node (required for spec generation). */
  function activeWithTip(): ActiveProjectStateResponse {
    return makeActiveState({
      activeRoute: { ...makeRoute({ isActive: true }), tipNodeId: 'lnode-2' },
    })
  }

  async function load(store: ReturnType<typeof useWorkspaceStore>): Promise<void> {
    await store.loadWorkspace('p1')
  }

  it('loads lineage lazily from the backend and caches it', async () => {
    const active = makeActiveState()
    const lineage = makeRouteLineage({ routeId: active.activeRoute?.id ?? 'r1' })
    mockBackendViews(active, makeRequirementState())
    mockedGetRouteLineage.mockResolvedValue(lineage)
    const store = useWorkspaceStore()
    await load(store)

    expect(mockedGetRouteLineage).not.toHaveBeenCalled()

    await store.ensureRouteLineage(active.activeRoute?.id as string)
    expect(mockedGetRouteLineage).toHaveBeenCalledTimes(1)
    expect(store.routeLineages[active.activeRoute?.id as string]).toEqual(lineage)

    // Cached: no second backend call on re-ensure.
    await store.ensureRouteLineage(active.activeRoute?.id as string)
    expect(mockedGetRouteLineage).toHaveBeenCalledTimes(1)
  })

  it('selects a route and clears historical node/spec selection', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)
    store.selectHistoricalNode('lnode-1')
    store.selectedSpecId = 'spec-1'

    store.selectRoute('r-sibling')

    expect(store.selectedRouteId).toBe('r-sibling')
    expect(store.selectedNodeId).toBeNull()
    expect(store.selectedSpecId).toBeNull()
  })

  it('selects and clears a historical node for inspection', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)

    store.selectHistoricalNode('lnode-1')
    expect(store.selectedNodeId).toBe('lnode-1')

    store.clearHistoricalSelection()
    expect(store.selectedNodeId).toBeNull()
  })

  it('opens on the backend-active route and never invents one', async () => {
    const active = makeActiveState()
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)

    expect(store.selectedRouteId).toBe(active.activeRoute?.id)

    mockBackendViews(makeActiveState({ activeRoute: null, activeNode: null }), makeRequirementState())
    await store.loadWorkspace('p1')
    expect(store.selectedRouteId).toBeNull()
  })

  it('activate sends the command then refreshes all canonical reads', async () => {
    const active = makeActiveState()
    const refreshed = makeActiveState({
      project: { ...active.project, id: 'p1' },
      activeRoute: makeRoute({ id: 'r2', isActive: true }),
      activeNode: null,
    })
    mockedApiActivateRoute.mockResolvedValue(makeRouteMutation({ route: refreshed.activeRoute as never }))
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)

    mockedGetActiveState.mockResolvedValue(refreshed)
    mockedListRoutes.mockResolvedValue([refreshed.activeRoute as never])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'r2' }))

    const ok = await store.activateRoute('r2')

    expect(ok).toBe(true)
    expect(mockedApiActivateRoute).toHaveBeenCalledWith('p1', 'r2')
    expect(mockedGetActiveState).toHaveBeenCalledTimes(2)
    expect(mockedGetRequirementState).toHaveBeenCalledTimes(2)
    expect(mockedListRoutes).toHaveBeenCalledTimes(2)
    expect(store.selectedRouteId).toBe('r2')
    expect(mockedGetRouteLineage).toHaveBeenCalledWith('p1', 'r2')
    expect(store.feedback).toBe('Route activated.')
  })

  it('restore sends the command then refreshes canonical reads', async () => {
    const active = makeActiveState()
    const restored = makeActiveState({
      project: { ...active.project, id: 'p1' },
      activeRoute: makeRoute({ id: 'r-archived', isActive: true }),
      activeNode: null,
    })
    mockedApiRestoreRoute.mockResolvedValue(makeRouteMutation({ route: restored.activeRoute as never }))
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)

    mockedGetActiveState.mockResolvedValue(restored)
    mockedListRoutes.mockResolvedValue([restored.activeRoute as never])

    const ok = await store.restoreRoute('r-archived')

    expect(ok).toBe(true)
    expect(mockedApiRestoreRoute).toHaveBeenCalledWith('p1', 'r-archived')
    expect(store.selectedRouteId).toBe('r-archived')
    expect(mockedGetRouteLineage).toHaveBeenCalledWith('p1', 'r-archived')
    expect(store.feedback).toBe('Route restored.')
  })

  it('archive sends the command and refreshes canonical reads', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiArchiveRoute.mockResolvedValue(makeRouteMutation({ activeRouteId: null }))
    const store = useWorkspaceStore()
    await load(store)

    const ok = await store.archiveRoute('r1')

    expect(ok).toBe(true)
    expect(mockedApiArchiveRoute).toHaveBeenCalledWith('p1', 'r1')
    expect(mockedGetActiveState).toHaveBeenCalledTimes(2)
    expect(mockedGetRouteLineage).toHaveBeenCalledWith('p1', 'r1')
    expect(store.feedback).toBe('Route archived.')
  })

  it('delete sends the command and refreshes canonical reads', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiDeleteRoute.mockResolvedValue(makeRouteMutation({ activeRouteId: null }))
    const store = useWorkspaceStore()
    await load(store)

    const ok = await store.deleteRoute('r1')

    expect(ok).toBe(true)
    expect(mockedApiDeleteRoute).toHaveBeenCalledWith('p1', 'r1')
    expect(mockedGetActiveState).toHaveBeenCalledTimes(2)
    expect(store.feedback).toBe('Route deleted.')
  })

  it('fork request contains only the user label', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiForkNode.mockResolvedValue(makeRouteMutation())
    const store = useWorkspaceStore()
    await load(store)

    await store.forkNode('lnode-1', 'Alternative route')
    expect(mockedApiForkNode).toHaveBeenCalledWith('p1', 'lnode-1', { label: 'Alternative route' })

    await store.forkNode('lnode-2', null)
    expect(mockedApiForkNode).toHaveBeenCalledWith('p1', 'lnode-2', { label: null })
  })

  it('fork request never carries runtime-owned ids', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiForkNode.mockResolvedValue(makeRouteMutation())
    const store = useWorkspaceStore()
    await load(store)

    await store.forkNode('lnode-1')
    const [, , payload] = mockedApiForkNode.mock.calls[0]
    expect(Object.keys(payload)).toEqual(['label'])
  })

  it('fork success selects the backend-created active route and reloads its lineage', async () => {
    const active = makeActiveState()
    const forkActive = makeActiveState({
      project: { ...active.project, id: 'p1' },
      activeRoute: makeRoute({ id: 'route-fork', label: 'Fork route', isActive: true }),
      activeNode: null,
    })
    mockedApiForkNode.mockResolvedValue(makeRouteMutation({
      route: forkActive.activeRoute as never,
      activeRouteId: 'route-fork',
    }))
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)

    mockedGetActiveState.mockResolvedValue(forkActive)
    mockedListRoutes.mockResolvedValue([forkActive.activeRoute as never])

    const ok = await store.forkNode('lnode-1', 'Fork route')

    expect(ok).toBe(true)
    // The new route id comes from the backend read, never from a local guess.
    expect(store.selectedRouteId).toBe('route-fork')
    expect(store.selectedNodeId).toBeNull()
    expect(mockedGetRouteLineage).toHaveBeenCalledWith('p1', 'route-fork')
    expect(store.feedback).toBe('Fork created.')
  })

  it('regenerate request passes through without runtime-owned ids', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiRegenerateNode.mockResolvedValue(makeRegenerateResponse())
    const store = useWorkspaceStore()
    await load(store)

    const payload = {
      instruction: 'Make it narrower',
      replacementQuestion: 'What scope is required?',
      replacementPurpose: null,
      replacementOptions: [{ label: 'Tiny', impact: 'Small' }],
    }
    await store.regenerateNode('lnode-2', payload)

    expect(mockedApiRegenerateNode).toHaveBeenCalledWith('p1', 'lnode-2', payload)
    const sentPayload = mockedApiRegenerateNode.mock.calls[0][2]
    expect(sentPayload.replacementOptions?.[0]).not.toHaveProperty('id')
    expect(Object.keys(sentPayload).sort()).toEqual(
      ['instruction', 'replacementOptions', 'replacementPurpose', 'replacementQuestion'],
    )
  })

  it('regenerate success refreshes canonical state and selects the replacement route', async () => {
    const active = makeActiveState()
    const result = makeRegenerateResponse({
      oldRoute: { ...makeRoute({ id: 'route-old', isActive: false }), lifecycleStatus: 'superseded' },
      replacementRoute: { ...makeRoute({ id: 'route-new', isActive: true }), lifecycleStatus: 'open' },
    })
    const regenerated = makeActiveState({
      project: { ...active.project, id: 'p1' },
      activeRoute: { ...result.replacementRoute, isActive: true },
      activeNode: { ...result.replacementNode },
    })
    mockedApiRegenerateNode.mockResolvedValue(result)
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)
    store.selectHistoricalNode('lnode-2')

    mockedGetActiveState.mockResolvedValue(regenerated)
    mockedListRoutes.mockResolvedValue([regenerated.activeRoute as never])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'route-new' }))

    const ok = await store.regenerateNode('lnode-2', {
      replacementQuestion: 'Replacement question',
    })

    expect(ok).toBe(true)
    expect(store.selectedRouteId).toBe('route-new')
    expect(store.selectedNodeId).toBeNull()
    expect(mockedGetRouteLineage).toHaveBeenCalledWith('p1', 'route-new')
    expect(mockedGetRequirementState).toHaveBeenCalledTimes(2)
    expect(store.feedback).toBe('Question regenerated.')
  })

  it('prevents duplicate route commands while one is pending', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    let resolveActivate: (v: ReturnType<typeof makeRouteMutation>) => void = () => undefined
    mockedApiActivateRoute.mockReturnValue(
      new Promise((resolve) => {
        resolveActivate = resolve
      }),
    )
    const store = useWorkspaceStore()
    await load(store)

    const first = store.activateRoute('r2')
    const second = store.activateRoute('r2')

    expect(mockedApiActivateRoute).toHaveBeenCalledTimes(1)
    resolveActivate(makeRouteMutation())
    await first
    await second
    expect(store.routeCommandPending).toBe(false)
  })

  it('surfaces a route command API error safely', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiActivateRoute.mockRejectedValue(
      new ApiError('Only an OPEN route can be activated', 'ROUTE_NOT_ACTIVATABLE', 409),
    )
    const store = useWorkspaceStore()
    await load(store)

    const ok = await store.activateRoute('r-superseded')

    expect(ok).toBe(false)
    expect(store.error?.code).toBe('ROUTE_NOT_ACTIVATABLE')
    expect(store.error?.message).toBe('Only an OPEN route can be activated')
    expect(store.routeCommandPending).toBe(false)
  })

  it('loads spec snapshots for the selected route', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    const snapshot = makeSpecSnapshot()
    mockedListRouteSpecs.mockResolvedValue([snapshot])
    const store = useWorkspaceStore()
    await load(store)

    await store.loadRouteSpecs('r-selected')

    expect(mockedListRouteSpecs).toHaveBeenCalledWith('p1', 'r-selected')
    expect(store.specsByRoute['r-selected']).toEqual([snapshot])
  })

  it('generate spec requires an active route with a tip node', async () => {
    mockBackendViews(makeActiveState({ activeRoute: makeRoute({ tipNodeId: null }), activeNode: null }), makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)

    const ok = await store.generateSpec()

    expect(ok).toBeNull()
    expect(mockedApiGenerateSpec).not.toHaveBeenCalled()
    expect(store.error?.code).toBe('NO_ACTIVE_TIP_NODE')
  })

  it('prevents duplicate spec generation while one is pending', async () => {
    mockBackendViews(activeWithTip(), makeRequirementState())
    let resolveGenerate: (v: ReturnType<typeof makeSpecGeneration>) => void = () => undefined
    mockedApiGenerateSpec.mockReturnValue(
      new Promise((resolve) => {
        resolveGenerate = resolve
      }),
    )
    const store = useWorkspaceStore()
    await load(store)

    const first = store.generateSpec()
    const second = store.generateSpec()

    expect(mockedApiGenerateSpec).toHaveBeenCalledTimes(1)
    resolveGenerate(makeSpecGeneration())
    await first
    await second
    expect(store.generatingSpec).toBe(false)
  })

  it('after successful generation reloads snapshots and selects the new snapshot', async () => {
    mockBackendViews(activeWithTip(), makeRequirementState())
    const generation = makeSpecGeneration({ specSnapshot: makeSpecSnapshot({ id: 'spec-new', routeId: 'route-1' }) })
    mockedApiGenerateSpec.mockResolvedValue(generation)
    const store = useWorkspaceStore()
    await load(store)

    // The canonical list reload returns the persisted snapshot list.
    mockedListRouteSpecs.mockResolvedValue([
      makeSpecSnapshot({ id: 'spec-old', createdAt: '2026-01-02T00:00:00Z' }),
      generation.specSnapshot,
    ])

    const ok = await store.generateSpec()

    expect(ok).not.toBeNull()
    expect(ok?.specSnapshot.id).toBe('spec-new')
    expect(mockedListRouteSpecs).toHaveBeenCalledWith('p1', 'route-1')
    expect(store.selectedSpecId).toBe('spec-new')
    expect(store.selectedSpecIdByRoute['route-1']).toBe('spec-new')
    expect(store.specsByRoute['route-1'].map((s) => s.id)).toEqual(['spec-old', 'spec-new'])
    expect(store.feedback).toBe('Spec snapshot generated.')
  })

  it('cross-route generation selects the route that owns the new snapshot', async () => {
    // active route = route-B, while the user is browsing route-A history.
    const routeA = makeRoute({ id: 'route-A', isActive: false })
    const routeB = { ...makeRoute({ id: 'route-B', isActive: true }), tipNodeId: 'lnode-2' }
    const active = makeActiveState({
      project: makeProject({ id: 'p1' }),
      activeRoute: routeB,
    })
    mockBackendViews(active, makeRequirementState({ routeId: 'route-B' }))
    mockedListRoutes.mockResolvedValue([routeA, routeB])
    const store = useWorkspaceStore()
    await load(store)

    // The user inspects route-A's old snapshot history.
    const staleSnapshotA = makeSpecSnapshot({ id: 'spec-old-A', routeId: 'route-A' })
    mockedListRouteSpecs.mockResolvedValue([staleSnapshotA])
    await store.loadRouteSpecs('route-A')
    store.selectRoute('route-A')
    store.selectSpec('spec-old-A')
    expect(store.selectedRouteId).toBe('route-A')
    expect(store.selectedSpec?.id).toBe('spec-old-A')

    // Generation succeeds on the ACTIVE route B; the canonical route-B list
    // contains only the new snapshot.
    const newSnapshotB = makeSpecSnapshot({ id: 'spec-new-B', routeId: 'route-B' })
    mockedApiGenerateSpec.mockResolvedValue(makeSpecGeneration({ specSnapshot: newSnapshotB }))
    mockedListRouteSpecs.mockResolvedValue([newSnapshotB])

    const ok = await store.generateSpec()

    expect(ok).not.toBeNull()
    expect(ok?.specSnapshot.routeId).toBe('route-B')
    expect(mockedListRouteSpecs).toHaveBeenCalledWith('p1', 'route-B')
    expect(store.selectedRouteId).toBe('route-B')
    expect(store.selectedNodeId).toBeNull()
    expect(store.selectedSpecId).toBe('spec-new-B')
    expect(store.selectedSpec?.id).toBe('spec-new-B')
    expect(store.selectedSpec?.routeId).toBe('route-B')
    expect(store.specsByRoute['route-B'].map((s) => s.id)).toContain('spec-new-B')
    // The stale route-A snapshot must not be what selection resolves to.
    expect(store.selectedSpec?.id).not.toBe('spec-old-A')
    expect(store.feedback).toBe('Spec snapshot generated.')
  })
})