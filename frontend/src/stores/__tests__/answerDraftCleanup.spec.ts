import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { useInputDraftStore } from '@/stores/inputDraftStore'
import {
  makeActiveState,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
} from '@/test/fixtures'
import type { AgentRunView } from '@/api/agentRuns'

vi.mock('@/api/projects', () => ({ getProject: vi.fn() }))
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
vi.mock('@/api/graph', () => ({ getProjectGraph: vi.fn() }))
vi.mock('@/api/routes', () => ({
  activateRoute: vi.fn(),
  archiveRoute: vi.fn(),
  deleteRoute: vi.fn(),
  forkNode: vi.fn(),
  reanswerNode: vi.fn(),
  restoreRoute: vi.fn(),
}))
vi.mock('@/api/spec', () => ({ generateSpec: vi.fn(), listRouteSpecs: vi.fn() }))

import { createAgentRun, getAgentRun } from '@/api/agentRuns'
import type { Mock } from 'vitest'

const mockedCreateAgentRun = createAgentRun as unknown as Mock
const mockedGetAgentRun = getAgentRun as unknown as Mock

function completedRun(overrides: Partial<AgentRunView> = {}): AgentRunView {
  return {
    runId: 'run-1',
    projectId: 'p1',
    routeId: 'r-old',
    operation: 'ANSWER_TIP',
    status: 'completed',
    phase: 'COMPLETED',
    producedNodeId: 'q4-next',
    producedAnswerId: 'answer-1',
    producedPatchId: 'patch-1',
    producedSpecSnapshotId: null,
    ...overrides,
  }
}

describe('answer draft cleanup identity', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('answerSuccessClearsDraftForAnsweredNodeNotProducedNode', async () => {
    const route = makeRoute({ id: 'r-old', projectId: 'p1', tipNodeId: 'q3' })
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: route.id }),
      activeRoute: route,
      activeNode: makeNode({ id: 'q3', projectId: 'p1' }),
    })
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(completedRun())

    const store = useWorkspaceStore()
    const { getProject } = await import('@/api/projects')
    const { getRequirementState } = await import('@/api/requirementState')
    const { getActiveState, listRoutes } = await import('@/api/workspace')
    const { getProjectGraph } = await import('@/api/graph')
    ;(getProject as unknown as Mock).mockResolvedValue(makeProject({ id: "p1", activeRouteId: route.id }))
    ;(getActiveState as unknown as Mock).mockResolvedValue(active)
    ;(listRoutes as unknown as Mock).mockResolvedValue([route])
    ;(getRequirementState as unknown as Mock).mockResolvedValue(makeRequirementState())
    ;(getProjectGraph as unknown as Mock).mockResolvedValue({ projectId: "p1", activeRouteId: route.id, routes: [], nodes: [], answers: [], relations: [] })
    await store.loadWorkspace('p1')

    // Drafts exist for BOTH the answered node (Q3) and the produced node (Q4).
    const drafts = useInputDraftStore()
    drafts.setDraft('p1', 'q3', { selectedOptionId: null, freeText: 'my Q3 answer' }, 'r-old')
    drafts.setDraft('p1', 'q4-next', { selectedOptionId: null, freeText: 'unrelated Q4 draft' }, 'r-new')

    // After the run completes the runtime has moved to a new route; the
    // refresh returns that new canonical state.
    ;(getActiveState as unknown as Mock).mockResolvedValue(
      makeActiveState({
        project: makeProject({ id: 'p1', activeRouteId: 'r-new' }),
        activeRoute: makeRoute({ id: 'r-new', projectId: 'p1', isActive: true }),
      }),
    )

    const ok = await store.submitAnswer({ freeText: 'final answer' })

    expect(ok).toBe(true)
    expect(drafts.getDraft('p1', 'q3', 'r-old')).toBeUndefined()
    expect(drafts.getDraft('p1', 'q4-next', 'r-new')?.freeText).toBe('unrelated Q4 draft')
  })

  it('answerDraftCleanupUsesSubmissionRouteIdentity', async () => {
    const submissionRoute = makeRoute({ id: 'route-at-submit', projectId: 'p1', tipNodeId: 'n1' })
    const active = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: submissionRoute.id }),
      activeRoute: submissionRoute,
      activeNode: makeNode({ id: 'n1', projectId: 'p1' }),
    })
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-2',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue(
      completedRun({ routeId: 'replacement-route', producedNodeId: null }),
    )

    const store = useWorkspaceStore()
    const { getProject } = await import('@/api/projects')
    const { getRequirementState } = await import('@/api/requirementState')
    const { getActiveState, listRoutes } = await import('@/api/workspace')
    const { getProjectGraph } = await import('@/api/graph')
    ;(getProject as unknown as Mock).mockResolvedValue(makeProject({ id: "p1", activeRouteId: submissionRoute.id }))
    ;(getActiveState as unknown as Mock).mockResolvedValue(active)
    ;(listRoutes as unknown as Mock).mockResolvedValue([submissionRoute])
    ;(getRequirementState as unknown as Mock).mockResolvedValue(makeRequirementState())
    ;(getProjectGraph as unknown as Mock).mockResolvedValue({ projectId: "p1", activeRouteId: submissionRoute.id, routes: [], nodes: [], answers: [], relations: [] })
    await store.loadWorkspace('p1')

    const drafts = useInputDraftStore()
    drafts.setDraft('p1', 'n1', { selectedOptionId: null, freeText: 'typed answer' }, 'route-at-submit')

    // The runtime replaced the route during the run; the post-refresh state
    // points at the replacement route — cleanup must still use the route
    // captured at SUBMISSION time.
    const replacementRoute = makeRoute({ id: 'replacement-route', projectId: 'p1', isActive: true })
    ;(getActiveState as unknown as Mock).mockResolvedValue(
      makeActiveState({
        project: makeProject({ id: 'p1', activeRouteId: 'replacement-route' }),
        activeRoute: replacementRoute,
      }),
    )

    const ok = await store.submitAnswer({ freeText: 'final answer' })

    expect(ok).toBe(true)
    expect(drafts.getDraft('p1', 'n1', 'route-at-submit')).toBeUndefined()
  })
})
