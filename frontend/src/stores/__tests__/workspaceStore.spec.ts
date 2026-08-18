import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeAnswerExecution,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
  makeRouteLineage,
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
  submitAnswer: vi.fn(),
}))

vi.mock('@/api/requirementState', () => ({
  getRequirementState: vi.fn(),
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
import {
  draftNextQuestion,
  getActiveState,
  listRoutes,
  submitAnswer,
} from '@/api/workspace'
import { getRequirementState } from '@/api/requirementState'
import { getRouteLineage } from '@/api/routes'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedListRoutes = vi.mocked(listRoutes)
const mockedGetRequirementState = vi.mocked(getRequirementState)
const mockedDraftNextQuestion = vi.mocked(draftNextQuestion)
const mockedSubmitAnswer = vi.mocked(submitAnswer)
const mockedGetRouteLineage = vi.mocked(getRouteLineage)

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
    expect(store.feedback).toBe('Question drafted.')
  })

  it('submits an option-only answer payload exactly as selected', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedSubmitAnswer.mockResolvedValue(makeAnswerExecution())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const ok = await store.submitAnswer({ selectedOptionId: 'opt-a' })

    expect(ok).toBe(true)
    expect(mockedSubmitAnswer).toHaveBeenCalledWith('p1', { selectedOptionId: 'opt-a' })
  })

  it('submits a free-text-only answer payload', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedSubmitAnswer.mockResolvedValue(makeAnswerExecution())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    await store.submitAnswer({ freeText: 'We need a single-user tool' })

    expect(mockedSubmitAnswer).toHaveBeenCalledWith('p1', { freeText: 'We need a single-user tool' })
  })

  it('submits combined option + free-text payload without discarding either', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedSubmitAnswer.mockResolvedValue(makeAnswerExecution())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    await store.submitAnswer({ selectedOptionId: 'opt-a', freeText: 'explanation text' })

    expect(mockedSubmitAnswer).toHaveBeenCalledWith('p1', {
      selectedOptionId: 'opt-a',
      freeText: 'explanation text',
    })
  })

  it('prevents duplicate answer submission while the first is pending', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    let resolveSubmit: (v: ReturnType<typeof makeAnswerExecution>) => void = () => undefined
    mockedSubmitAnswer.mockReturnValue(
      new Promise((resolve) => {
        resolveSubmit = resolve
      }),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const first = store.submitAnswer({ freeText: 'first' })
    const second = store.submitAnswer({ freeText: 'second' })

    expect(mockedSubmitAnswer).toHaveBeenCalledTimes(1)
    resolveSubmit(makeAnswerExecution())
    await first
    await second
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
    mockedSubmitAnswer.mockResolvedValue(makeAnswerExecution())
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    expect(store.requirementState?.confirmed).toHaveLength(0)

    // The backend now reports the new state; the frontend must re-read it.
    mockedGetRequirementState.mockResolvedValue(after)
    mockedGetActiveState.mockResolvedValue(
      makeActiveState({ activeNode: makeNode({ question: 'Drafted next question' }) }),
    )

    await store.submitAnswer({ freeText: 'answer' })

    expect(mockedGetRequirementState).toHaveBeenCalledTimes(2)
    expect(store.requirementState?.confirmed[0].text).toBe('Backend-derived confirmed claim')
    expect(store.activeState?.activeNode?.question).toBe('Drafted next question')
    expect(store.feedback).toBe('Answer recorded.')
  })

  it('surfaces a provider-neutral rate-limit error safely', async () => {
    mockBackendViews(makeActiveState(), makeRequirementState())
    mockedSubmitAnswer.mockRejectedValue(
      new ApiError(
        'The model provider is temporarily rate limited',
        'MODEL_PROVIDER_RATE_LIMITED',
        429,
      ),
    )
    const store = useWorkspaceStore()
    await store.loadWorkspace('p1')

    const ok = await store.submitAnswer({ freeText: 'answer' })

    expect(ok).toBe(false)
    expect(store.error?.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
    expect(store.error?.message).toBe('The model provider is temporarily rate limited')
    expect(store.error?.message).not.toContain('provider raw payload')
    expect(store.submitting).toBe(false)
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
})