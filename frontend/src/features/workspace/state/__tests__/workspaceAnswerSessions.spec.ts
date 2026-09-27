// 文件名:workspaceAnswerSessions.spec.ts
// 用途:多路线并发回答 run 的回归测试(受控 Promise,无真实时序):锁定逐会话隔离——A 路线完成绝不清除 B 路线的输入草稿、恢复入口或错误状态。
/*
 * 多路线并发回答 run 的回归测试(问题:共享的收尾状态)。
 *
 * 在回答 run 会话模型之前,每次提交尝试都把生命周期写进共享的单值 store
 * 字段(pendingAnswerNodeId、submittedRouteIdForCleanup、
 * lastSubmittedAnswerPayload……),于是 A 路线上完成的 run 会在 B 仍在
 * 运行时清掉 B 的输入草稿、恢复入口或错误状态。这些测试用受控 Promise
 * 锁定逐会话隔离——没有真实时序。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/features/workspace/state/workspaceStore'
import { useInputDraftStore } from '@/features/workspace/state/inputDraftStore'
import {
  makeActiveState,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
} from '@/test/fixtures'
import type { AgentRunView } from '@/features/workspace/api/agentRuns'

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

import { createAgentRun, getAgentRun } from '@/features/workspace/api/agentRuns'

const mockedCreateAgentRun = vi.mocked(createAgentRun)
const mockedGetAgentRun = vi.mocked(getAgentRun)

const P1 = 'p1'
const R1 = 'route-r1'
const R2 = 'route-r2'
const N1 = 'node-n1'
const N2 = 'node-n2'

function runView(overrides: Partial<AgentRunView> = {}): AgentRunView {
  return {
    runId: 'run-x',
    projectId: P1,
    routeId: R1,
    operation: 'ANSWER_TIP',
    status: 'completed',
    phase: 'COMPLETED',
    producedNodeId: 'node-next',
    producedAnswerId: 'answer-1',
    producedPatchId: null,
    producedSpecSnapshotId: null,
    childRunId: null,
    continuationPending: false,
    respondMessage: null,
    ...overrides,
  }
}

/** Controlled terminal promise per run id, resolved manually by tests. */
function makePollControl(): {
  promiseFor: (runId: string) => Promise<AgentRunView>
  resolve: (runId: string, view: AgentRunView) => void
  reject: (runId: string, err: unknown) => void
} {
  const pending = new Map<string, {
    resolve: (v: AgentRunView) => void
    reject: (e: unknown) => void
  }>()
  return {
    promiseFor: (runId) => new Promise<AgentRunView>((resolve, reject) => {
      pending.set(runId, { resolve, reject })
    }),
    resolve: (runId, view) => pending.get(runId)?.resolve(view),
    reject: (runId, err) => pending.get(runId)?.reject(err),
  }
}

