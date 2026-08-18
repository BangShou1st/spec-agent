import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { ApiError } from '@/api/client'
import {
  makeActiveState,
  makeAnswerExecution,
  makeNode,
  makeRequirementState,
  makeRoute,
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

import { getProject } from '@/api/projects'
import {
  draftNextQuestion,
  getActiveState,
  listRoutes,
  submitAnswer,
} from '@/api/workspace'
import { getRequirementState } from '@/api/requirementState'

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
}

async function mountWorkspace(projectId = 'p1') {
  const wrapper = mount(WorkspaceView, {
    props: { projectId },
    global: { plugins: [createPinia()] },
  })
  await flushPromises()
  return wrapper
}

describe('WorkspaceView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads the workspace into the three panels', async () => {
    const active = makeActiveState()
    mockViews(active, makeRequirementState())
    const wrapper = await mountWorkspace()

    expect(wrapper.text()).toContain('Route / Project')
    expect(wrapper.text()).toContain('Clarification')
    expect(wrapper.text()).toContain('Requirement State')
    expect(wrapper.text()).toContain(active.project.title)
    expect(wrapper.find('[data-test="question"]').text()).toBe(active.activeNode?.question)
    expect(wrapper.text()).toContain('Confirmed')
  })

  it('shows the explicit draft button and does not auto-draft on open', async () => {
    mockViews(makeActiveState({ activeNode: null }), makeRequirementState())
    const wrapper = await mountWorkspace()

    expect(mockedDraftNextQuestion).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Draft first question')
  })

  it('drafts the first question only on button click and refreshes the node', async () => {
    const drafted = makeNode({ question: 'What is the most important outcome?' })
    mockedGetProject.mockResolvedValue(makeActiveState().project)
    // Initial read: active route with no tip node. Post-draft refresh: new tip.
    mockedGetActiveState
      .mockResolvedValueOnce(makeActiveState({ activeNode: null }))
      .mockResolvedValueOnce(makeActiveState({ activeNode: drafted }))
    mockedListRoutes.mockResolvedValue([makeRoute({ isActive: true })])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState())
    mockedDraftNextQuestion.mockResolvedValue({
      agentRun: makeAnswerExecution().agentRun,
      producedNode: drafted,
    })
    const wrapper = await mountWorkspace()
    expect(wrapper.find('[data-test="question"]').exists()).toBe(false)

    await wrapper.find('button').trigger('click')
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
    // First read serves the initial state; the post-command refresh serves the
    // backend-derived state. The frontend must not build this locally.
    mockedGetRequirementState
      .mockResolvedValueOnce(makeRequirementState({ confirmed: [] }))
      .mockResolvedValueOnce(refreshed)
    const wrapper = await mountWorkspace()

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
    const wrapper = await mountWorkspace()

    expect(wrapper.text()).toContain('MODEL_PROVIDER_ERROR')
    expect(wrapper.text()).toContain('The model provider returned an internal error')
    expect(wrapper.text()).not.toContain('raw provider payload')
    // The workspace still offers a retry instead of a blank page.
    expect(wrapper.text()).toContain('Retry')
  })

  it('keeps OPEN routes visible without treating them as active', async () => {
    const active = makeActiveState()
    const sibling = makeRoute({ id: 'r-sibling', lifecycleStatus: 'open', isActive: false })
    mockedGetProject.mockResolvedValue(active.project)
    mockedGetActiveState.mockResolvedValue(active)
    mockedListRoutes.mockResolvedValue([sibling, active.activeRoute as never])
    mockedGetRequirementState.mockResolvedValue(makeRequirementState())
    const wrapper = await mountWorkspace()

    expect(wrapper.findAll('[data-test="active-route"]')).toHaveLength(1)
  })
})