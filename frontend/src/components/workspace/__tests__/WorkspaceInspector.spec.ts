import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WorkspaceInspector from '@/components/workspace/WorkspaceInspector.vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { useGraphUiStore } from '@/stores/graphUiStore'
import {
  makeActiveState,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRequirementState,
  makeRoute,
} from '@/test/fixtures'

vi.mock('@/api/projects', () => ({ getProject: vi.fn() }))
vi.mock('@/api/workspace', () => ({
  draftNextQuestion: vi.fn(),
  getActiveState: vi.fn(),
  listRoutes: vi.fn(),
  submitAnswer: vi.fn(),
}))
vi.mock('@/api/requirementState', () => ({
  getRequirementState: vi.fn(),
  getRouteRequirementState: vi.fn(),
}))
vi.mock('@/api/graph', () => ({ getProjectGraph: vi.fn() }))
vi.mock('@/api/graphCommands', () => ({
  acceptProposal: vi.fn(),
  appendContinuation: vi.fn(),
  attachResource: vi.fn(),
  createFloatingDraftNode: vi.fn(),
  createNodeQuery: vi.fn(),
  createRelation: vi.fn(),
  getNodeQueryResult: vi.fn(),
  getUndoRedoAvailability: vi.fn(),
  listProposals: vi.fn().mockResolvedValue([]),
  redoGraphOperation: vi.fn(),
  rejectProposal: vi.fn(),
  reviseDraftNode: vi.fn(),
  setKnowledgeStatus: vi.fn(),
  undoGraphOperation: vi.fn(),
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
vi.mock('@/api/spec', () => ({ generateSpec: vi.fn(), listRouteSpecs: vi.fn() }))

import { getProject } from '@/api/projects'
import { getActiveState, listRoutes } from '@/api/workspace'
import { getRequirementState, getRouteRequirementState } from '@/api/requirementState'
import { getProjectGraph } from '@/api/graph'
import { listRouteSpecs } from '@/api/spec'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedListRoutes = vi.mocked(listRoutes)
const mockedGetRequirementState = vi.mocked(getRequirementState)
const mockedGetRouteRequirementState = vi.mocked(getRouteRequirementState)
const mockedGetProjectGraph = vi.mocked(getProjectGraph)
const mockedListRouteSpecs = vi.mocked(listRouteSpecs)

function nodeData(overrides: Record<string, unknown> = {}) {
  return {
    node: makeNode({ id: 'n1', question: 'Selection question' }),
    canonicalNodeId: 'n1',
    routeIds: ['rA'],
    visibleRouteIds: ['rA'],
    answers: [],
    routeStates: [{ routeId: 'rA', answer: null }],
    primaryAnswer: null,
    answerPresentationMode: 'single-route' as const,
    readingRouteId: 'rA',
    isCurrent: false,
    canAnswer: false,
    isExpanded: false,
    isShared: false,
    projectId: 'project-1',
    isLatest: false,
    qLabel: null,
    visualWeight: 'normal' as const,
    ...overrides,
  }
}

async function loadStore(active = makeActiveState()) {
  const store = useWorkspaceStore()
  mockedGetProject.mockResolvedValue(active.project)
  mockedGetActiveState.mockResolvedValue(active)
  mockedListRoutes.mockResolvedValue(active.activeRoute ? [active.activeRoute] : [])
  mockedGetRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'rA' }))
  mockedGetProjectGraph.mockResolvedValue(
    makeGraphWorkspaceView({ activeRouteId: active.activeRoute?.id ?? null }),
  )
  await store.loadWorkspace('p1')
  return store
}

