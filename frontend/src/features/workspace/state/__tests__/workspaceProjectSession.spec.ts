// 文件名:workspaceProjectSession.spec.ts
// 用途:工作区异步读取的项目会话身份回归测试(受控门,无真实时序):锁定 A→B、A→B→A 与同项目乱序刷新下,慢响应绝不能覆盖另一个项目的 canonical 状态。
/*
 * 工作区异步读取的项目会话身份回归测试(问题:针对项目 A 的慢请求在 B
 * 已经加载完成之后覆盖了 B 的 canonical 状态)。
 *
 * 所有场景都使用受控门——确定性,无真实时序,并覆盖 A→B、A→B→A 与
 * 同项目的乱序刷新。这些测试锁定的正是项目会话计数器(而不只是
 * projectId)。
 *
 * 门模型:按项目 id 开门;门开着时,该项目的 canonical 读取在门上等待。
 * 重新 mock 其它项目的读取绝不会让在途调用脱离,因此门放开时过期响应
 * 确实还在途。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/features/workspace/state/workspaceStore'
import {
  makeActiveState,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
} from '@/test/fixtures'
import type { ProjectResponse } from '@/shared/contracts/types'

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
  generateSpec: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

vi.mock('@/features/workspace/api/graphCommands', () => ({
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

import { getProject } from '@/features/projects/api/projects'
import { getProjectGraph } from '@/features/workspace/api/graph'
import { getActiveState } from '@/features/workspace/api/workspace'

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
  })

  it('slow load of A cannot overwrite B after B finished loading (A→B)', async () => {
    const store = useWorkspaceStore()
    openGate('p1')

    const slowLoad = store.loadWorkspace('p1')
    await vi.waitFor(() => expect(store.loading).toBe(true))
    await store.loadWorkspace('p2')
    expect(store.projectId).toBe('p2')
    expect(store.project?.id).toBe('p2')

    // A 的响应在 B 接管 store 之后才落地。
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
    // 同一项目 id 使用两个独立的门:第一次加载的读取在调用时挂到 gate1,
    // 重载的挂到 gate2——因此重载可以在第一次加载仍在途时完成。
    const gate1 = openGate('p1')

    const slowLoad = store.loadWorkspace('p1')
    await vi.waitFor(() => expect(store.loading).toBe(true))

    const gate2 = openGate('p1')
    const fastLoad = store.loadWorkspace('p1')
    gate2.resolve()
    await fastLoad
    expect(store.project?.title).toBe('canonical-p1')

    // 第一次加载的响应在重载接管之后才落地。
    gate1.resolve()
    await slowLoad
    await vi.waitFor(() => expect(store.loading).toBe(false))

    // 光靠 projectId 无法区分这两次加载;会话计数器必须拒绝较旧的那个。
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
    // 第二次刷新在第一次仍在运行时到来:它必须被执行(串行化),而不是
    // 被拒绝导致刷新丢失。
    const refresh2 = store.refreshWorkspace()

    releaseGate('p1')
    expect(await refresh1).toBe(true)
    expect(await refresh2).toBe(true)

    // 1 load + 2 refreshes — the second refresh was NOT swallowed by the
    // 旧版 `refreshing` 提前返回的行为。
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
    // 复现:A 的第一次刷新在途;A 的第二次刷新已排队;用户切到 B 且 B
    // 显示一个错误;A 的第一次刷新结束、排队的第二次刷新开始——旧任务
    // 绝不能清 B 的错误,也绝不能再为 A 发请求。
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1')
    const refresh1 = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))
    const refresh2 = store.refreshWorkspace() // 排在 refresh1 之后
    const p1ReadsBefore = readCount('p1')

    await store.loadWorkspace('p2')
    expect(store.projectId).toBe('p2')
    // B 处于出错纪元(例如它自己的读取失败了)。
    store.error = { code: 'B_ERROR', message: 'B failed' }

    releaseGate('p1')
    expect(await refresh1).toBe(false)
    expect(await refresh2).toBe(false)
    await vi.waitFor(() => expect(store.refreshing).toBe(false))

    // 过期的排队任务在写状态之前、发请求之前就已退出:B 的错误幸存,
    // 且没有为 p1 发出额外的 canonical 读取。
    expect(store.projectId).toBe('p2')
    expect(store.error).toMatchObject({ code: 'B_ERROR' })
    expect(readCount('p1')).toBe(p1ReadsBefore)
    expect(store.graphView?.projectId).toBe('p2')
  })

  it('after a project switch the new session\'s refresh does NOT wait behind the old session\'s queued/slow refresh', async () => {
    // A 的刷新在途(被门控),另一条 A 刷新已排队。切到 B:B 的刷新必须
    // 立即开始,而不是等旧会话的慢请求落定。
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    openGate('p1')
    const refresh1 = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))
    void store.refreshWorkspace() // 排在 refresh1 之后

    await store.loadWorkspace('p2')

    const p2ReadsBefore = readCount('p2')
    const refreshB = store.refreshWorkspace()
    // 排队的函数体在其链尾之后同步启动——B 的 canonical 读取必须在
    // 不放开 A 的门的情况下发出。
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

    // B 自己的刷新现在在途(被门控)。
    openGate('p2')
    const refreshB = store.refreshWorkspace()
    await vi.waitFor(() => expect(store.refreshing).toBe(true))

    // A 的慢刷新落定时,B 的仍在运行。
    releaseGate('p1')
    expect(await refreshA).toBe(false)

    // 旧任务的清理绝不能释放 B 的 refreshing 标志。
    expect(store.refreshing).toBe(true)

    releaseGate('p2')
    expect(await refreshB).toBe(true)
    expect(store.refreshing).toBe(false)
  })
})
