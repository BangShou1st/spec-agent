import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { ApiError } from '@/api/client'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeAnswerExecution,
  makeNode,
  makeRegenerateResponse,
  makeRequirementState,
  makeRoute,
  makeRouteLineage,
  makeSpecGeneration,
  makeSpecSnapshot,
} from '@/test/fixtures'
import type { ActiveProjectStateResponse, RequirementStateView } from '@/api/types'

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
import {
  activateRoute as apiActivateRoute,
  forkNode as apiForkNode,
  getRouteLineage,
  regenerateNode as apiRegenerateNode,
} from '@/api/routes'
import { generateSpec as apiGenerateSpec, listRouteSpecs } from '@/api/spec'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedListRoutes = vi.mocked(listRoutes)
const mockedGetRequirementState = vi.mocked(getRequirementState)
const mockedDraftNextQuestion = vi.mocked(draftNextQuestion)
const mockedSubmitAnswer = vi.mocked(submitAnswer)

function mockViews(active: ActiveProjectStateResponse, state: RequirementStateView): void {
  mockedGetProject.mockResolvedValue({ ...active.project })
  mockedGetActiveState.mockResolvedValue(active)
  mockedListRoutes.mockResolvedValue(active.activeRoute ? [active.activeRoute] : [])
  mockedGetRequirementState.mockResolvedValue(state)
  vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
}

async function mountWorkspace(projectId = 'p1') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(WorkspaceView, {
    props: { projectId },
    global: { plugins: [pinia] },
  })
  await flushPromises()
  return { wrapper, store: useWorkspaceStore() }
}

