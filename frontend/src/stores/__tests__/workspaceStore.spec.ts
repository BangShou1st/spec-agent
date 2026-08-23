import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeAnswerExecution,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRegenerateResponse,
  makeRequirementState,
  makeRoute,
  makeRouteLineage,
  makeSpecGeneration,
  makeSpecSnapshot,
} from '@/test/fixtures'
import type {
  ActiveProjectStateResponse,
  RequirementStateView,
  RouteResponse,
} from '@/api/types'

vi.mock('@/api/projects', () => ({
  getProject: vi.fn(),
}))

vi.mock('@/api/workspace', () => ({
  draftNextQuestion: vi.fn(),
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
}))

vi.mock('@/api/spec', () => ({
  generateSpec: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

import { getProject } from '@/api/projects'
import {
  createAgentRun,
  getAgentRun,
} from '@/api/agentRuns'
import type { AgentRunView } from '@/api/agentRuns'
import {
  draftNextQuestion,
  getActiveState,
  listRoutes,
} from '@/api/workspace'
import { useInputDraftStore } from '@/stores/inputDraftStore'
import { getRequirementState, getRouteRequirementState } from '@/api/requirementState'
import { getProjectGraph } from '@/api/graph'
import {
  activateRoute as apiActivateRoute,
  forkNode as apiForkNode,
  getRouteLineage,
  reanswerNode as apiReanswerNode,
  regenerateNode as apiRegenerateNode,
} from '@/api/routes'
import { generateSpec as apiGenerateSpec, listRouteSpecs } from '@/api/spec'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedListRoutes = vi.mocked(listRoutes)
const mockedGetRequirementState = vi.mocked(getRequirementState)
const mockedGetRouteRequirementState = vi.mocked(getRouteRequirementState)
const mockedGetProjectGraph = vi.mocked(getProjectGraph)
const mockedDraftNextQuestion = vi.mocked(draftNextQuestion)
const mockedCreateAgentRun = vi.mocked(createAgentRun)
const mockedGetAgentRun = vi.mocked(getAgentRun)
const mockedGetRouteLineage = vi.mocked(getRouteLineage)
const mockedActivateRoute = vi.mocked(apiActivateRoute)
const mockedForkNode = vi.mocked(apiForkNode)
const mockedReanswerNode = vi.mocked(apiReanswerNode)
const mockedRegenerateNode = vi.mocked(apiRegenerateNode)
const mockedApiGenerateSpec = vi.mocked(apiGenerateSpec)
const mockedListRouteSpecs = vi.mocked(listRouteSpecs)

describe('workspaceStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  function mockBackendViews(active: ActiveProjectStateResponse, state: RequirementStateView): void {
    mockedGetProject.mockResolvedValue(makeProject({ id: active.project.id, title: active.project.title }))
    mockedGetActiveState.mockResolvedValue(active)
    mockedListRoutes.mockResolvedValue(active.activeRoute ? [active.activeRoute] : [])
    mockedGetRequirementState.mockResolvedValue(state)
    mockedGetRouteLineage.mockResolvedValue(makeRouteLineage())
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView())
  }

  /** Completed-run view factory for the happy answer path. */
  function completedRunView(overrides: Partial<AgentRunView> = {}): AgentRunView {
    return {
      runId: 'run-1',
      projectId: 'p1',
      routeId: 'r1',
      operation: 'ANSWER_TIP',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: 'node-next',
      producedAnswerId: 'answer-1',
      producedPatchId: 'patch-1',
      producedSpecSnapshotId: null,
      ...overrides,
    }
  }

  it('loads workspace from the four backend reads', async () => {
    const active = makeActiveState()
    const state = makeRequirementState()
    mockBackendViews(active, state)
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(store.loading).toBe(false)
    expect(store.project?.id).toBe(active.project.id)
    expect(store.activeState?.activeNode?.question).toBe(active.activeNode?.question)
    expect(store.routes[0].isActive).toBe(true)
    expect(store.requirementState?.confirmed[0].status).toBe('confirmed')
    expect(mockedGetProject).toHaveBeenCalledWith('p1')
    expect(mockedGetActiveState).toHaveBeenCalledWith('p1')
    expect(mockedListRoutes).toHaveBeenCalledWith('p1')
    expect(mockedGetRequirementState).toHaveBeenCalledWith('p1')
    expect(mockedGetProjectGraph).toHaveBeenCalledWith('p1')
  })

  it('never manufactures an active node when the backend returns none', async () => {
    const active = makeActiveState({ activeNode: null })
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(store.activeState?.activeNode).toBeNull()
  })

  it('does not draft a question merely because the workspace opened', async () => {
    mockBackendViews(makeActiveState({ activeNode: null }), makeRequirementState())
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(mockedDraftNextQuestion).not.toHaveBeenCalled()
  })

  it('drafts a question only on explicit action and refreshes backend views', async () => {
    const before = makeActiveState({ activeNode: null })
    const draftedNode = makeNode({ question: 'First drafted question' })
    mockBackendViews(before, makeRequirementState())
    mockedDraftNextQuestion.mockResolvedValue({
      agentRun: makeAnswerExecution().agentRun,
      producedNode: draftedNode,
    })
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(store.activeState?.activeNode).toBeNull()
    const readCallsBefore = mockedGetActiveState.mock.calls.length

    // After the draft command the backend serves the new tip node; the
    // frontend must re-read it instead of building it locally.
    mockedGetActiveState.mockResolvedValue(makeActiveState({ activeNode: draftedNode }))

    const ok = await store.draftQuestion()

    expect(ok).toBe(true)
    expect(mockedDraftNextQuestion).toHaveBeenCalledWith('p1')
    expect(mockedGetActiveState.mock.calls.length).toBe(readCallsBefore + 1)
    expect(store.activeState?.activeNode?.question).toBe('First drafted question')
    expect(store.feedback).toBe('问题已起草。')
  })

  it('creates an ANSWER_TIP run and returns pending immediately', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    let resolveRun: (v: ReturnType<typeof completedRunView>) => void = () => undefined
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockReturnValue(
      new Promise((resolve) => {
        resolveRun = resolve
      }),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const pending = store.submitAnswer({ freeText: 'async answer' })

    // The create call returned 202 already; the run is being polled in the
    // background while submitAnswer has NOT resolved yet.
    await vi.waitFor(() => expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1))
    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', {
      operation: 'ANSWER_TIP',
      nodeId: store.pendingAnswerNodeId,
      selectedOptionId: null,
      freeText: 'async answer',
    })
    expect(store.submitting).toBe(true)
    expect(store.answerRunId).toBe('run-1')
    expect(mockedGetAgentRun).toHaveBeenCalledWith('p1', 'run-1')

    resolveRun(completedRunView())
    expect(await pending).toBe(true)
    expect(store.submitting).toBe(false)
  })

  it('submits an option-only answer payload exactly as selected', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(completedRunView())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const ok = await store.submitAnswer({ selectedOptionId: 'opt-a' })

    expect(ok).toBe(true)
    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', {
      operation: 'ANSWER_TIP',
      nodeId: expect.any(String),
      selectedOptionId: 'opt-a',
      freeText: null,
    })
  })

  it('submits a free-text-only answer payload', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(completedRunView())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    await store.submitAnswer({ freeText: 'We need a single-user tool' })

    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', {
      operation: 'ANSWER_TIP',
      nodeId: expect.any(String),
      selectedOptionId: null,
      freeText: 'We need a single-user tool',
    })
  })

  it('submits combined option + free-text payload without discarding either', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(completedRunView())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    await store.submitAnswer({ selectedOptionId: 'opt-a', freeText: 'explanation text' })

    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', {
      operation: 'ANSWER_TIP',
      nodeId: expect.any(String),
      selectedOptionId: 'opt-a',
      freeText: 'explanation text',
    })
  })

  it('prevents duplicate answer submission while a run is still pending', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    let resolvePoll: (v: AgentRunView) => void = () => undefined
    mockedGetAgentRun.mockReturnValue(
      new Promise((resolve) => {
        resolvePoll = resolve
      }),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const first = store.submitAnswer({ freeText: 'first' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-1'))
    const second = store.submitAnswer({ freeText: 'second' })

    expect(await second).toBe(false)
    expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1)

    resolvePoll(completedRunView())
    expect(await first).toBe(true)
    expect(store.submitting).toBe(false)
  })

  it('prevents duplicate drafts while the first is pending', async () => {
    mockBackendViews(makeActiveState({ activeNode: null }), makeRequirementState())
    let resolveDraft: (v: never) => void = () => undefined
    mockedDraftNextQuestion.mockReturnValue(new Promise((resolve) => {
      resolveDraft = resolve as (v: never) => void
    }))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const first = store.draftQuestion()
    const second = store.draftQuestion()

    expect(mockedDraftNextQuestion).toHaveBeenCalledTimes(1)
    resolveDraft(undefined as never)
    await first
    await second
    expect(store.drafting).toBe(false)
  })

  it('after a successful answer refreshes requirement state from the backend (not locally)', async () => {
    const active = makeActiveState()
    const before = makeRequirementState({ confirmed: [] })
    const after = makeRequirementState({
      confirmed: [
        {
          kind: 'goal',
          text: 'Backend-derived confirmed claim',
          status: 'confirmed',
          confidence: 0.9,
          sourceNodeId: 'node-1',
          sourceAnswerId: 'answer-1',
        },
      ],
    })
    mockBackendViews(active, before)
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(completedRunView())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(store.requirementState?.confirmed).toHaveLength(0)

    // The backend now reports the new state; the frontend must re-read it
    // after the run completes.
    mockedGetRequirementState.mockResolvedValue(after)
    mockedGetActiveState.mockResolvedValue(
      makeActiveState({ activeNode: makeNode({ question: 'Drafted next question' }) }),
    )

    await store.submitAnswer({ freeText: 'answer' })

    expect(mockedGetRequirementState).toHaveBeenCalledTimes(2)
    expect(store.requirementState?.confirmed[0].text).toBe('Backend-derived confirmed claim')
    expect(store.activeState?.activeNode?.question).toBe('Drafted next question')
    expect(store.feedback).toBe('回答已记录。')
  })

  it('surfaces a provider-neutral rate-limit error safely when the run fails', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(completedRunView({ status: 'failed', phase: 'FAILED' }))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    // The failed run reconciles against canonical reads; nothing landed, so a
    // one-shot resubmit affordance is offered instead of an opaque error.
    const ok = await store.submitAnswer({ freeText: 'answer' })

    expect(ok).toBe(false)
    expect(store.submitting).toBe(false)
    expect(store.resubmitAnswerPayload).toEqual({ freeText: 'answer' })
    expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1)
  })

  it('polls a FAILED run and offers repair for the persisted Answer without another submit', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'node-1', isActive: true }),
      activeNode: makeNode({ id: 'node-1', projectId: 'p1' }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
      projectId: 'p1',
      activeRouteId: 'r1',
      routes: [{
        ...makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'node-1', isActive: true }),
        rootNodeId: 'node-1',
        lineageNodeIds: ['node-1'],
      }],
      nodes: [makeNode({ id: 'node-1', projectId: 'p1' })],
      answers: [{
        id: 'answer-1', routeId: 'r1', nodeId: 'node-1', selectedOptionId: null,
        freeText: 'answer', createdAt: '2026-01-01T00:00:00Z',
        ownerRouteId: 'r1', inherited: false,
      }],
    }))
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    // The Answer persisted but DECISION crashed afterwards → run FAILED.
    mockedGetAgentRun.mockResolvedValue(completedRunView({
      status: 'failed',
      phase: 'FAILED',
      producedNodeId: null,
    }))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.submitAnswer({ freeText: 'answer' })).toBe(false)
    expect(store.repairableAnswerId).toBe('answer-1')
    expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1)
  })

  it('repairs through a RESUME_ANSWER run and never POSTs a second answer', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'node-1', isActive: true }),
      activeNode: makeNode({ id: 'node-1', projectId: 'p1' }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'resume-run-1',
      operation: 'RESUME_ANSWER',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(completedRunView({
      runId: 'resume-run-1',
      operation: 'RESUME_ANSWER',
      producedNodeId: 'node-next',
    }))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.repairAnswerForActiveFlow('answer-1')).toBe(true)
    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', {
      operation: 'RESUME_ANSWER',
      nodeId: 'node-1',
      answerId: 'answer-1',
    })
    expect(store.repairableAnswerId).toBeNull()
    expect(mockedGetAgentRun).toHaveBeenCalledWith('p1', 'resume-run-1')
  })

  it('keeps routes visible after a refresh with a changeset', async () => {
    const active = makeActiveState({
      activeRoute: makeRoute({ id: 'r1', isActive: true }),
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
    })
    const sibling = makeRoute({
      id: 'r-sibling',
      lifecycleStatus: 'open',
      label: 'Sibling route',
      isActive: false,
    })
    mockedGetProject.mockResolvedValue(makeProject({ id: 'p1', activeRouteId: 'r1' }))
    mockedGetActiveState.mockResolvedValue(active)
    mockedListRoutes.mockResolvedValue([sibling, active.activeRoute as RouteResponse])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState())
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(store.routes.map((r) => r.id)).toEqual(['r-sibling', 'r1'])
    expect(store.routes.find((r) => r.isActive)?.id).toBe('r1')
  })

  it('loads the canonical graph view alongside the other backend reads', async () => {
    const active = makeActiveState()
    const graph = makeGraphWorkspaceView({
      projectId: active.project.id,
      activeRouteId: active.activeRoute?.id ?? 'route-1',
      routes: [
        {
          id: 'route-1',
          label: 'Initial route',
          lifecycleStatus: 'open',
          isActive: true,
          rootNodeId: 'node-1',
          tipNodeId: 'node-1',
          createdFromNodeId: null,
          supersedesRouteId: null,
          replacementOfNodeId: null,
          lineageNodeIds: ['node-1'],
        },
      ],
      nodes: [makeNode({ id: 'node-1', projectId: active.project.id })],
      answers: [],
    })
    mockBackendViews(active, makeRequirementState())
    mockedGetProjectGraph.mockResolvedValue(graph)
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(mockedGetProjectGraph).toHaveBeenCalledWith('p1')
    expect(store.graphView?.projectId).toBe(active.project.id)
    expect(store.graphView?.routes[0].lineageNodeIds).toEqual(['node-1'])
  })

  it('refreshWorkspace replaces graphView from the backend and never patches locally', async () => {
    const active = makeActiveState()
    mockBackendViews(active, makeRequirementState())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    // A runtime mutation added a second node; the backend response is the
    // only authority and the store must replace the whole view.
    const refreshedGraph = makeGraphWorkspaceView({
      projectId: active.project.id,
      activeRouteId: active.activeRoute?.id ?? 'route-1',
      nodes: [
        makeNode({ id: 'node-1', projectId: active.project.id }),
        makeNode({ id: 'node-2', projectId: active.project.id, parentNodeId: 'node-1' }),
      ],
    })
    mockedGetProjectGraph.mockResolvedValue(refreshedGraph)

    await store.refreshWorkspace()

    expect(store.graphView?.nodes.map((n) => n.id)).toEqual(['node-1', 'node-2'])
  })

  it('ensureRequirementState reads and caches route-scoped state per route', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedGetRouteRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'r-other' }))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const first = await store.ensureRequirementState('r-other')
    const second = await store.ensureRequirementState('r-other')

    expect(mockedGetRouteRequirementState).toHaveBeenCalledTimes(1)
    expect(mockedGetRouteRequirementState).toHaveBeenCalledWith('p1', 'r-other')
    expect(first?.routeId).toBe('r-other')
    expect(second?.routeId).toBe('r-other')
    expect(store.requirementStatesByRoute['r-other']?.routeId).toBe('r-other')
  })

  it('ensureRequirementState surfaces failures without throwing', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedGetRouteRequirementState.mockRejectedValue(
      new ApiError('Route not found', 'ROUTE_NOT_FOUND', 404),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const state = await store.ensureRequirementState('r-missing')

    expect(state).toBeNull()
    expect(store.error?.code).toBe('ROUTE_NOT_FOUND')
  })

  it('sends an explicit Fork source and preserves the route when first-child Draft fails', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', isActive: true }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedForkNode.mockResolvedValue({
      projectId: 'p1',
      route: makeRoute({ id: 'forked', projectId: 'p1', isActive: true }),
      activeRouteId: 'forked',
    })
    mockedDraftNextQuestion.mockRejectedValue(new ApiError('draft failed', 'DRAFT_FAILED', 500))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const ok = await store.forkNode('node-1', 'r1', 'future branch')

    expect(ok).toBe(false)
    expect(mockedForkNode).toHaveBeenCalledWith('p1', 'node-1', {
      sourceRouteId: 'r1',
      label: 'future branch',
    })
    expect(mockedDraftNextQuestion).toHaveBeenCalledWith('p1')
    expect(store.forkDraftRetryRouteId).toBe('forked')
    expect(store.feedback).toContain('分支已创建')
  })

  it('does not retry a failed Fork Draft against a different Active route', async () => {
    const initial = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', isActive: true }),
    })
    mockBackendViews(initial, makeRequirementState())
    mockedForkNode.mockResolvedValue({
      projectId: 'p1',
      route: makeRoute({ id: 'forked', projectId: 'p1', isActive: true }),
      activeRouteId: 'forked',
    })
    mockedDraftNextQuestion.mockRejectedValueOnce(new ApiError('draft failed', 'DRAFT_FAILED', 500))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    await store.forkNode('node-1', 'r1', 'future branch')
    expect(store.forkDraftRetryRouteId).toBe('forked')

    const routeA = makeRoute({ id: 'route-a', projectId: 'p1', isActive: true })
    const routeB = makeRoute({ id: 'forked', projectId: 'p1', isActive: false })
    mockedActivateRoute.mockResolvedValue({ projectId: 'p1', route: routeA, activeRouteId: 'route-a' })
    mockedGetActiveState.mockResolvedValue(makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'route-a' }),
      activeRoute: routeA,
    }))
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
      projectId: 'p1',
      activeRouteId: 'route-a',
      routes: [
        {
          id: 'route-a',
          label: 'Route A',
          lifecycleStatus: 'open',
          isActive: true,
          rootNodeId: 'node-1',
          tipNodeId: 'node-1',
          createdFromNodeId: null,
          supersedesRouteId: null,
          replacementOfNodeId: null,
          lineageNodeIds: ['node-1'],
        },
        {
          id: routeB.id,
          label: 'Fork B',
          lifecycleStatus: 'open',
          isActive: false,
          rootNodeId: 'node-1',
          tipNodeId: 'node-1',
          createdFromNodeId: 'node-1',
          supersedesRouteId: null,
          replacementOfNodeId: null,
          lineageNodeIds: ['node-1'],
        },
      ],
    }))

    expect(await store.activateRoute('route-a')).toBe(true)
    mockedDraftNextQuestion.mockClear()

    expect(await store.retryForkDraft()).toBe(false)
    expect(mockedDraftNextQuestion).not.toHaveBeenCalled()
    expect(store.forkDraftRetryRouteId).toBeNull()
  })

  it('does not retain a Fork retry checkpoint after a successful first Draft', async () => {
    const initialRoute = makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'n1', isActive: true })
    const forkRoute = makeRoute({
      id: 'forked', projectId: 'p1', tipNodeId: 'n1', isActive: true,
      branchType: 'fork', branchAtNodeId: 'n1',
    })
    const activeBefore = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: initialRoute,
    })
    const activeAfterDraft = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'forked' }),
      activeRoute: { ...forkRoute, tipNodeId: 'n2' },
    })
    mockBackendViews(activeBefore, makeRequirementState())
    mockedGetActiveState.mockResolvedValueOnce(activeBefore).mockResolvedValue(activeAfterDraft)
    mockedGetProjectGraph
      .mockResolvedValueOnce(makeGraphWorkspaceView({ projectId: 'p1', activeRouteId: 'r1' }))
      .mockResolvedValue(makeGraphWorkspaceView({
        projectId: 'p1',
        activeRouteId: 'forked',
        routes: [{ ...forkRoute, tipNodeId: 'n2', rootNodeId: 'n1', lineageNodeIds: ['n1', 'n2'] }],
        nodes: [makeNode({ id: 'n1' }), makeNode({ id: 'n2' })],
      }))
    mockedForkNode.mockResolvedValue({
      projectId: 'p1', route: forkRoute, activeRouteId: 'forked',
    })
    mockedDraftNextQuestion.mockResolvedValue({
      agentRun: makeAnswerExecution().agentRun,
      producedNode: makeNode({ id: 'n2' }),
    })
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.forkNode('n1', 'r1', 'future branch')).toBe(true)
    expect(mockedDraftNextQuestion).toHaveBeenCalledTimes(1)
    expect(store.forkDraftRetryRouteId).toBeNull()

    expect(await store.retryForkDraft()).toBe(false)
    expect(mockedDraftNextQuestion).toHaveBeenCalledTimes(1)
  })

  it('treats a lost Fork first-Draft response as success when the canonical tip advanced', async () => {
    const initialRoute = makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'n1', isActive: true })
    const forkRoute = makeRoute({
      id: 'forked', projectId: 'p1', tipNodeId: 'n1', isActive: true,
      branchType: 'fork', branchAtNodeId: 'n1',
    })
    const activeBefore = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }), activeRoute: initialRoute,
    })
    const activeOnFork = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'forked' }), activeRoute: forkRoute,
    })
    const activeAfterDraft = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'forked' }),
      activeRoute: { ...forkRoute, tipNodeId: 'n2' },
    })
    const checkpointGraph = makeGraphWorkspaceView({
      projectId: 'p1', activeRouteId: 'forked',
      routes: [{ ...forkRoute, rootNodeId: 'n1', lineageNodeIds: ['n1'] }],
      answers: [{
        id: 'inherited-answer', routeId: 'forked', ownerRouteId: 'r1', inherited: true,
        nodeId: 'n1', selectedOptionId: null, freeText: 'source answer',
        createdAt: '2026-01-01T00:00:00Z',
      }],
    })
    const advancedGraph = makeGraphWorkspaceView({
      projectId: 'p1', activeRouteId: 'forked',
      routes: [{ ...forkRoute, tipNodeId: 'n2', rootNodeId: 'n1', lineageNodeIds: ['n1', 'n2'] }],
      nodes: [makeNode({ id: 'n1' }), makeNode({ id: 'n2' })],
    })
    mockBackendViews(activeBefore, makeRequirementState())
    mockedGetActiveState
      .mockResolvedValueOnce(activeBefore)
      .mockResolvedValueOnce(activeOnFork)
      .mockResolvedValue(activeAfterDraft)
    mockedGetProjectGraph
      .mockResolvedValueOnce(makeGraphWorkspaceView({ projectId: 'p1', activeRouteId: 'r1' }))
      .mockResolvedValueOnce(checkpointGraph)
      .mockResolvedValue(advancedGraph)
    mockedForkNode.mockResolvedValue({
      projectId: 'p1', route: forkRoute, activeRouteId: 'forked',
    })
    mockedDraftNextQuestion.mockRejectedValue(new ApiError('network lost', 'NETWORK_ERROR', 0))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.forkNode('n1', 'r1', 'future branch')).toBe(true)
    expect(mockedDraftNextQuestion).toHaveBeenCalledTimes(1)
    expect(store.forkDraftRetryRouteId).toBeNull()
    expect(store.manualModelRetry).toBeNull()
    expect(store.error).toBeNull()
    expect(store.feedback).toBe('已创建新分支路线。')
  })

  it('reload restores an owned active-tip Answer as the repair target', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
      activeNode: makeNode({ id: 'n1', projectId: 'p1' }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
      projectId: 'p1',
      activeRouteId: 'r1',
      routes: [{
        ...makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
        rootNodeId: 'n1',
        lineageNodeIds: ['n1'],
      }],
      nodes: [makeNode({ id: 'n1', projectId: 'p1' })],
      answers: [{
        id: 'answer-owned',
        routeId: 'r1',
        ownerRouteId: 'r1',
        inherited: false,
        nodeId: 'n1',
        selectedOptionId: null,
        freeText: 'saved answer',
        createdAt: '2026-01-01T00:00:00Z',
      }],
    }))
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(store.repairableAnswerId).toBe('answer-owned')
  })

  it('never treats an inherited active-tip Answer as a repair target', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r-fork' }),
      activeRoute: makeRoute({ id: 'r-fork', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
      activeNode: makeNode({ id: 'n1', projectId: 'p1' }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
      projectId: 'p1',
      activeRouteId: 'r-fork',
      routes: [{
        ...makeRoute({ id: 'r-fork', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
        rootNodeId: 'n1',
        lineageNodeIds: ['n1'],
      }],
      nodes: [makeNode({ id: 'n1', projectId: 'p1' })],
      answers: [{
        id: 'answer-inherited',
        routeId: 'r-fork',
        ownerRouteId: 'r-source',
        inherited: true,
        nodeId: 'n1',
        selectedOptionId: null,
        freeText: 'inherited answer',
        createdAt: '2026-01-01T00:00:00Z',
      }],
    }))
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(store.repairableAnswerId).toBeNull()
  })

  it('reload restores the failed Fork first-draft checkpoint', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r-fork' }),
      activeRoute: makeRoute({
        id: 'r-fork', projectId: 'p1', tipNodeId: 'n1', isActive: true,
        branchType: 'fork', branchAtNodeId: 'n1',
      }),
      activeNode: makeNode({ id: 'n1', projectId: 'p1' }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
      projectId: 'p1',
      activeRouteId: 'r-fork',
      routes: [{
        ...makeRoute({
          id: 'r-fork', projectId: 'p1', tipNodeId: 'n1', isActive: true,
          branchType: 'fork', branchAtNodeId: 'n1',
        }),
        rootNodeId: 'n1',
        lineageNodeIds: ['n1'],
      }],
      nodes: [makeNode({ id: 'n1', projectId: 'p1' })],
      answers: [{
        id: 'answer-inherited', routeId: 'r-fork', ownerRouteId: 'r-source', inherited: true,
        nodeId: 'n1', selectedOptionId: null, freeText: 'source answer',
        createdAt: '2026-01-01T00:00:00Z',
      }],
    }))
    const store = useWorkspaceStore()

    await store.loadWorkspace('p1')

    expect(store.forkDraftRetryRouteId).toBe('r-fork')
  })

  it('reconciles a lost regenerate response after persistence without a second POST', async () => {
    const oldRoute = makeRoute({ id: 'r-old', projectId: 'p1', tipNodeId: 'n1', isActive: true })
    const replacementRoute = makeRoute({
      id: 'r-new', projectId: 'p1', tipNodeId: 'n2', isActive: true,
      branchType: 'regenerate', sourceRouteId: 'r-old', branchAtNodeId: 'n1',
      replacementOfNodeId: 'n1',
    })
    const initial = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r-old' }),
      activeRoute: oldRoute,
    })
    const after = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r-new' }),
      activeRoute: replacementRoute,
    })
    const initialGraph = makeGraphWorkspaceView({
      projectId: 'p1', activeRouteId: 'r-old',
      routes: [{ ...oldRoute, rootNodeId: 'n1', lineageNodeIds: ['n1'] }],
    })
    const afterGraph = makeGraphWorkspaceView({
      projectId: 'p1', activeRouteId: 'r-new',
      routes: [
        { ...oldRoute, rootNodeId: 'n1', lineageNodeIds: ['n1'] },
        { ...replacementRoute, rootNodeId: 'n2', lineageNodeIds: ['n2'] },
      ],
    })
    mockBackendViews(initial, makeRequirementState())
    mockedGetActiveState.mockResolvedValueOnce(initial).mockResolvedValueOnce(after)
    mockedGetProjectGraph.mockResolvedValueOnce(initialGraph).mockResolvedValueOnce(afterGraph)
    mockedRegenerateNode.mockRejectedValue(new ApiError('network lost', 'NETWORK_ERROR', 0))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.regenerateNode('n1', { sourceRouteId: 'r-old', instruction: 'new angle' })).toBe(true)

    expect(mockedRegenerateNode).toHaveBeenCalledTimes(1)
    expect(store.manualModelRetry).toBeNull()
    expect(store.consumeFocusAfterMutation()).toEqual({ routeId: 'r-new', nodeId: 'n2' })
  })

  it('allows exactly one explicit regenerate retry when reconciliation finds no new route', async () => {
    const oldRoute = makeRoute({ id: 'r-old', projectId: 'p1', tipNodeId: 'n1', isActive: true })
    const graph = makeGraphWorkspaceView({
      projectId: 'p1', activeRouteId: 'r-old',
      routes: [{ ...oldRoute, rootNodeId: 'n1', lineageNodeIds: ['n1'] }],
    })
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r-old' }),
      activeRoute: oldRoute,
    })
    mockBackendViews(active, makeRequirementState())
    mockedGetProjectGraph.mockResolvedValue(graph)
    mockedRegenerateNode.mockRejectedValueOnce(new ApiError('network lost', 'NETWORK_ERROR', 0))
    const regenerated = makeRegenerateResponse({
      oldRoute: makeRoute({ id: 'r-old', lifecycleStatus: 'superseded', isActive: false }),
      replacementRoute: makeRoute({ id: 'r-new', isActive: true, tipNodeId: 'n2' }),
      replacementNode: makeNode({ id: 'n2' }),
    })
    mockedRegenerateNode.mockResolvedValueOnce(regenerated)
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.regenerateNode('n1', { sourceRouteId: 'r-old' })).toBe(false)
    expect(store.manualModelRetry?.state).toBe('ready')
    expect(await store.retryManualModelOperation()).toBe(true)
    expect(mockedRegenerateNode).toHaveBeenCalledTimes(2)
  })

  it('fails closed on an incompatible regenerate active transition without mutation', async () => {
    const oldRoute = makeRoute({ id: 'r-old', projectId: 'p1', tipNodeId: 'n1', isActive: true })
    const replacementRoute = makeRoute({
      id: 'r-new', projectId: 'p1', tipNodeId: 'n2', isActive: false,
      branchType: 'regenerate', sourceRouteId: 'r-old', branchAtNodeId: 'n1',
      replacementOfNodeId: 'n1',
    })
    const initial = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r-old' }), activeRoute: oldRoute,
    })
    const after = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r-old' }), activeRoute: oldRoute,
    })
    mockBackendViews(initial, makeRequirementState())
    mockedGetActiveState.mockResolvedValueOnce(initial).mockResolvedValueOnce(after)
    mockedGetProjectGraph.mockResolvedValueOnce(makeGraphWorkspaceView({
      projectId: 'p1', activeRouteId: 'r-old',
      routes: [{ ...oldRoute, rootNodeId: 'n1', lineageNodeIds: ['n1'] }],
    })).mockResolvedValueOnce(makeGraphWorkspaceView({
      projectId: 'p1', activeRouteId: 'r-old',
      routes: [
        { ...oldRoute, rootNodeId: 'n1', lineageNodeIds: ['n1'] },
        { ...replacementRoute, rootNodeId: 'n2', lineageNodeIds: ['n2'] },
      ],
    }))
    mockedRegenerateNode.mockRejectedValue(new ApiError('network lost', 'NETWORK_ERROR', 0))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.regenerateNode('n1', { sourceRouteId: 'r-old' })).toBe(false)
    expect(store.manualModelRetry?.state).toBe('ambiguous')
    expect(await store.retryManualModelOperation()).toBe(false)
    expect(mockedRegenerateNode).toHaveBeenCalledTimes(1)
  })

  it('reconciles a persisted spec snapshot after a lost response without a second POST', async () => {
    const oldSnapshot = makeSpecSnapshot({ id: 'spec-old', routeId: 'r1' })
    const newSnapshot = makeSpecSnapshot({ id: 'spec-new', routeId: 'r1' })
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedListRouteSpecs.mockResolvedValueOnce([oldSnapshot]).mockResolvedValueOnce([oldSnapshot, newSnapshot])
    mockedApiGenerateSpec.mockRejectedValue(new ApiError('network lost', 'NETWORK_ERROR', 0))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.generateSpec()).toBeNull()
    expect(mockedApiGenerateSpec).toHaveBeenCalledTimes(1)
    expect(store.manualModelRetry).toBeNull()
    expect(store.selectedSpecIdByRoute['r1']).toBe('spec-new')
  })

  it('allows exactly one explicit spec retry when reconciliation finds no new snapshot', async () => {
    const oldSnapshot = makeSpecSnapshot({ id: 'spec-old', routeId: 'route-1' })
    const newSnapshot = makeSpecSnapshot({ id: 'spec-new', routeId: 'route-1' })
    mockBackendViews(makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'route-1' }),
      activeRoute: makeRoute({ id: 'route-1', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
    }), makeRequirementState())
    mockedListRouteSpecs
      .mockResolvedValueOnce([oldSnapshot])
      .mockResolvedValueOnce([oldSnapshot])
      .mockResolvedValueOnce([oldSnapshot])
      .mockResolvedValueOnce([oldSnapshot, newSnapshot])
    mockedApiGenerateSpec.mockRejectedValueOnce(new ApiError('network lost', 'NETWORK_ERROR', 0))
      .mockResolvedValueOnce(makeSpecGeneration({ specSnapshot: newSnapshot }))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.generateSpec()).toBeNull()
    expect(store.manualModelRetry?.state).toBe('ready')
    expect(await store.retryManualModelOperation()).toBe(true)
    expect(mockedApiGenerateSpec).toHaveBeenCalledTimes(2)
  })

  it('fails closed when more than one new spec snapshot appears during reconciliation', async () => {
    const oldSnapshot = makeSpecSnapshot({ id: 'spec-old', routeId: 'route-1' })
    const newSnapshotA = makeSpecSnapshot({ id: 'spec-new-a', routeId: 'route-1' })
    const newSnapshotB = makeSpecSnapshot({ id: 'spec-new-b', routeId: 'route-1' })
    mockBackendViews(makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'route-1' }),
      activeRoute: makeRoute({ id: 'route-1', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
    }), makeRequirementState())
    mockedListRouteSpecs.mockResolvedValueOnce([oldSnapshot]).mockResolvedValueOnce([
      oldSnapshot, newSnapshotA, newSnapshotB,
    ])
    mockedApiGenerateSpec.mockRejectedValue(new ApiError('network lost', 'NETWORK_ERROR', 0))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.generateSpec()).toBeNull()
    expect(store.manualModelRetry?.state).toBe('ambiguous')
    expect(await store.retryManualModelOperation()).toBe(false)
    expect(mockedApiGenerateSpec).toHaveBeenCalledTimes(1)
  })

  it('does not discard a failed answer payload while a mutation guard is active', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')
    store.resubmitAnswerPayload = { freeText: 'retry me' }
    store.submitting = true

    expect(await store.resubmitFailedAnswer()).toBe(false)
    expect(store.resubmitAnswerPayload).toEqual({ freeText: 'retry me' })
    expect(mockedCreateAgentRun).not.toHaveBeenCalled()
  })

  it('does not start spec generation when its baseline read fails', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'n1', isActive: true }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedListRouteSpecs.mockRejectedValue(new ApiError('read failed', 'NETWORK_ERROR', 0))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.generateSpec()).toBeNull()
    expect(mockedApiGenerateSpec).not.toHaveBeenCalled()
    expect(store.manualModelRetry).toBeNull()
  })

  it('does not create a model retry intent for deterministic non-model failures', async () => {
    mockBackendViews(makeActiveState({ activeNode: null }), makeRequirementState())
    mockedDraftNextQuestion.mockRejectedValue(new ApiError('invalid command', 'VALIDATION_ERROR', 422))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(await store.draftQuestion()).toBe(false)
    expect(store.manualModelRetry).toBeNull()
  })

  it('sends the explicit Re-answer source and refreshes canonical reads', async () => {
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', isActive: true }),
    })
    mockBackendViews(active, makeRequirementState())
    mockedReanswerNode.mockResolvedValue({
      projectId: 'p1',
      route: makeRoute({ id: 'reanswer', projectId: 'p1', isActive: true }),
      activeRouteId: 'reanswer',
    })
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const ok = await store.reanswerNode('node-1', 'r1', 'different answer')

    expect(ok).toBe(true)
    expect(mockedReanswerNode).toHaveBeenCalledWith('p1', 'node-1', {
      sourceRouteId: 'r1',
      label: 'different answer',
    })
    expect(mockedGetProjectGraph.mock.calls.length).toBeGreaterThan(1)
  })

  it('scopes the pending answer to the answering node while the run polls', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    let resolvePoll: (v: AgentRunView) => void = () => undefined
    mockedGetAgentRun.mockReturnValue(
      new Promise((resolve) => {
        resolvePoll = resolve
      }),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')
    const answeringNodeId = store.activeState?.activeNode?.id ?? null
    const graphReadsBeforeSubmit = mockedGetProjectGraph.mock.calls.length

    const pending = store.submitAnswer({ freeText: 'async answer' })

    await vi.waitFor(() => expect(store.answerRunId).toBe('run-1'))
    // While the run is in flight the UI is not globally frozen: a
    // node-scoped pending marker names exactly the node being answered.
    expect(store.submitting).toBe(true)
    expect(store.pendingAnswerNodeId).toBe(answeringNodeId)

    resolvePoll(completedRunView())
    expect(await pending).toBe(true)

    expect(store.submitting).toBe(false)
    // The canonical graph is re-read from the backend after the run
    // completes — never patched locally from optimistic state.
    expect(mockedGetProjectGraph.mock.calls.length).toBeGreaterThan(graphReadsBeforeSubmit)
  })

  it('stops observing the old project run after switching projects', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    let resolvePoll: (v: AgentRunView) => void = () => undefined
    mockedGetAgentRun.mockReturnValue(
      new Promise((resolve) => {
        resolvePoll = resolve
      }),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const pending = store.submitAnswer({ freeText: 'answer' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-1'))

    // Switching projects clears run observation state; the poll loop exits
    // without ever resolving the old run.
    await store.loadWorkspace('p2')
    expect(store.answerRunId).toBeNull()
    expect(store.pendingAnswerNodeId).toBeNull()

    resolvePoll(completedRunView())
    await pending
    expect(mockedGetAgentRun).toHaveBeenCalledTimes(1)
  })

  it('does not auto-resubmit after poll network errors; reconcile first', async () => {
    vi.useFakeTimers()
    try {
      const active = makeActiveState({
        project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
        activeRoute: makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'node-1', isActive: true }),
        activeNode: makeNode({ id: 'node-1', projectId: 'p1' }),
      })
      mockBackendViews(active, makeRequirementState())
      mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
        projectId: 'p1',
        activeRouteId: 'r1',
        routes: [{
          ...makeRoute({ id: 'r1', projectId: 'p1', tipNodeId: 'node-1', isActive: true }),
          rootNodeId: 'node-1',
          lineageNodeIds: ['node-1'],
        }],
        nodes: [makeNode({ id: 'node-1', projectId: 'p1' })],
        answers: [{
          id: 'answer-1', routeId: 'r1', nodeId: 'node-1', selectedOptionId: null,
          freeText: 'answer', createdAt: '2026-01-01T00:00:00Z',
          ownerRouteId: 'r1', inherited: false,
        }],
      }))
      mockedCreateAgentRun.mockResolvedValue({
        runId: 'run-1',
        operation: 'ANSWER_TIP',
        phase: 'CREATED',
      })
      // Every poll read fails (network loss after the run was created).
      mockedGetAgentRun.mockRejectedValue(new ApiError('network lost', 'NETWORK_ERROR', 0))
      const store = useWorkspaceStore()
      await store.loadWorkspace('p1')

      const pending = store.submitAnswer({ freeText: 'answer' })
      // Drive the full poll budget (120 attempts × 1.5s) instantly.
      await vi.runAllTimersAsync()
      await pending

      // The create-run call happened exactly once; no automatic second
      // mutation was issued despite the polls all failing. Canonical
      // reconciliation found the persisted Answer → repair affordance.
      expect(mockedCreateAgentRun).toHaveBeenCalledTimes(1)
      expect(store.resubmitAnswerPayload).toBeNull()
      expect(store.repairableAnswerId).toBe('answer-1')
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps draft input intact while a run is polling', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    let resolvePoll: (v: AgentRunView) => void = () => undefined
    mockedGetAgentRun.mockReturnValue(
      new Promise((resolve) => {
        resolvePoll = resolve
      }),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')
    const nodeId = store.activeState?.activeNode?.id ?? 'node-1'
    useInputDraftStore().setDraft('p1', nodeId, {
      selectedOptionId: null,
      freeText: 'draft being typed',
    })

    const pending = store.submitAnswer({ freeText: 'final answer' })
    await vi.waitFor(() => expect(store.answerRunId).toBe('run-1'))

    // Simulate a canonical refresh while the run is pending: the typed draft
    // must survive it.
    await store.refreshWorkspace()
    expect(useInputDraftStore().getDraft('p1', nodeId)?.freeText).toBe('draft being typed')

    resolvePoll(completedRunView())
    expect(await pending).toBe(true)
  })

  it('keeps the forked route and the source route visible immediately when the first draft fails', async () => {
    mockBackendViews(makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
      activeRoute: makeRoute({ id: 'r1', projectId: 'p1', isActive: true }),
    }), makeRequirementState())
    mockedForkNode.mockResolvedValue({
      projectId: 'p1',
      route: makeRoute({ id: 'forked', projectId: 'p1', isActive: true }),
      activeRouteId: 'forked',
    })
    mockedDraftNextQuestion.mockRejectedValue(new ApiError('draft failed', 'DRAFT_FAILED', 500))
    const forkedRoute = makeRoute({ id: 'forked', projectId: 'p1', isActive: true })
    // Canonical reads after the fork list both routes; the failed draft has
    // produced nothing yet (the forked route tip is still the branch point).
    mockedGetActiveState.mockResolvedValue(makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'forked' }),
      activeRoute: forkedRoute,
    }))
    mockedListRoutes.mockResolvedValue([
      makeRoute({ id: 'r1', projectId: 'p1', isActive: false }),
      forkedRoute,
    ])
    mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
      projectId: 'p1',
      activeRouteId: 'forked',
      routes: [
        {
          id: 'r1',
          label: 'Source',
          lifecycleStatus: 'open',
          isActive: false,
          rootNodeId: 'node-1',
          tipNodeId: 'node-1',
          createdFromNodeId: null,
          supersedesRouteId: null,
          replacementOfNodeId: null,
          lineageNodeIds: ['node-1'],
        },
        {
          id: 'forked',
          label: 'Branch',
          lifecycleStatus: 'open',
          isActive: true,
          rootNodeId: 'node-1',
          tipNodeId: 'node-1',
          createdFromNodeId: 'node-1',
          supersedesRouteId: null,
          replacementOfNodeId: null,
          lineageNodeIds: ['node-1'],
        },
      ],
    }))
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    await store.forkNode('node-1', 'r1', 'future branch')

    // The new route is immediately visible without waiting for any AI
    // draft, and the other routes stay visible too.
    const visibleIds = store.routes.map((route) => route.id)
    expect(visibleIds).toContain('forked')
    expect(visibleIds).toContain('r1')
    expect(store.graphView?.routes.map((route) => route.id)).toEqual(['r1', 'forked'])
    // The failed draft leaves an explicit retry affordance for the forked
    // route instead of dropping it.
    expect(store.forkDraftRetryRouteId).toBe('forked')
    expect(store.feedback).toContain('分支已创建')
  })
})
