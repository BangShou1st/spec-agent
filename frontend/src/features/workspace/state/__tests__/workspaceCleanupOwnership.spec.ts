// 文件名:workspaceCleanupOwnership.spec.ts
// 用途:工作区 store 各领域(specDock/routeCommands/resources/loader)异步清理归属的回归测试:用受控门证明过期请求绝不能释放新请求的加载标记/锁或清掉新项目的错误。
/*
 * 工作区 store 各领域(specDock / routeCommands / resources / loader)中
 * 异步清理"归属"的回归测试。
 *
 * 项目会话守卫保护 canonical 写入,但早期实现仍有无条件的 `finally` 块:
 * 针对项目 A 的慢请求(或更旧的同项目请求)可能释放新请求的加载标记或
 * 锁、覆盖更新的响应,或清掉新项目的错误。以下每个场景都使用受控门——
 * 确定性,无真实时序。
 *
 * 门模型:按字符串键(通常是 `${projectId}:${key}`)开门;门开着时,被
 * 门控的 API 调用在门上等待。重新 mock 绝不会让已发出的调用脱离,因此
 * 门放开时过期响应确实还在途。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/shared/http/client'
import { useWorkspaceStore } from '@/features/workspace/state/workspaceStore'
import {
  makeActiveState,
  makeClaim,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
  makeSpecSnapshot,
} from '@/test/fixtures'
import type { ProjectResponse, RequirementStateView } from '@/shared/contracts/types'

vi.mock('@/features/projects/api/projects', () => ({
  getProject: vi.fn(),
}))

vi.mock('@/features/workspace/api/workspace', () => ({
  getActiveState: vi.fn(),
  listRoutes: vi.fn(),
}))

vi.mock('@/features/workspace/api/agentRuns', async () => ({
  ...(await vi.importActual<typeof import('@/features/workspace/api/agentRuns')>('@/features/workspace/api/agentRuns')),
  createAgentRun: vi.fn(),
  getAgentRun: vi.fn(),
}))

vi.mock('@/features/workspace/api/requirementState', () => ({
  getRequirementState: vi.fn(),
  getRouteRequirementState: vi.fn(),
}))

vi.mock('@/features/workspace/api/graph', () => ({
  getProjectGraph: vi.fn(),
}))

vi.mock('@/features/workspace/api/routes', () => ({
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

vi.mock('@/features/workspace/api/spec', () => ({
  downloadSpecMarkdown: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

vi.mock('@/features/workspace/api/graphCommands', () => ({
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

import { getProject } from '@/features/projects/api/projects'
import { getActiveState } from '@/features/workspace/api/workspace'
import { activateRoute } from '@/features/workspace/api/routes'
import { getRouteRequirementState } from '@/features/workspace/api/requirementState'
import { listRouteSpecs } from '@/features/workspace/api/spec'

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
    const graphCommands = await import('@/features/workspace/api/graphCommands')
    vi.mocked(graphCommands.listProposals).mockResolvedValue([])
    vi.mocked(graphCommands.getUndoRedoAvailability).mockResolvedValue({
      canUndo: false,
      canRedo: false,
    })
    const requirementState = await import('@/features/workspace/api/requirementState')
    vi.mocked(requirementState.getRequirementState).mockResolvedValue(makeRequirementState())
    const workspace = await import('@/features/workspace/api/workspace')
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

    // 切到 B:beginProject 已清掉旧标记;B 发起自己的读取。
    await store.loadWorkspace('p2')
    expect(store.loadingRequirementRouteId).toBeNull()

    openGate('p2:rB')
    const slowB = store.ensureRequirementState('rB')
    expect(store.loadingRequirementRouteId).toBe('rB')

    // A 的过期响应落定时,B 的仍在途。
    releaseGate('p1:rA')
    await slowA

    // 旧请求的 finally 绝不能释放 B 的 loading 标记。
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

    // 较旧的请求(r1)先完成:它绝不能清掉现在属于 r2 的标记。
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

    // 同一条路线使用两个独立的门:第一个调用挂在门 1 上,第二个(重新
    // 开门后)挂在门 2 上——因此较新的请求可以在较旧的那个仍在途时完成。
    const gate1 = openGate('p1:rX')
    const slowOlder = store.ensureRequirementState('rX')
    mockedGetRouteRequirementState.mockImplementationOnce(async () =>
      gated('p1:rX', newer))
    openGate('p1:rX')
    const fastNewer = store.ensureRequirementState('rX')

    releaseGate('p1:rX')
    await fastNewer
    expect(store.requirementStatesByRoute['rX']?.confirmed[0]?.text).toBe('NEWER RESPONSE')

    // 较旧的响应在较新的之后落地:绝不能覆盖。
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

    // 较旧的请求现在失败:它的 catch/finally 绝不能写入错误(该路线的
    // 状态已归较新的响应所有)。
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

    // A 的慢命令落定(甚至失败)时,B 的仍在途。
    breakGate('activate:p1', new ApiError('p1 conflict', 'RUNTIME_CONFLICT', 409))
    await slowA

    // 旧命令的 finally 绝不能释放 B 的锁。
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

    // 模拟旧会话的所有忙碌标志仍处于设置状态。
    store.routeCommandPending = true
    store.pendingRouteCommand = 'activate'
    store.drafting = true
    store.graphCommandPending = true
    store.generatingSpec = true
    store.loadingSpecs = true
    store.refreshing = true
    store.loading = true

    await store.loadWorkspace('p2')

    // beginProject 同步重置旧会话的忙碌标志——由于有受守卫的 finally 块,
    // 否则永远不会有别人去释放它们。
    expect(store.routeCommandPending).toBe(false)
    expect(store.pendingRouteCommand).toBeNull()
    expect(store.drafting).toBe(false)
    expect(store.graphCommandPending).toBe(false)
    expect(store.generatingSpec).toBe(false)
    expect(store.loadingSpecs).toBe(false)
    expect(store.refreshing).toBe(false)

    // 新项目确实可操作:路线命令得以执行,而不是被过期锁拒绝。
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

    // 较旧的响应随后落地(这里失败):绝不能覆盖较新的那个,其 finally
    // 也绝不能留下错误的标志。
    breakGate('p1:specs', new Error('ignored'))
    await slowOlder
    expect(store.specsByRoute['rX']?.map((s) => s.id)).toEqual(['spec-new'])
    expect(store.loadingSpecs).toBe(false)
  })

  it('旧会话的 generateSpec 失败收尾不释放新会话的 generatingSpec 锁', async () => {
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    // A 的规格生成被它的基线读取阻塞。
    openGate('specs:p1')
    const slowA = store.generateSpec()
    expect(store.generatingSpec).toBe(true)

    await store.loadWorkspace('p2')

    // B 的规格生成被它自己的基线读取阻塞。
    openGate('specs:p2')
    const slowB = store.generateSpec()
    expect(store.generatingSpec).toBe(true)

    // A 的基线读取现在失败:过期的 finally 绝不能释放 B 的锁,其错误也
    // 绝不能泄漏进 B。
    breakGate('specs:p1', new ApiError('p1 read failed', 'NETWORK_ERROR', 0))
    await slowA

    expect(store.generatingSpec).toBe(true)
    expect(store.error).toBeNull()

    releaseGate('specs:p2')
    expect(await slowB).toBe(false) // 空 spec 列表 → 走没有新快照的路径
    expect(store.generatingSpec).toBe(false)
  })
})