describe('workspace inspector contextual surface', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    useGraphUiStore().initProject('p1')
  })

  it('with no focus there is no implicit reading route', async () => {
    const store = await loadStore()
    const ui = useGraphUiStore()
    expect(ui.readingRouteId(store.activeRoute?.id ?? null)).toBeNull()
  })

  it('Active=A + Focus=B reads requirement state for B only', async () => {
    const activeA = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'rA' }),
      activeRoute: makeRoute({ id: 'rA', isActive: true }),
      activeNode: makeNode({ id: 'nA' }),
    })
    const store = await loadStore(activeA)
    const ui = useGraphUiStore()
    ui.setFocusRoute('rB')
    mockedGetRouteRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'rB' }))

    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(mockedGetRouteRequirementState).toHaveBeenCalledWith('p1', 'rB')
    expect(store.requirementStatesByRoute.rB?.routeId).toBe('rB')
  })

  it('no selection shows the project summary without top-level tabs', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    expect(wrapper.find('[data-test="inspector-tabs"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="tab-details"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="tab-requirement"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="tab-spec"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="project-summary"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="spec-snapshot-panel"]').exists()).toBe(false)
  })

  it('a selected node shows the node inspector, not the summary', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, { props: { nodeData: nodeData() } })
    expect(wrapper.find('[data-test="node-inspector"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="project-summary"]').exists()).toBe(false)
  })

  it('a selected edge shows the edge view with readable route members', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, {
      props: {
        nodeData: null,
        selectedEdge: { id: 'lineage:x', kind: 'lineage', relationType: null, routeIds: [] },
      },
    })
    expect(wrapper.find('[data-test="edge-inspector"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="project-summary"]').exists()).toBe(false)
  })

  it('project summary opens the requirement detail view and returns', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    await wrapper.find('[data-test="open-requirements"]').trigger('click')
    expect(wrapper.find('[data-test="requirement-detail"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="project-summary"]').exists()).toBe(false)
    await wrapper.find('[data-test="requirement-back"]').trigger('click')
    expect(wrapper.find('[data-test="project-summary"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="requirement-detail"]').exists()).toBe(false)
  })

  it('selecting a node resets the secondary requirements view', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    await wrapper.find('[data-test="open-requirements"]').trigger('click')
    expect(wrapper.find('[data-test="requirement-detail"]').exists()).toBe(true)
    await wrapper.setProps({ nodeData: nodeData() })
    expect(wrapper.find('[data-test="node-inspector"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="requirement-detail"]').exists()).toBe(false)
  })

  it('inspector shows the canonical Answer once and keeps readable membership', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, {
      props: {
        nodeData: nodeData({
          node: makeNode({ id: 'n1', question: 'Shared node question' }),
          routeIds: ['rA', 'rB'],
          visibleRouteIds: ['rA', 'rB'],
          answers: [
            { routeId: 'rA', selectedOptionId: null, selectedOptionLabel: null, freeText: 'A answer', isPrimary: true },
          ],
          routeStates: [
            { routeId: 'rA', answer: { routeId: 'rA', selectedOptionId: null, selectedOptionLabel: null, freeText: 'A answer', isPrimary: true } },
            { routeId: 'rB', answer: null },
          ],
          primaryAnswer: {
            routeId: 'rA', selectedOptionId: null, selectedOptionLabel: null, freeText: 'A answer', isPrimary: true,
          },
          answerPresentationMode: 'focused' as const,
          readingRouteId: 'rB',
          isShared: true,
          visualWeight: 'focus' as const,
        }),
      },
    })
    expect(wrapper.find('[data-test="canonical-answer"]').text()).toContain('A answer')
    expect(wrapper.find('[data-test="route-answer-rA"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="route-answer-rB"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="route-waiting"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('路线归属')
  })

  it('current pending node keeps details but offers no fork or regenerate', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, {
      props: {
        nodeData: nodeData({
          node: makeNode({ id: 'nC', question: 'Current pending question' }),
          isCurrent: true,
          canAnswer: true,
          visualWeight: 'active' as const,
        }),
      },
    })
    expect(wrapper.find('[data-test="node-inspector"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="node-detail-question"]').text()).toContain('Current pending question')
    expect(wrapper.find('[data-test="node-detail-no-answer"]').text()).toContain('还没有回答')
    expect(wrapper.find('[data-test="inspector-fork"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="inspector-regenerate"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="submit-answer"]').exists()).toBe(false)
  })

  it('historical nodes offer fork and regenerate from the inspector', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, {
      props: {
        nodeData: nodeData({ node: makeNode({ id: 'nOld', question: 'Historical question' }) }),
      },
    })
    expect(wrapper.find('[data-test="inspector-fork"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="inspector-regenerate"]').exists()).toBe(true)
  })

  it('never renders spec content inside the inspector', async () => {
    await loadStore()
    mockedListRouteSpecs.mockResolvedValue([])
    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-test="spec-snapshot-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="generate-spec"]').exists()).toBe(false)
    expect(mockedListRouteSpecs).not.toHaveBeenCalled()
  })
})
