/**
 * Regression tests for the project-session identity of workspace async
 * reads (issue: a slow request for project A overwrote project B's
 * canonical state after B had already loaded).
 *
 * All scenarios use controlled gates — deterministic, no real timing, and
 * covering A→B, A→B→A, and same-project out-of-order reloads. The
 * project-session counter (not just projectId) is what these tests pin.
 *
 * Gate model: a gate is opened per project id; while open, that project's
 * canonical reads wait on the gate. Re-mocking other projects' reads never
 * detaches the pending call, so the stale response is always really in
 * flight when the gate is released.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
} from '@/test/fixtures'
import type { ProjectResponse } from '@/api/types'

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
  reanswerNode: vi.fn(),
  getRouteLineage: vi.fn(),
  regenerateNode: vi.fn(),
  restoreRoute: vi.fn(),
  startRouteFromNode: vi.fn(),
}))

vi.mock('@/api/spec', () => ({
  generateSpec: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

vi.mock('@/api/graphCommands', () => ({
  acceptProposal: vi.fn(),
  appendContinuation: vi.fn(),
  connectFloatingNode: vi.fn(),
  createFloatingDraftNode: vi.fn(),
  createRelation: vi.fn(),
  createNodeQuery: vi.fn(),
  createRootDraftNode: vi.fn(),
  disconnectNode: vi.fn(),
  getNodeQueryResult: vi.fn(),
  getUndoRedoAvailability: vi.fn(),
  listProposals: vi.fn(),
  redoGraphOperation: vi.fn(),
  rejectProposal: vi.fn(),
  reviseDraftNode: vi.fn(),
  setKnowledgeStatus: vi.fn(),
  undoGraphOperation: vi.fn(),
}))

import { getProject } from '@/api/projects'
import { getProjectGraph } from '@/api/graph'
import { getActiveState } from '@/api/workspace'

const mockedGetProject = vi.mocked(getProject)
const mockedGetProjectGraph = vi.mocked(getProjectGraph)
const mockedGetActiveState = vi.mocked(getActiveState)

// ---- Gate plumbing -----------------------------------------------------------

type Gate = {
  promise: Promise<void>
  resolve: () => void
  reject: (err: unknown) => void
}

const gates = new Map<string, Gate>()

function openGate(projectId: string): Gate {
  let resolve!: () => void
  let reject!: (err: unknown) => void
  const promise = new Promise<void>((res, rej) => {
    resolve = res
    reject = rej
  })
  const gate: Gate = { promise, resolve, reject }
  gates.set(projectId, gate)
  return gate
}

function releaseGate(projectId: string): void {
  gates.get(projectId)?.resolve()
  gates.delete(projectId)
}

function breakGate(projectId: string, err: unknown): void {
  gates.get(projectId)?.reject(err)
  gates.delete(projectId)
}

function gated<T>(projectId: string, value: T): Promise<T> {
  const gate = gates.get(projectId)
  return gate ? gate.promise.then(() => value) : Promise.resolve(value)
}

// ---- Canonical read mocks (gate-aware, per project id) -----------------------

function projectFor(id: string, title: string): ProjectResponse {
  return makeProject({ id, title, activeRouteId: `route-${id}` })
}

function installCanonicalReads(): void {
  mockedGetProject.mockImplementation(async (projectId) =>
    gated(projectId, projectFor(projectId, `canonical-${projectId}`)),
  )
  mockedGetActiveState.mockImplementation(async (projectId) =>
    gated(projectId, makeActiveState({
      project: projectFor(projectId, `canonical-${projectId}`),
      activeRoute: makeRoute({
        id: `route-${projectId}`,
        projectId,
        tipNodeId: 'node-x',
        isActive: true,
      }),
      activeNode: makeNode({ id: 'node-x', projectId }),
    })),
  )
  mockedGetProjectGraph.mockImplementation(async (projectId) =>
    gated(projectId, makeGraphWorkspaceView({
      projectId,
      activeRouteId: `route-${projectId}`,
    })),
  )
}

describe('project-session identity for workspace async reads', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    gates.clear()
    vi.clearAllMocks()
    const graphCommands = await import('@/api/graphCommands')
    vi.mocked(graphCommands.listProposals).mockResolvedValue([])
    vi.mocked(graphCommands.getUndoRedoAvailability).mockResolvedValue({
      canUndo: false,
      canRedo: false,
    })
    const requirementState = await import('@/api/requirementState')
    vi.mocked(requirementState.getRequirementState).mockResolvedValue(makeRequirementState())
    const workspace = await import('@/api/workspace')
    vi.mocked(workspace.listRoutes).mockResolvedValue([])
    installCanonicalReads()
  })

  it('slow load of A cannot overwrite B after B finished loading (A→B)', async () => {
    const store = useWorkspaceStore()
    openGate('p1')

    const slowLoad = store.loadWorkspace('p1')
    await vi.waitFor(() => expect(store.loading).toBe(true))
    await store.loadWorkspace('p2')
    expect(store.projectId).toBe('p2')
    expect(store.project?.id).toBe('p2')

    // A's responses land AFTER B took over the store.
    releaseGate('p1')
    await slowLoad
    await vi.waitFor(() => expect(store.loading).toBe(false))

    expect(store.projectId).toBe('p2')
    expect(store.project?.title).toBe('canonical-p2')
    expect(store.graphView?.projectId).toBe('p2')
    expect(store.activeState?.project.id).toBe('p2')
    expect(store.error).toBeNull()
  })

  it('out-of-order SAME-PROJECT reloads keep the newest response (A→A)', async () => {
    const store = useWorkspaceStore()
    // Two SEPARATE gates for the same project id: the first load's reads
    // attach to gate1 at call time, the reload's to gate2 — so the reload
    // can complete while the first load is still in flight.
    const gate1 = openGate('p1')

    const slowLoad = store.loadWorkspace('p1')
    await vi.waitFor(() => expect(store.loading).toBe(true))

    const gate2 = openGate('p1')
    const fastLoad = store.loadWorkspace('p1')
    gate2.resolve()
    await fastLoad
    expect(store.project?.title).toBe('canonical-p1')

    // The first load's responses land AFTER the reload took over.
    gate1.resolve()
    await slowLoad
    await vi.waitFor(() => expect(store.loading).toBe(false))

    // projectId alone cannot distinguish the two loads; the session
    // counter must reject the older one.
    expect(store.project?.title).toBe('canonical-p1')
    expect(store.graphView?.projectId).toBe('p1')
    expect(store.error).toBeNull()
    expect(store.projectSessionId).toBe(2)
  })

  it('stale refresh cannot overwrite the new project state (refresh A→switch B)', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')
    expect(store.project?.id).toBe('p1')

    openGate('p1')
    const refreshing = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))

    await store.loadWorkspace('p2')
    expect(store.project?.id).toBe('p2')

    releaseGate('p1')
    expect(await refreshing).toBe(false)

    expect(store.project?.id).toBe('p2')
    expect(store.graphView?.projectId).toBe('p2')
    expect(store.error).toBeNull()
    expect(store.refreshing).toBe(false)
  })

  it('a refresh requested while another runs is queued, never dropped', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')
    expect(mockedGetProjectGraph).toHaveBeenCalledTimes(1)

    openGate('p1')
    const refresh1 = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))
    // Second refresh arrives while the first is still running: it must be
    // executed (serialized), not rejected with a lost refresh.
    const refresh2 = store.refreshWorkspace()

    releaseGate('p1')
    expect(await refresh1).toBe(true)
    expect(await refresh2).toBe(true)

    // 1 load + 2 refreshes — the second refresh was NOT swallowed by the
    // old `refreshing` early-return.
    expect(mockedGetProjectGraph).toHaveBeenCalledTimes(3)
    expect(store.graphView?.projectId).toBe('p1')
  })

  it('a stale load FAILURE does not surface its error into the new project', async () => {
    const store = useWorkspaceStore()
    openGate('p1')

    const slowLoad = store.loadWorkspace('p1')
    await vi.waitFor(() => expect(store.loading).toBe(true))
    await store.loadWorkspace('p2')

    breakGate('p1', new Error('p1 read failed'))
    await slowLoad

    expect(store.projectId).toBe('p2')
    expect(store.error).toBeNull()
  })

  it('a stale refresh FAILURE does not surface its error into the new project', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1')
    const refreshing = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))

    await store.loadWorkspace('p2')

    breakGate('p1', new Error('p1 refresh failed'))
    expect(await refreshing).toBe(false)

    expect(store.projectId).toBe('p2')
    expect(store.error).toBeNull()
  })

  /** Count of canonical reads issued for one project id. */
  function readCount(projectId: string): number {
    return mockedGetProject.mock.calls.filter((call) => call[0] === projectId).length
  }

  it('a QUEUED stale refresh exits before clearing the new project\'s error or re-requesting the old project', async () => {
    // Reproduction: A's first refresh is in flight; A's second refresh is
    // queued; the user switches to B and B shows an error; A's first
    // refresh ends and the QUEUED second one starts — the old task must
    // not clear B's error and must not send another request for A.
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1')
    const refresh1 = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))
    const refresh2 = store.refreshWorkspace() // queued behind refresh1
    const p1ReadsBefore = readCount('p1')

    await store.loadWorkspace('p2')
    expect(store.projectId).toBe('p2')
    // B is in an errored era (e.g. its own read failed).
    store.error = { code: 'B_ERROR', message: 'B failed' }

    releaseGate('p1')
    expect(await refresh1).toBe(false)
    expect(await refresh2).toBe(false)
    await vi.waitFor(() => expect(store.refreshing).toBe(false))

    // The stale queued task exited BEFORE writing state and BEFORE sending
    // its request: B's error survives, and no extra canonical read for p1
    // was issued.
    expect(store.projectId).toBe('p2')
    expect(store.error).toMatchObject({ code: 'B_ERROR' })
    expect(readCount('p1')).toBe(p1ReadsBefore)
    expect(store.graphView?.projectId).toBe('p2')
  })

  it('after a project switch the new session\'s refresh does NOT wait behind the old session\'s queued/slow refresh', async () => {
    // A's refresh is in flight (gated) and another A refresh is queued.
    // Switch to B: B's refresh must start immediately, not wait for the
    // old session's slow request to resolve.
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1')
    const refresh1 = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))
    void store.refreshWorkspace() // queued behind refresh1

    await store.loadWorkspace('p2')

    const p2ReadsBefore = readCount('p2')
    const refreshB = store.refreshWorkspace()
    // The queued body starts synchronously after its tail — B's canonical
    // reads must be issued without releasing A's gate.
    await vi.waitFor(() => expect(readCount('p2')).toBe(p2ReadsBefore + 1))

    releaseGate('p1')
    expect(await refresh1).toBe(false)
    expect(await refreshB).toBe(true)
    expect(store.projectId).toBe('p2')
  })

  it('a stale refresh finishing does not clear the NEW session\'s refreshing flag', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1')
    const refreshA = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))

    await store.loadWorkspace('p2')

    // B's own refresh is now in flight (gated).
    openGate('p2')
    const refreshB = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))

    // A's slow refresh settles while B's is still running.
    releaseGate('p1')
    expect(await refreshA).toBe(false)

    // The old task's cleanup must not release B's refreshing flag.
    expect(store.refreshing).toBe(true)

    releaseGate('p2')
    expect(await refreshB).toBe(true)
    expect(store.refreshing).toBe(false)
  })
})
