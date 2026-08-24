import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
  makeRouteMutation,
  makeSpecSnapshot,
} from '@/test/fixtures'
import type { ActiveProjectStateResponse, RequirementStateView } from '@/api/types'

vi.mock('@/api/projects', () => ({
  getProject: vi.fn(),
}))

vi.mock('@/api/workspace', () => ({
  getActiveState: vi.fn(),
  listRoutes: vi.fn(),
}))

vi.mock('@/api/agentRuns', async () => ({
  ...(await vi.importActual<typeof import('@/api/agentRuns')>('@/api/agentRuns')),
  createAgentRun: vi.fn(),
  getAgentRun: vi.fn(),
}))

vi.mock('@/api/requirementState', () => ({
  getRequirementState: vi.fn(),
  getRouteRequirementState: vi.fn(),
}))

vi.mock('@/api/graph', () => ({
  getProjectGraph: vi.fn(),
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

vi.mock('@/api/spec', () => ({
  generateSpec: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

import { getProject } from '@/api/projects'
import { getActiveState, listRoutes } from '@/api/workspace'
import { createAgentRun, getAgentRun } from '@/api/agentRuns'
import type { AgentRunView } from '@/api/agentRuns'
import { getRequirementState } from '@/api/requirementState'
import { getProjectGraph } from '@/api/graph'
import {
  activateRoute as apiActivateRoute,
  archiveRoute as apiArchiveRoute,
  deleteRoute as apiDeleteRoute,
  forkNode as apiForkNode,
  restoreRoute as apiRestoreRoute,
} from '@/api/routes'
import { listRouteSpecs } from '@/api/spec'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedListRoutes = vi.mocked(listRoutes)
const mockedGetRequirementState = vi.mocked(getRequirementState)
const mockedGetProjectGraph = vi.mocked(getProjectGraph)
const mockedCreateAgentRun = vi.mocked(createAgentRun)
const mockedGetAgentRun = vi.mocked(getAgentRun)
const mockedApiActivateRoute = vi.mocked(apiActivateRoute)
const mockedApiRestoreRoute = vi.mocked(apiRestoreRoute)
const mockedApiArchiveRoute = vi.mocked(apiArchiveRoute)
const mockedApiDeleteRoute = vi.mocked(apiDeleteRoute)
const mockedApiForkNode = vi.mocked(apiForkNode)
const mockedListRouteSpecs = vi.mocked(listRouteSpecs)

describe('workspaceStore route workspace', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedListRouteSpecs.mockResolvedValue([])
  })

  function mockBackendViews(active: ActiveProjectStateResponse, state: RequirementStateView): void {
    mockedGetProject.mockResolvedValue({ ...active.project })
    mockedGetActiveState.mockResolvedValue(active)
    mockedListRoutes.mockResolvedValue(active.activeRoute ? [active.activeRoute] : [])
    mockedGetRequirementState.mockResolvedValue(state)
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

  it('loads the canonical graph as part of every workspace load', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)
    expect(mockedGetProjectGraph).toHaveBeenCalledWith('p1')
    expect(store.graphView).not.toBeNull()
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
    expect(mockedGetProjectGraph).toHaveBeenCalledTimes(2)
    expect(store.feedback).toBe('已设为当前路线。')
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
    expect(store.feedback).toBe('已恢复路线。')
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
    expect(store.feedback).toBe('已归档路线。')
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
    expect(store.feedback).toBe('已删除路线。')
  })

  it('fork request carries the explicit source route and user label', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiForkNode.mockResolvedValue(makeRouteMutation())
    const store = useWorkspaceStore()
    await load(store)

    await store.forkNode('lnode-1', 'r1', '替代路线')
    expect(mockedApiForkNode).toHaveBeenCalledWith('p1', 'lnode-1', { sourceRouteId: 'r1', label: '替代路线' })

    await store.forkNode('lnode-2', 'r1', null)
    expect(mockedApiForkNode).toHaveBeenCalledWith('p1', 'lnode-2', { sourceRouteId: 'r1', label: null })
  })

  it('fork request never carries runtime-owned ids beyond explicit source selection', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedApiForkNode.mockResolvedValue(makeRouteMutation())
    const store = useWorkspaceStore()
    await load(store)

    await store.forkNode('lnode-1', 'r1')
    const [, , payload] = mockedApiForkNode.mock.calls[0]
    expect(Object.keys(payload)).toEqual(['sourceRouteId', 'label'])
  })

  it('fork success refreshes canonical reads and never guesses the new route id', async () => {
    // The fork's first-child draft goes through the async run surface; a
    // completed DRAFT_QUESTION run keeps the fork flow successful.
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-draft',
      operation: 'DRAFT_QUESTION',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue({
      runId: 'run-draft',
      projectId: 'p1',
      routeId: 'route-fork',
      operation: 'DRAFT_QUESTION',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: 'n-draft',
      producedAnswerId: null,
      producedPatchId: null,
      producedSpecSnapshotId: null,
    })
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

    const ok = await store.forkNode('lnode-1', 'r1', 'Fork route')

    expect(ok).toBe(true)
    expect(mockedGetProjectGraph).toHaveBeenCalledTimes(3)
    expect(store.activeState?.activeRoute?.id).toBe('route-fork')
    expect(store.feedback).toBe('已创建新分支路线。')
  })

  it('regenerate request passes through without runtime-owned ids', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-regen',
      operation: 'REGENERATE_NODE',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue({
      runId: 'run-regen',
      projectId: 'p1',
      routeId: 'r-new',
      operation: 'REGENERATE_NODE',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: 'n-replacement',
      producedAnswerId: null,
      producedPatchId: null,
      producedSpecSnapshotId: null,
    })
    const store = useWorkspaceStore()
    await load(store)

    await store.regenerateNode('lnode-2', { sourceRouteId: 'r1', instruction: '改窄一些' })

    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', {
      operation: 'REGENERATE_NODE',
      nodeId: 'lnode-2',
      sourceRouteId: 'r1',
      freeText: '改窄一些',
    })
    const sentPayload = mockedCreateAgentRun.mock.calls[0][1]
    expect(Object.keys(sentPayload).sort()).toEqual([
      'freeText', 'nodeId', 'operation', 'sourceRouteId',
    ])
  })

  it('regenerate success refreshes canonical state', async () => {
    const active = makeActiveState()
    const regenerated = makeActiveState({
      project: { ...active.project, id: 'p1' },
      activeRoute: { ...makeRoute({ id: 'route-new', isActive: true }), lifecycleStatus: 'open' },
      activeNode: makeNode({ id: 'n-replacement' }),
    })
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-regen',
      operation: 'REGENERATE_NODE',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue({
      runId: 'run-regen',
      projectId: 'p1',
      routeId: 'route-new',
      operation: 'REGENERATE_NODE',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: 'n-replacement',
      producedAnswerId: null,
      producedPatchId: null,
      producedSpecSnapshotId: null,
    })
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()
    await load(store)

    mockedGetActiveState.mockResolvedValue(regenerated)
    mockedListRoutes.mockResolvedValue([regenerated.activeRoute as never])
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
      projectId: 'p1',
      activeRouteId: 'route-new',
      routes: [{ ...regenerated.activeRoute, rootNodeId: 'n1', lineageNodeIds: ['n1'] } as never],
      nodes: [makeNode({ id: 'n-replacement' })],
    }))
    mockedGetRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'route-new' }))

    const ok = await store.regenerateNode('lnode-2', {
      sourceRouteId: 'r1',
      instruction: '把问题聚焦到可执行范围',
    })

    expect(ok).toBe(true)
    expect(store.activeState?.activeRoute?.id).toBe('route-new')
    expect(store.feedback).toBe('已创建换一个问题路线。')
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
    expect(store.routeCommandPending).toBe(false)
  })

  it('loads spec snapshots for an explicit route', async () => {
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
    mockBackendViews(
      makeActiveState({ activeRoute: makeRoute({ tipNodeId: null }), activeNode: null }),
      makeRequirementState(),
    )
    const store = useWorkspaceStore()
    await load(store)

    const ok = await store.generateSpec()

    expect(ok).toBe(false)
    expect(mockedCreateAgentRun).not.toHaveBeenCalled()
    expect(store.error?.code).toBe('NO_ACTIVE_TIP_NODE')
  })

  it('prevents duplicate spec generation while one is pending', async () => {
    mockBackendViews(activeWithTip(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-spec',
      operation: 'GENERATE_ARTIFACT',
      phase: 'CREATED',
    })
    let resolveRun: (v: AgentRunView) => void = () => undefined
    mockedGetAgentRun.mockReturnValue(
      new Promise<AgentRunView>((resolve) => {
        resolveRun = resolve
      }),
    )
    const store = useWorkspaceStore()
    await load(store)

    const first = store.generateSpec()
    const second = store.generateSpec()

    await Promise.resolve()
    expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1)
    resolveRun({
      runId: 'run-spec',
      projectId: 'p1',
      routeId: 'route-1',
      operation: 'GENERATE_ARTIFACT',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: null,
      producedAnswerId: null,
      producedPatchId: null,
      producedSpecSnapshotId: 'spec-new',
    })
    await first
    await second
    expect(store.generatingSpec).toBe(false)
  })

  it('after successful generation reloads snapshots and selects in the route cache', async () => {
    mockBackendViews(activeWithTip(), makeRequirementState())
    // The generation goes through the async run surface; the produced
    // snapshot id comes from the terminal run read view.
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-spec',
      operation: 'GENERATE_ARTIFACT',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue({
      runId: 'run-spec',
      projectId: 'p1',
      routeId: 'route-1',
      operation: 'GENERATE_ARTIFACT',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: null,
      producedAnswerId: null,
      producedPatchId: null,
      producedSpecSnapshotId: 'spec-new',
    })
    const store = useWorkspaceStore()
    await load(store)

    const activeRouteId = store.activeState?.activeRoute?.id ?? 'route-1'
    mockedGetAgentRun.mockResolvedValue({
      runId: 'run-spec',
      projectId: 'p1',
      routeId: activeRouteId,
      operation: 'GENERATE_ARTIFACT',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: null,
      producedAnswerId: null,
      producedPatchId: null,
      producedSpecSnapshotId: 'spec-new',
    })
    mockedListRouteSpecs.mockResolvedValue([
      makeSpecSnapshot({ id: 'spec-old', createdAt: '2026-01-02T00:00:00Z' }),
      makeSpecSnapshot({ id: 'spec-new', routeId: activeRouteId }),
    ])

    const ok = await store.generateSpec()

    expect(ok).toBe(true)
    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', { operation: 'GENERATE_ARTIFACT' })
    expect(mockedGetAgentRun).toHaveBeenCalledWith('p1', 'run-spec')
    expect(mockedListRouteSpecs).toHaveBeenCalledWith('p1', activeRouteId)
    expect(store.selectedSpecIdByRoute[activeRouteId]).toBe('spec-new')
    expect(store.specsByRoute[activeRouteId].map((s) => s.id)).toEqual(['spec-old', 'spec-new'])
    expect(store.feedback).toBe('已生成规格快照。')
  })

  it('cross-route generation selects the returned artifact route cache', async () => {
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

    const newSnapshotB = makeSpecSnapshot({ id: 'spec-new-B', routeId: 'route-B' })
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-spec-b',
      operation: 'GENERATE_ARTIFACT',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue({
      runId: 'run-spec-b',
      projectId: 'p1',
      routeId: 'route-B',
      operation: 'GENERATE_ARTIFACT',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: null,
      producedAnswerId: null,
      producedPatchId: null,
      producedSpecSnapshotId: 'spec-new-B',
    })
    mockedListRouteSpecs.mockResolvedValue([newSnapshotB])

    const ok = await store.generateSpec()

    expect(ok).toBe(true)
    expect(mockedListRouteSpecs).toHaveBeenCalledWith('p1', 'route-B')
    expect(store.selectedSpecIdByRoute['route-B']).toBe('spec-new-B')
    expect(store.selectedSpecForRoute('route-B')?.id).toBe('spec-new-B')
    expect(store.feedback).toBe('已生成规格快照。')
  })
})
