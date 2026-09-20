/**
 * Regression tests for the OWNERSHIP of async cleanup in the workspace
 * store domains (specDock / routeCommands / resources / loader).
 *
 * The project-session guard protects canonical writes, but the earlier
 * implementation still had unconditional `finally` blocks: a slow request
 * for project A (or an older same-project request) could release the NEW
 * request's loading marker or lock, overwrite a newer response, or clear
 * the new project's error. Every scenario below uses controlled gates —
 * deterministic, no real timing.
 *
 * Gate model: a gate is opened per string key (usually `${projectId}:${key}`);
 * while open, the gated API call waits on it. Re-mocking never detaches a
 * pending call, so the stale response is always really in flight when the
 * gate is released.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeClaim,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
  makeSpecSnapshot,
} from '@/test/fixtures'
import type { ProjectResponse, RequirementStateView } from '@/api/types'

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
  downloadSpecMarkdown: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

vi.mock('@/api/graphCommands', () => ({
  acceptProposal: vi.fn(),
  appendContinuation: vi.fn(),
  connectFloatingNode: vi.fn(),
  createFloatingDraftNode: vi.fn(),
  createRelation: vi.fn(),
  createNodeQuery: vi.fn(),
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
import { getActiveState } from '@/api/workspace'
import { activateRoute } from '@/api/routes'
import { getRouteRequirementState } from '@/api/requirementState'
import { listRouteSpecs } from '@/api/spec'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedActivateRoute = vi.mocked(activateRoute)
const mockedGetRouteRequirementState = vi.mocked(getRouteRequirementState)
const mockedListRouteSpecs = vi.mocked(listRouteSpecs)

// ---- Gate plumbing -----------------------------------------------------------

type Gate = {
  promise: Promise<void>
  resolve: () => void
  reject: (err: unknown) => void
}

const gates = new Map<string, Gate>()

function openGate(key: string): Gate {
  let resolve!: () => void
  let reject!: (err: unknown) => void
  const promise = new Promise<void>((res, rej) => {
    resolve = res
    reject = rej
  })
  const gate: Gate = { promise, resolve, reject }
  gates.set(key, gate)
  return gate
}

function releaseGate(key: string): void {
  gates.get(key)?.resolve()
  gates.delete(key)
}

function breakGate(key: string, err: unknown): void {
  gates.get(key)?.reject(err)
  gates.delete(key)
}

function gated<T>(key: string, value: T): Promise<T> {
  const gate = gates.get(key)
  return gate ? gate.promise.then(() => value) : Promise.resolve(value)
}

// ---- Canonical read mocks ----------------------------------------------------

function projectFor(id: string): ProjectResponse {
  return makeProject({ id, title: `canonical-${id}`, activeRouteId: `route-${id}` })
}

function installCanonicalReads(): void {
  mockedGetProject.mockImplementation(async (projectId) =>
    gated(projectId, projectFor(projectId)))
  mockedGetActiveState.mockImplementation(async (projectId) =>
    gated(projectId, makeActiveState({
      project: projectFor(projectId),
      activeRoute: makeRoute({
        id: `route-${projectId}`,
        projectId,
        tipNodeId: 'node-x',
        isActive: true,
      }),
      activeNode: makeNode({ id: 'node-x', projectId }),
    })))
}

describe('async cleanup ownership (loading markers, locks, responses)', () => {
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
    mockedGetRouteRequirementState.mockImplementation(async (projectId, routeId) =>
      gated(`${projectId}:${routeId}`, makeRequirementState({ routeId })))
    mockedListRouteSpecs.mockImplementation(async (projectId) =>
      gated(`specs:${projectId}`, []))
    mockedActivateRoute.mockImplementation(async (projectId, routeId) =>
      gated(`activate:${projectId}`, {
        projectId,
        route: makeRoute({ id: routeId, projectId, isActive: true }),
        activeRouteId: routeId,
      }))
  })

  it('A 的旧需求状态请求结束时，B 的请求仍在运行，B 的加载标记保持正确', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1:rA')
    const slowA = store.ensureRequirementState('rA')
    expect(store.loadingRequirementRouteId).toBe('rA')

    // Switch to B: beginProject cleared the old marker; B starts its own read.
    await store.loadWorkspace('p2')
    expect(store.loadingRequirementRouteId).toBeNull()

    openGate('p2:rB')
    const slowB = store.ensureRequirementState('rB')
    expect(store.loadingRequirementRouteId).toBe('rB')

    // A's stale response settles while B's is still in flight.
    releaseGate('p1:rA')
    await slowA

    // The old request's finally must NOT release B's loading marker.
    expect(store.loadingRequirementRouteId).toBe('rB')
    expect(store.requirementStatesByRoute['rA']).toBeUndefined()

    releaseGate('p2:rB')
    await slowB
    expect(store.loadingRequirementRouteId).toBeNull()
    expect(store.requirementStatesByRoute['rB']?.routeId).toBe('rB')
  })

  it('同项目两条路线并发读取：旧请求完成不清除新请求的加载标记', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1:r1')
    const slowR1 = store.ensureRequirementState('r1')
    expect(store.loadingRequirementRouteId).toBe('r1')

    openGate('p1:r2')
    const slowR2 = store.ensureRequirementState('r2')
    expect(store.loadingRequirementRouteId).toBe('r2')

    // The OLDER request (r1) finishes first: it must not clear the marker
    // that now belongs to r2.
    releaseGate('p1:r1')
    await slowR1
    expect(store.loadingRequirementRouteId).toBe('r2')
    expect(store.requirementStatesByRoute['r1']?.routeId).toBe('r1')

    releaseGate('p1:r2')
    await slowR2
    expect(store.loadingRequirementRouteId).toBeNull()
    expect(store.requirementStatesByRoute['r2']?.routeId).toBe('r2')
  })

  it('同一路线重复读取乱序返回：旧响应不覆盖新响应', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const newer: RequirementStateView = makeRequirementState({
      routeId: 'rX',
      confirmed: [makeClaim({ text: 'NEWER RESPONSE' })],
    })

    // Two SEPARATE gates for the same route: the first call attaches to
    // gate 1, the second (after re-opening) to gate 2 — so the NEWER
    // request can complete while the older one is still in flight.
    const gate1 = openGate('p1:rX')
    const slowOlder = store.ensureRequirementState('rX')
    mockedGetRouteRequirementState.mockImplementationOnce(async () =>
      gated('p1:rX', newer))
    openGate('p1:rX')
    const fastNewer = store.ensureRequirementState('rX')

    releaseGate('p1:rX')
    await fastNewer
    expect(store.requirementStatesByRoute['rX']?.confirmed[0]?.text).toBe('NEWER RESPONSE')

    // The older response lands AFTER the newer one: it must not overwrite.
    gate1.reject(new Error('older read failed'))
    await slowOlder
    expect(store.requirementStatesByRoute['rX']?.confirmed[0]?.text).toBe('NEWER RESPONSE')
  })

  it('旧需求状态请求失败：不覆盖较新请求的结果，也不设置新错误', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const gate1 = openGate('p1:rX')
    const failingOlder = store.ensureRequirementState('rX')
    mockedGetRouteRequirementState.mockImplementationOnce(async () =>
      gated('p1:rX', makeRequirementState({
        routeId: 'rX',
        confirmed: [makeClaim({ text: 'NEWER RESPONSE' })],
      })))
    openGate('p1:rX')
    const newer = store.ensureRequirementState('rX')

    releaseGate('p1:rX')
    await newer
    expect(store.requirementStatesByRoute['rX']?.confirmed[0]?.text).toBe('NEWER RESPONSE')
    expect(store.error).toBeNull()

    // The older request now FAILS: its catch/finally must not write the
    // error (the newer response already owns the route's state).
    gate1.reject(new ApiError('older read failed', 'NETWORK_ERROR', 0))
    await failingOlder

    expect(store.error).toBeNull()
    expect(store.requirementStatesByRoute['rX']?.confirmed[0]?.text).toBe('NEWER RESPONSE')
    expect(store.loadingRequirementRouteId).toBeNull()
  })

  it('旧路线命令收尾不释放新项目的路线命令锁', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('activate:p1')
    const slowA = store.activateRoute('route-p1')
    expect(store.routeCommandPending).toBe(true)

    await store.loadWorkspace('p2')
    expect(store.routeCommandPending).toBe(false)

    openGate('activate:p2')
    const slowB = store.activateRoute('route-p2')
    expect(store.routeCommandPending).toBe(true)
    expect(store.pendingRouteCommand).toBe('activate')

    // A's slow command settles (and even fails) while B's is in flight.
    breakGate('activate:p1', new ApiError('p1 conflict', 'RUNTIME_CONFLICT', 409))
    await slowA

    // The old command's finally must not release B's lock.
    expect(store.routeCommandPending).toBe(true)
    expect(store.pendingRouteCommand).toBe('activate')

    releaseGate('activate:p2')
    expect(await slowB).toBe(true)
    expect(store.routeCommandPending).toBe(false)
    expect(store.pendingRouteCommand).toBeNull()
  })

  it('切换项目后新项目可以正常操作，不被旧项目的忙碌标记卡住', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    // Simulate every busy flag of the old session still being set.
    store.routeCommandPending = true
    store.pendingRouteCommand = 'activate'
    store.drafting = true
    store.graphCommandPending = true
    store.generatingSpec = true
    store.loadingSpecs = true
    store.refreshing = true
    store.loading = true

    await store.loadWorkspace('p2')

    // beginProject resets the old session's busy flags synchronously —
    // with the guarded finally blocks, nobody else would ever release them.
    expect(store.routeCommandPending).toBe(false)
    expect(store.pendingRouteCommand).toBeNull()
    expect(store.drafting).toBe(false)
    expect(store.graphCommandPending).toBe(false)
    expect(store.generatingSpec).toBe(false)
    expect(store.loadingSpecs).toBe(false)
    expect(store.refreshing).toBe(false)

    // The new project is actually operable: a route command runs instead
    // of being rejected by the stale lock.
    const ok = await store.activateRoute('route-p2')
    expect(ok).toBe(true)
    expect(store.feedback).toBe('已设为当前路线')
  })

  it('同一路线的规格列表乱序返回：旧响应不覆盖新响应，加载标记归属正确', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const newerSpec = makeSpecSnapshot({ id: 'spec-new', routeId: 'rX' })

    openGate('p1:specs')
    const slowOlder = store.loadRouteSpecs('rX')
    expect(store.loadingSpecs).toBe(true)
    mockedListRouteSpecs.mockImplementationOnce(async () =>
      gated('p1:specs', [newerSpec]))
    openGate('p1:specs')
    const fastNewer = store.loadRouteSpecs('rX')

    releaseGate('p1:specs')
    await fastNewer
    expect(store.loadingSpecs).toBe(false)
    expect(store.specsByRoute['rX']?.map((s) => s.id)).toEqual(['spec-new'])

    // The older response lands afterwards (it FAILS here): it must not
    // overwrite the newer one, and its finally must not leave a wrong
    // flag behind.
    breakGate('p1:specs', new Error('ignored'))
    await slowOlder
    expect(store.specsByRoute['rX']?.map((s) => s.id)).toEqual(['spec-new'])
    expect(store.loadingSpecs).toBe(false)
  })

  it('旧会话的 generateSpec 失败收尾不释放新会话的 generatingSpec 锁', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    // A's spec generation is blocked on its baseline read.
    openGate('specs:p1')
    const slowA = store.generateSpec()
    expect(store.generatingSpec).toBe(true)

    await store.loadWorkspace('p2')

    // B's spec generation is blocked on its own baseline read.
    openGate('specs:p2')
    const slowB = store.generateSpec()
    expect(store.generatingSpec).toBe(true)

    // A's baseline read now fails; the stale finally must not release
    // B's lock, and its error must not leak into B.
    breakGate('specs:p1', new ApiError('p1 read failed', 'NETWORK_ERROR', 0))
    await slowA

    expect(store.generatingSpec).toBe(true)
    expect(store.error).toBeNull()

    releaseGate('specs:p2')
    expect(await slowB).toBe(false) // empty spec list → no new snapshot path
    expect(store.generatingSpec).toBe(false)
  })
})