describe('WorkspaceView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads the workspace into the three panels', async () => {
    const active = makeActiveState()
    mockViews(active, makeRequirementState())
    const { wrapper } = await mountWorkspace()

    expect(wrapper.text()).toContain('Route Workspace')
    expect(wrapper.text()).toContain('Clarification')
    expect(wrapper.text()).toContain('Requirement State')
    expect(wrapper.text()).toContain(active.project.title)
    expect(wrapper.find('[data-test="question"]').text()).toBe(active.activeNode?.question)
    expect(wrapper.text()).toContain('Confirmed')
  })

  it('shows the explicit draft button and does not auto-draft on open', async () => {
    mockViews(makeActiveState({ activeNode: null }), makeRequirementState())
    const { wrapper } = await mountWorkspace()

    expect(mockedDraftNextQuestion).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Draft first question')
  })

  it('drafts the first question only on button click and refreshes the node', async () => {
    const drafted = makeNode({ question: 'What is the most important outcome?' })
    mockedGetProject.mockResolvedValue(makeActiveState().project)
    mockedGetActiveState
      .mockResolvedValueOnce(makeActiveState({ activeNode: null }))
      .mockResolvedValueOnce(makeActiveState({ activeNode: drafted }))
    mockedListRoutes.mockResolvedValue([makeRoute({ isActive: true })])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState())
    mockedDraftNextQuestion.mockResolvedValue({
      agentRun: makeAnswerExecution().agentRun,
      producedNode: drafted,
    })
    const { wrapper } = await mountWorkspace()
    expect(wrapper.find('[data-test="question"]').exists()).toBe(false)

    await wrapper.find('[data-test="draft-question"]').trigger('click')
    await flushPromises()

    expect(mockedDraftNextQuestion).toHaveBeenCalledWith('p1')
    expect(wrapper.find('[data-test="question"]').text()).toBe('What is the most important outcome?')
    expect(wrapper.text()).toContain('Question drafted.')
  })

  it('submits an option-only answer through the workspace flow and refreshes state', async () => {
    const option = { id: 'opt-a', label: 'Small scope', impact: 'Limits scope' }
    mockViews(
      makeActiveState({ activeNode: makeNode({ options: [option] }) }),
      makeRequirementState({ confirmed: [] }),
    )
    mockedSubmitAnswer.mockResolvedValue(makeAnswerExecution())
    const refreshed = makeRequirementState({
      confirmed: [
        {
          kind: 'goal',
          text: 'Backend-refreshed confirmed claim',
          status: 'confirmed',
          confidence: 0.9,
          sourceNodeId: 'node-1',
          sourceAnswerId: 'answer-1',
        },
      ],
    })
    mockedGetRequirementState
      .mockResolvedValueOnce(makeRequirementState({ confirmed: [] }))
      .mockResolvedValueOnce(refreshed)
    const { wrapper } = await mountWorkspace()

    await wrapper.find('input[type="radio"][value="opt-a"]').setValue()
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    await flushPromises()

    expect(mockedSubmitAnswer).toHaveBeenCalledWith('p1', { selectedOptionId: 'opt-a' })
    expect(mockedGetRequirementState).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Backend-refreshed confirmed claim')
    expect(wrapper.text()).toContain('Answer recorded.')
  })

  it('shows an error banner with only the safe backend message', async () => {
    mockedGetProject.mockResolvedValue(makeActiveState().project)
    mockedGetActiveState.mockRejectedValue(
      new ApiError(
        'The model provider returned an internal error',
        'MODEL_PROVIDER_ERROR',
        502,
      ),
    )
    mockedListRoutes.mockResolvedValue([])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState())
    const { wrapper } = await mountWorkspace()

    expect(wrapper.text()).toContain('MODEL_PROVIDER_ERROR')
    expect(wrapper.text()).toContain('The model provider returned an internal error')
    expect(wrapper.text()).not.toContain('raw provider payload')
    expect(wrapper.text()).toContain('Retry')
  })

  it('keeps OPEN routes visible without treating them as active', async () => {
    const active = makeActiveState()
    const sibling = makeRoute({ id: 'r-sibling', lifecycleStatus: 'open', isActive: false })
    mockedGetProject.mockResolvedValue(active.project)
    mockedGetActiveState.mockResolvedValue(active)
    mockedListRoutes.mockResolvedValue([sibling, active.activeRoute as never])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState())
    vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
    const { wrapper } = await mountWorkspace()

    expect(wrapper.findAll('[data-test="active-route"]')).toHaveLength(1)
  })

  it('inspects a historical node and returns to the active question', async () => {
    const active = makeActiveState()
    mockViews(active, makeRequirementState())
    const lineage = makeRouteLineage({ routeId: active.activeRoute?.id as string })
    vi.mocked(getRouteLineage).mockResolvedValue(lineage)
    const { wrapper } = await mountWorkspace()
    await flushPromises()

    await wrapper.findAll('[data-test="lineage-node"]')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="historical-question"]').text()).toBe('Child question')

    await wrapper.find('[data-test="back-to-active"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="question"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="historical-question"]').exists()).toBe(false)
  })

  it('activate runs through the route command and refreshes the workspace', async () => {
    const active = makeActiveState()
    const sibling = makeRoute({ id: 'r-sibling', lifecycleStatus: 'open', isActive: false })
    mockViews(active, makeRequirementState())
    vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
    vi.mocked(apiActivateRoute).mockResolvedValue({
      projectId: 'p1',
      route: sibling,
      activeRouteId: 'r-sibling',
    })
    const { wrapper, store } = await mountWorkspace()

    // Add an OPEN non-active route through the canonical store read.
    store.routes = [sibling, active.activeRoute as never]
    await nextTick()
    await flushPromises()

    await wrapper.find('[data-test="activate-route"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(apiActivateRoute)).toHaveBeenCalledWith('p1', 'r-sibling')
  })

  it('archive requires an explicit confirmation dialog', async () => {
    const active = makeActiveState()
    mockViews(active, makeRequirementState())
    vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
    const { wrapper } = await mountWorkspace()

    await wrapper.find('[data-test="archive-route"]').trigger('click')
    expect(wrapper.find('[data-test="confirm-route-action-dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Archive route?')

    // Cancel leaves the route untouched.
    await wrapper.find('[data-test="cancel-route-action"]').trigger('click')
    expect(wrapper.find('[data-test="confirm-route-action-dialog"]').exists()).toBe(false)
  })

  it('delete requires an explicit confirmation and states soft-delete semantics', async () => {
    const active = makeActiveState()
    mockViews(active, makeRequirementState())
    vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
    const { wrapper } = await mountWorkspace()

    await wrapper.find('[data-test="delete-route"]').trigger('click')
    expect(wrapper.find('[data-test="confirm-route-action-dialog"]').exists()).toBe(true)
    const description = wrapper.find('[data-test="confirm-description"]').text()
    expect(description.toLowerCase()).toContain('soft-delete')
  })

  it('opens the fork dialog from a historical node and submits label only', async () => {
    const active = makeActiveState()
    mockViews(active, makeRequirementState())
    vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
    vi.mocked(apiForkNode).mockResolvedValue({
      projectId: 'p1',
      route: makeRoute({ id: 'route-fork', isActive: true }),
      activeRouteId: 'route-fork',
    })
    const { wrapper } = await mountWorkspace()
    await flushPromises()

    await wrapper.findAll('[data-test="lineage-node"]')[1].trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="fork-from-here"]').trigger('click')
    expect(wrapper.find('[data-test="fork-dialog"]').exists()).toBe(true)

    await wrapper.find('[data-test="fork-label"]').setValue('Alternative route')
    await wrapper.find('[data-test="fork-submit"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(apiForkNode)).toHaveBeenCalledWith('p1', 'lnode-2', { label: 'Alternative route' })
  })

  it('opens the regenerate dialog from a historical node and submits a runtime-free payload', async () => {
    const active = makeActiveState()
    mockViews(active, makeRequirementState())
    vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
    vi.mocked(apiRegenerateNode).mockResolvedValue(makeRegenerateResponse())
    const { wrapper } = await mountWorkspace()
    await flushPromises()

    await wrapper.findAll('[data-test="lineage-node"]')[1].trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="regenerate-this-question"]').trigger('click')
    expect(wrapper.find('[data-test="regenerate-dialog"]').exists()).toBe(true)

    await wrapper.find('[data-test="regenerate-submit"]').trigger('click')
    await flushPromises()

    const payload = vi.mocked(apiRegenerateNode).mock.calls[0][2]
    expect(payload.replacementQuestion).toBe('Child question')
    expect(payload.replacementOptions?.[0]).not.toHaveProperty('id')
    expect(Object.keys(payload).sort()).toEqual([
      'instruction',
      'replacementOptions',
      'replacementPurpose',
      'replacementQuestion',
    ])
  })

  it('switches to the Spec Snapshots tab and generates for the active route', async () => {
    const active = makeActiveState({
      activeRoute: makeRoute({ id: 'r1', isActive: true, tipNodeId: 'lnode-2' }),
    })
    mockViews(active, makeRequirementState())
    vi.mocked(getRouteLineage).mockResolvedValue(makeRouteLineage())
    vi.mocked(listRouteSpecs).mockResolvedValue([])
    vi.mocked(apiGenerateSpec).mockResolvedValue(
      makeSpecGeneration({ specSnapshot: makeSpecSnapshot({ id: 'spec-1', routeId: 'r1' }) }),
    )
    const { wrapper } = await mountWorkspace()

    await wrapper.find('[data-test="tab-spec"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="generate-spec"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Generate spec for active route')

    vi.mocked(listRouteSpecs).mockResolvedValue([makeSpecSnapshot({ id: 'spec-1', routeId: 'r1' })])
    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(apiGenerateSpec)).toHaveBeenCalledWith('p1')
    expect(wrapper.find('[data-test="spec-snapshot-detail"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="derived-label"]').exists()).toBe(true)
  })
})