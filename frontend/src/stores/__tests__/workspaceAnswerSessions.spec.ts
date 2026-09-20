/**
 * Regression tests for multi-route CONCURRENT answer runs (issue: shared
 * finish-up state).
 *
 * Before the answer-run-session model, every submit attempt wrote its
 * lifecycle into single shared store fields (pendingAnswerNodeId,
 * submittedRouteIdForCleanup, lastSubmittedAnswerPayload, ...), so a
 * completing run on route A cleared route B's input draft, recovery
 * affordances, or error state while B was still running. These tests pin
 * the per-session isolation with controlled promises — no real timing.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { useInputDraftStore } from '@/stores/inputDraftStore'
import {
  makeActiveState,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
} from '@/test/fixtures'
import type { AgentRunView } from '@/api/agentRuns'

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

import { createAgentRun, getAgentRun } from '@/api/agentRuns'

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
    const projects = await import('@/api/projects')
    vi.mocked(projects.getProject).mockResolvedValue(
      makeProject({ id: P1, activeRouteId: R1 }),
    )
    const workspace = await import('@/api/workspace')
    vi.mocked(workspace.getActiveState).mockResolvedValue(active)
    vi.mocked(workspace.listRoutes).mockResolvedValue([
      makeRoute({ id: R1, projectId: P1, tipNodeId: N1, isActive: true }),
      makeRoute({ id: R2, projectId: P1, tipNodeId: N2, isActive: false }),
    ])
    const requirementState = await import('@/api/requirementState')
    vi.mocked(requirementState.getRequirementState).mockResolvedValue(makeRequirementState())
    const graph = await import('@/api/graph')
    vi.mocked(graph.getProjectGraph).mockResolvedValue(
      makeGraphWorkspaceView({ projectId: P1, activeRouteId: R1 }),
    )
    const graphCommands = await import('@/api/graphCommands')
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

    // A finishes FIRST while B is still in flight.
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'A done' }))
    expect(await submitA).toBe(true)
    expect(drafts.getDraft(P1, N1, R1)).toBeUndefined()
    // B's draft, error and session state are exactly as B left them.
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

    // A succeeds; B's run FAILS and nothing landed (canonical reads prove it).
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'A done' }))
    expect(await submitA).toBe(true)
    poll.resolve(
      'run-b',
      runView({ runId: 'run-b', routeId: R2, status: 'failed', phase: 'FAILED' }),
    )
    expect(await submitB).toBe(false)

    // B's session survives with ITS OWN payload; A's session is gone.
    expect(store.answerRunSessions).toHaveLength(1)
    expect(store.resubmitAnswerPayload).toMatchObject({
      nodeId: N2,
      routeId: R2,
      freeText: 'B answer',
    })
    expect(store.answerRunsInFlight).toEqual([])
    // A's success cleanup never touched B's draft.
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

    // Switch projects while the run is in flight.
    const projects = await import('@/api/projects')
    vi.mocked(projects.getProject).mockResolvedValue(
      makeProject({ id: 'p2', activeRouteId: null }),
    )
    await store.loadWorkspace('p2')
    expect(store.projectId).toBe('p2')
    expect(store.answerRunSessions).toEqual([])

    // The old run then resolves — the detached session must not write
    // anything into the NEW project (no error, no feedback, no cleanup).
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'late A' }))
    expect(await submitA).toBe(true)
    expect(store.projectId).toBe('p2')
    expect(store.feedback).toBeNull()
    expect(store.error).toBeNull()
    expect(store.answerRunSessions).toEqual([])
    // The p1 draft was NOT cleared by anyone: cleanup only ever targets its
    // own project/route/node, and its session was dropped on switch.
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

    // Same route: rejected, no second run created.
    expect(await store.submitAnswer({ selectedOptionId: 'opt-a2', nodeId: N1, routeId: R1 })).toBe(false)
    expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1)

    // Another route: allowed.
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

    // B fails AND its reconciliation read fails → outcome UNKNOWN (scoped
    // to B's session). Make only the NEXT graph read fail once.
    const graph = await import('@/api/graph')
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

    // A then completes: its cleanup must not resolve/clear B's UNKNOWN state.
    poll.resolve('run-a', runView({ runId: 'run-a', routeId: R1, respondMessage: 'A done' }))
    expect(await submitA).toBe(true)
    const stillUnknown = store.answerRunSessions.find((s) => s.runId === 'run-b')
    expect(stillUnknown?.status).toBe('UNKNOWN')
    expect(store.answerOutcomeUnknown).toBe(true)
    expect(drafts.getDraft(P1, N2, R2)?.freeText).toBe('B input')
    expect(drafts.getDraft(P1, N1, R1)).toBeUndefined()
  })
})