describe('concurrent multi-route answer runs (per-session isolation)', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    const active = makeActiveState({
      project: makeProject({ id: P1, activeRouteId: R1 }),
      activeRoute: makeRoute({ id: R1, projectId: P1, tipNodeId: N1, isActive: true }),
      activeNode: makeNode({ id: N1, projectId: P1 }),
    })
    const projects = await import('@/features/projects/api/projects')
    vi.mocked(projects.getProject).mockResolvedValue(
      makeProject({ id: P1, activeRouteId: R1 }),
    )
    const workspace = await import('@/features/workspace/api/workspace')
    vi.mocked(workspace.getActiveState).mockResolvedValue(active)
    vi.mocked(workspace.listRoutes).mockResolvedValue([
      makeRoute({ id: R1, projectId: P1, tipNodeId: N1, isActive: true }),
      makeRoute({ id: R2, projectId: P1, tipNodeId: N2, isActive: false }),
    ])
    const requirementState = await import('@/features/workspace/api/requirementState')
    vi.mocked(requirementState.getRequirementState).mockResolvedValue(makeRequirementState())
    const graph = await import('@/features/workspace/api/graph')
    vi.mocked(graph.getProjectGraph).mockResolvedValue(
      makeGraphWorkspaceView({ projectId: P1, activeRouteId: R1 }),
    )
    const graphCommands = await import('@/features/workspace/api/graphCommands')
    vi.mocked(graphCommands.listProposals).mockResolvedValue([])
    vi.mocked(graphCommands.getUndoRedoAvailability).mockResolvedValue({
      canUndo: false,
      canRedo: false,
    })
  })

  async function loadP1(): Promise<ReturnType<typeof useWorkspaceStore>> {
    const store = useWorkspaceStore()
    await store.loadWorkspace(P1)
    return store
  }

  it('A completes first: A cleans up only its own draft, B keeps running untouched', async () => {
    const store = await loadP1()
    const drafts = useInputDraftStore()
    drafts.setDraft(P1, N1, { selectedOptionId: null, freeText: 'A input' }, R1)
    drafts.setDraft(P1, N2, { selectedOptionId: null, freeText: 'B input' }, R2)

    const poll = makePollControl()
    mockedCreateAgentRun
      .mockResolvedValueOnce({ runId: 'run-a', operation: 'ANSWER_TIP', phase: 'CREATED' })
      .mockResolvedValueOnce({ runId: 'run-b', operation: 'ANSWER_TIP', phase: 'CREATED' })
    mockedGetAgentRun.mockImplementation((_projectId, runId) => poll.promiseFor(runId))

    const submitA = store.submitAnswer({ selectedOptionId: 'opt-a' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-a'))
    const submitB = store.submitAnswer({ selectedOptionId: 'opt-b', nodeId: N2, routeId: R2 })
    await vi.waitFor(() =>
      expect(store.answerRunSessions.filter((s) => s.status === 'RUNNING').length).toBe(2),
    )

    // A 先完成,而 B 仍在途。
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'A done' }))
    expect(await submitA).toBe(true)
    expect(drafts.getDraft(P1, N1, R1)).toBeUndefined()
    // B 的草稿、错误与会话状态与 B 留下时分毫不差。
    expect(drafts.getDraft(P1, N2, R2)?.freeText).toBe('B input')
    expect(store.answerRunsInFlight).toEqual([R2])
    expect(store.submitting).toBe(true)

    poll.resolve('run-b', runView({ runId: 'run-b', routeId: R2, respondMessage: 'B done' }))
    expect(await submitB).toBe(true)
    expect(drafts.getDraft(P1, N2, R2)).toBeUndefined()
    expect(store.answerRunSessions).toEqual([])
    expect(store.submitting).toBe(false)
  })

  it('B completes first (reverse order): B cleans up only its own draft', async () => {
    const store = await loadP1()
    const drafts = useInputDraftStore()
    drafts.setDraft(P1, N1, { selectedOptionId: null, freeText: 'A input' }, R1)
    drafts.setDraft(P1, N2, { selectedOptionId: null, freeText: 'B input' }, R2)

    const poll = makePollControl()
    mockedCreateAgentRun
      .mockResolvedValueOnce({ runId: 'run-a', operation: 'ANSWER_TIP', phase: 'CREATED' })
      .mockResolvedValueOnce({ runId: 'run-b', operation: 'ANSWER_TIP', phase: 'CREATED' })
    mockedGetAgentRun.mockImplementation((_projectId, runId) => poll.promiseFor(runId))

    const submitA = store.submitAnswer({ selectedOptionId: 'opt-a' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-a'))
    const submitB = store.submitAnswer({ selectedOptionId: 'opt-b', nodeId: N2, routeId: R2 })
    await vi.waitFor(() =>
      expect(store.answerRunSessions.filter((s) => s.status === 'RUNNING').length).toBe(2),
    )

    poll.resolve('run-b', runView({ runId: 'run-b', routeId: R2, respondMessage: 'B done' }))
    expect(await submitB).toBe(true)
    expect(drafts.getDraft(P1, N2, R2)).toBeUndefined()
    expect(drafts.getDraft(P1, N1, R1)?.freeText).toBe('A input')

    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'A done' }))
    expect(await submitA).toBe(true)
    expect(drafts.getDraft(P1, N1, R1)).toBeUndefined()
  })

  it('one succeeds, one fails: the failure keeps ITS OWN resubmit payload and draft', async () => {
    const store = await loadP1()
    const drafts = useInputDraftStore()
    drafts.setDraft(P1, N1, { selectedOptionId: null, freeText: 'A input' }, R1)
    drafts.setDraft(P1, N2, { selectedOptionId: null, freeText: 'B input' }, R2)

    const poll = makePollControl()
    mockedCreateAgentRun
      .mockResolvedValueOnce({ runId: 'run-a', operation: 'ANSWER_TIP', phase: 'CREATED' })
      .mockResolvedValueOnce({ runId: 'run-b', operation: 'ANSWER_TIP', phase: 'CREATED' })
    mockedGetAgentRun.mockImplementation((_projectId, runId) => poll.promiseFor(runId))

    const submitA = store.submitAnswer({ selectedOptionId: 'opt-a' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-a'))
    const submitB = store.submitAnswer({
      selectedOptionId: 'opt-b',
      nodeId: N2,
      routeId: R2,
      freeText: 'B answer',
    })
    await vi.waitFor(() =>
      expect(store.answerRunSessions.filter((s) => s.status === 'RUNNING').length).toBe(2),
    )

    // A 成功;B 的 run 失败且什么都没落地(canonical 读取证明)。
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'A done' }))
    expect(await submitA).toBe(true)
    poll.resolve(
      'run-b',
      runView({ runId: 'run-b', routeId: R2, status: 'failed', phase: 'FAILED' }),
    )
    expect(await submitB).toBe(false)

    // B 的会话带着它自己的载荷幸存;A 的会话已消失。
    expect(store.answerRunSessions).toHaveLength(1)
    expect(store.resubmitAnswerPayload).toMatchObject({
      nodeId: N2,
      routeId: R2,
      freeText: 'B answer',
    })
    expect(store.answerRunsInFlight).toEqual([])
    // A 的成功清理从未触碰 B 的草稿。
    expect(drafts.getDraft(P1, N2, R2)?.freeText).toBe('B input')
    expect(drafts.getDraft(P1, N1, R1)).toBeUndefined()
  })

  it('switching projects mid-run stops observation and never cleans up cross-project', async () => {
    const store = await loadP1()
    const drafts = useInputDraftStore()
    drafts.setDraft(P1, N1, { selectedOptionId: null, freeText: 'A input' }, R1)

    const poll = makePollControl()
    mockedCreateAgentRun
      .mockResolvedValueOnce({ runId: 'run-a', operation: 'ANSWER_TIP', phase: 'CREATED' })
    mockedGetAgentRun.mockImplementation((_projectId, runId) => poll.promiseFor(runId))

    const submitA = store.submitAnswer({ selectedOptionId: 'opt-a' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-a'))

    // 在 run 在途期间切换项目。
    const projects = await import('@/features/projects/api/projects')
    vi.mocked(projects.getProject).mockResolvedValue(
      makeProject({ id: 'p2', activeRouteId: null }),
    )
    await store.loadWorkspace('p2')
    expect(store.projectId).toBe('p2')
    expect(store.answerRunSessions).toEqual([])

    // 旧 run 随后落定——被脱离的会话绝不能向新项目写任何东西
    //(无错误、无反馈、无清理)。
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'late A' }))
    expect(await submitA).toBe(true)
    expect(store.projectId).toBe('p2')
    expect(store.feedback).toBeNull()
    expect(store.error).toBeNull()
    expect(store.answerRunSessions).toEqual([])
    // p1 的草稿没有被任何人清除:清理只针对它自己的项目/路线/节点,
    // 而且它的会话在切换时已被丢弃。
    expect(drafts.getDraft(P1, N1, R1)?.freeText).toBe('A input')
  })

  it('same-route duplicate submit stays blocked while another route runs concurrently', async () => {
    const store = await loadP1()
    const poll = makePollControl()
    mockedCreateAgentRun
      .mockResolvedValueOnce({ runId: 'run-a', operation: 'ANSWER_TIP', phase: 'CREATED' })
      .mockResolvedValueOnce({ runId: 'run-b', operation: 'ANSWER_TIP', phase: 'CREATED' })
    mockedGetAgentRun.mockImplementation((_projectId, runId) => poll.promiseFor(runId))

    void store.submitAnswer({ selectedOptionId: 'opt-a' })
    await vi.waitFor(() => expect(store.answerRunsInFlight).toEqual([R1]))

    // 同一条路线:被拒绝,不创建第二个 run。
    expect(await store.submitAnswer({ selectedOptionId: 'opt-a2', nodeId: N1, routeId: R1 })).toBe(false)
    expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1)

    // 另一条路线:允许。
    void store.submitAnswer({ selectedOptionId: 'opt-b', nodeId: N2, routeId: R2 })
    await vi.waitFor(() =>
      expect(store.answerRunSessions.filter((s) => s.status === 'RUNNING').length).toBe(2),
    )
    expect(mockedCreateAgentRun).toHaveBeenCalledTimes(2)
  })

  it('one unknown outcome does not leak into the other route and its draft survives success', async () => {
    const store = await loadP1()
    const drafts = useInputDraftStore()
    drafts.setDraft(P1, N1, { selectedOptionId: null, freeText: 'A input' }, R1)
    drafts.setDraft(P1, N2, { selectedOptionId: null, freeText: 'B input' }, R2)

    const poll = makePollControl()
    mockedCreateAgentRun
      .mockResolvedValueOnce({ runId: 'run-a', operation: 'ANSWER_TIP', phase: 'CREATED' })
      .mockResolvedValueOnce({ runId: 'run-b', operation: 'ANSWER_TIP', phase: 'CREATED' })
    mockedGetAgentRun.mockImplementation((_projectId, runId) => poll.promiseFor(runId))

    const submitA = store.submitAnswer({ selectedOptionId: 'opt-a' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-a'))
    const submitB = store.submitAnswer({ selectedOptionId: 'opt-b', nodeId: N2, routeId: R2 })
    await vi.waitFor(() =>
      expect(store.answerRunSessions.filter((s) => s.status === 'RUNNING').length).toBe(2),
    )

    // B 失败且它的对账读取也失败 → 结果 UNKNOWN(只限于 B 的会话)。
    // 让下一次图读取恰好失败一次。
    const graph = await import('@/features/workspace/api/graph')
    vi.mocked(graph.getProjectGraph)
      .mockRejectedValueOnce(new Error('reconcile read lost'))
    poll.resolve(
      'run-b',
      runView({ runId: 'run-b', routeId: R2, status: 'failed', phase: 'FAILED' }),
    )
    expect(await submitB).toBe(false)
    expect(store.answerRunSessions).toHaveLength(2)
    const unknownSession = store.answerRunSessions.find((s) => s.runId === 'run-b')
    expect(unknownSession?.status).toBe('UNKNOWN')

    // A 随后完成:它的清理绝不能解除/清除 B 的 UNKNOWN 状态。
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'A done' }))
    expect(await submitA).toBe(true)
    const stillUnknown = store.answerRunSessions.find((s) => s.runId === 'run-b')
    expect(stillUnknown?.status).toBe('UNKNOWN')
    expect(store.answerOutcomeUnknown).toBe(true)
    expect(drafts.getDraft(P1, N2, R2)?.freeText).toBe('B input')
    expect(drafts.getDraft(P1, N1, R1)).toBeUndefined()
  })
})
