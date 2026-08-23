import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeGraphWorkspaceView,
  makeNode,
  makeProject,
  makeRegenerateResponse,
  makeRequirementState,
  makeRoute,
  makeSpecGeneration,
  makeSpecSnapshot,
} from '@/test/fixtures'
import type { GraphWorkspaceView } from '@/api/types'

vi.mock('@/api/projects', () => ({ getProject: vi.fn() }))
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
vi.mock('@/api/graph', () => ({ getProjectGraph: vi.fn() }))
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
import { getActiveState, listRoutes, draftNextQuestion } from '@/api/workspace'
import { createAgentRun, getAgentRun } from '@/api/agentRuns'
import { getRequirementState, getRouteRequirementState } from '@/api/requirementState'
import { getProjectGraph } from '@/api/graph'
import {
  activateRoute as apiActivateRoute,
  forkNode as apiForkNode,
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

/**
 * GraphCanvas stub: real Vue Flow cannot render in jsdom; the shell tests
 * cover wiring while GraphCanvas.spec covers canvas behavior itself.
 */
const locateSpy = vi.fn()
const GraphCanvasStub = defineComponent({
  name: 'GraphCanvas',
  props: {
    view: { type: Object, default: null },
    activeNodeId: { type: String, default: null },
    submitting: Boolean,
    drafting: Boolean,
    pending: Boolean,
  },
  emits: ['draft', 'submit-answer', 'fork', 'regenerate'],
  setup(_props, { expose }) {
    expose({ locateRoute: locateSpy, locateNode: vi.fn() })
    return () => h('div', { 'data-test': 'graph-canvas-stub' })
  },
})

function graphView(): GraphWorkspaceView {
  return makeGraphWorkspaceView({
    projectId: 'p1',
    activeRouteId: 'r1',
    routes: [
      {
        id: 'r1',
        label: '当前路线',
        lifecycleStatus: 'open',
        isActive: true,
        rootNodeId: 'n1',
        tipNodeId: 'n2',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1', 'n2'],
      },
      {
        id: 'r2',
        label: '开放分支',
        lifecycleStatus: 'open',
        isActive: false,
        rootNodeId: 'n1',
        tipNodeId: 'n3',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1', 'n3'],
      },
      {
        id: 'r3',
        label: '旧路线',
        lifecycleStatus: 'archived',
        isActive: false,
        rootNodeId: 'n1',
        tipNodeId: 'n4',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1', 'n4'],
      },
    ],
    nodes: [
      makeNode({ id: 'n1', projectId: 'p1', question: 'What outcome matters most?' }),
      makeNode({ id: 'n2', projectId: 'p1', parentNodeId: 'n1', question: 'Scope question' }),
      makeNode({ id: 'n3', projectId: 'p1', parentNodeId: 'n1', question: 'Fork question' }),
      makeNode({ id: 'n4', projectId: 'p1', parentNodeId: 'n1', question: 'Old question' }),
    ],
    answers: [
      {
        id: 'a1',
        routeId: 'r1',
        nodeId: 'n1',
        selectedOptionId: null,
        freeText: 'confirmed answer',
        createdAt: '2026-01-01T00:00:00Z',
      },
    ],
  })
}

function mockViews() {
  const active = makeActiveState({
    project: makeProject({ id: 'p1', activeRouteId: 'r1' }),
    activeRoute: makeRoute({ id: 'r1', isActive: true, tipNodeId: 'n2' }),
    activeNode: makeNode({ id: 'n2' }),
  })
  mockedGetProject.mockResolvedValue(active.project)
  mockedGetActiveState.mockResolvedValue(active)
  mockedListRoutes.mockResolvedValue([active.activeRoute as never])
  mockedGetRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'r1' }))
  mockedGetRouteRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'r1' }))
  mockedGetProjectGraph.mockResolvedValue(graphView())
  return active
}

async function mountWorkspace(projectId = 'p1') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(WorkspaceView, {
    props: { projectId },
    global: {
      plugins: [pinia],
      stubs: { GraphCanvas: GraphCanvasStub },
    },
  })
  await flushPromises()
  return { wrapper, store: useWorkspaceStore(), graphUi: useGraphUiStore() }
}

describe('WorkspaceView graph shell', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    locateSpy.mockReset()
  })

  it('loads the graph-first shell with floating windows and canvas', async () => {
    mockViews()
    const { wrapper } = await mountWorkspace()
    expect(wrapper.find('[data-test="graph-canvas-stub"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="floating-window-routes"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="floating-window-inspector"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="route-navigator"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="workspace-inspector"]').exists()).toBe(true)
  })

  it('drafts through the canvas draft intent', async () => {
    mockViews()
    mockedDraftNextQuestion.mockResolvedValue({
      agentRun: { id: 'run-1' } as never,
      producedNode: makeNode({ id: 'n5' }),
    })
    const { wrapper } = await mountWorkspace()
    await wrapper.findComponent(GraphCanvasStub).vm.$emit('draft')
    await flushPromises()
    expect(mockedDraftNextQuestion).toHaveBeenCalledWith('p1')
    expect(useWorkspaceStore().feedback).toBe('问题已起草。')
  })

  it('submits answers through the canvas submit intent as an async run', async () => {
    mockViews()
    mockedCreateAgentRun.mockResolvedValue({
      runId: 'run-1',
      operation: 'ANSWER_TIP',
      phase: 'CREATED',
    })
    mockedGetAgentRun.mockResolvedValue({
      runId: 'run-1',
      projectId: 'p1',
      routeId: 'r1',
      operation: 'ANSWER_TIP',
      status: 'completed',
      phase: 'COMPLETED',
      producedNodeId: 'n5',
      producedAnswerId: 'answer-1',
      producedPatchId: 'patch-1',
      producedSpecSnapshotId: null,
    })
    const { wrapper } = await mountWorkspace()
    await wrapper.findComponent(GraphCanvasStub).vm.$emit('submit-answer', { freeText: 'answer' })
    await flushPromises()
    expect(mockedCreateAgentRun).toHaveBeenCalledWith('p1', {
      operation: 'ANSWER_TIP',
      nodeId: 'n2',
      selectedOptionId: null,
      freeText: 'answer',
    })
    expect(useWorkspaceStore().feedback).toBe('回答已记录。')
  })

  it('route sidebar activates a sibling route through the runtime command', async () => {
    mockViews()
    vi.mocked(apiActivateRoute).mockResolvedValue({
      projectId: 'p1',
      route: makeRoute({ id: 'r2', isActive: true }),
      activeRouteId: 'r2',
    })
    const { wrapper } = await mountWorkspace()
    await wrapper.find('[data-route-id="r2"] [data-test="activate-route"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(apiActivateRoute)).toHaveBeenCalledWith('p1', 'r2')
  })

  it('locate route only moves the viewport and never changes focus', async () => {
    mockViews()
    const { wrapper, graphUi } = await mountWorkspace()
    await wrapper.find('[data-route-id="r2"] [data-test="locate-route"]').trigger('click')
    expect(locateSpy).toHaveBeenCalledWith('r2')
    expect(graphUi.focusRouteId).toBeNull()
  })

  it('focus route changes only the browser reading context', async () => {
    mockViews()
    const { wrapper, graphUi } = await mountWorkspace()
    await wrapper.find('[data-route-id="r2"] [data-test="focus-route"]').trigger('click')
    expect(graphUi.focusRouteId).toBe('r2')
    expect(useWorkspaceStore().activeState?.activeRoute?.id).toBe('r1')
  })

  it('archive requires an explicit confirmation dialog', async () => {
    mockViews()
    const { wrapper } = await mountWorkspace()
    await wrapper.find('[data-route-id="r1"] [data-test="archive-route"]').trigger('click')
    expect(wrapper.find('[data-test="confirm-route-action-dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('归档该路线？')
    await wrapper.find('[data-test="cancel-route-action"]').trigger('click')
    expect(wrapper.find('[data-test="confirm-route-action-dialog"]').exists()).toBe(false)
  })

  it('fork dialog requires an explicit Focus route for a shared node', async () => {
    mockViews()
    const { wrapper, graphUi } = await mountWorkspace()
    await wrapper.findComponent(GraphCanvasStub).vm.$emit('fork', 'n1')
    expect(wrapper.find('[data-test="fork-dialog"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('当前查看')

    graphUi.setFocusRoute('r1')
    await flushPromises()
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeUndefined()
  })

  it('fork submits the explicit source route and user label through the existing API', async () => {
    mockViews()
    vi.mocked(apiForkNode).mockResolvedValue({
      projectId: 'p1',
      route: makeRoute({ id: 'route-fork', isActive: true }),
      activeRouteId: 'route-fork',
    })
    const { wrapper } = await mountWorkspace()
    await wrapper.findComponent(GraphCanvasStub).vm.$emit('fork', 'n1')
    useGraphUiStore().setFocusRoute('r1')
    await flushPromises()
    await wrapper.find('[data-test="fork-label"]').setValue('替代路线')
    await wrapper.find('[data-test="fork-submit"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(apiForkNode)).toHaveBeenCalledWith('p1', 'n1', { sourceRouteId: 'r1', label: '替代路线' })
  })

  it('regenerate dialog submits only the user direction and source route', async () => {
    mockViews()
    vi.mocked(apiRegenerateNode).mockResolvedValue(makeRegenerateResponse())
    const { wrapper, graphUi } = await mountWorkspace()
    graphUi.setFocusRoute('r1')
    await wrapper.findComponent(GraphCanvasStub).vm.$emit('regenerate', 'n2')
    expect(wrapper.find('[data-test="regenerate-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-test="regenerate-instruction"]').setValue('换个更可执行的切入点')
    await wrapper.find('[data-test="regenerate-submit"]').trigger('click')
    await flushPromises()
    const payload = vi.mocked(apiRegenerateNode).mock.calls[0][2]
    expect(payload).toEqual({ sourceRouteId: 'r1', instruction: '换个更可执行的切入点' })
  })

  it('spec tab reads the reading route and generates for the active route', async () => {
    mockViews()
    vi.mocked(listRouteSpecs).mockResolvedValue([])
    vi.mocked(apiGenerateSpec).mockResolvedValue(
      makeSpecGeneration({ specSnapshot: makeSpecSnapshot({ id: 'spec-1', routeId: 'r1' }) }),
    )
    const { wrapper, graphUi } = await mountWorkspace()
    graphUi.setFocusRoute('r1')
    await wrapper.find('[data-test="tab-spec"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="generate-spec"]').exists()).toBe(true)
    vi.mocked(listRouteSpecs).mockResolvedValue([makeSpecSnapshot({ id: 'spec-1', routeId: 'r1' })])
    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(apiGenerateSpec)).toHaveBeenCalledWith('p1')
    expect(wrapper.find('[data-test="spec-snapshot-detail"]').exists()).toBe(true)
  })


  it('shell copy is chinese while backend content stays verbatim', async () => {
    mockViews()
    const { wrapper, graphUi } = await mountWorkspace()
    graphUi.setFocusRoute('r1')
    await flushPromises()
    const text = wrapper.text()
    // 标题是项目名；壳层文案是中文。
    expect(text).toContain('Test project')
    expect(text).toContain('当前路线')
    expect(text).toContain('开放')
    expect(text).toContain('已替代')
    expect(text).toContain('已归档')
    expect(text).toContain('已删除')
    expect(text).toContain('需求状态')
    expect(text).toContain('规格')
    expect(text).toContain('归档')
    expect(text).toContain('删除路线')
    expect(text).toContain('定位路线')
    expect(text).toContain('聚焦此路线')
    expect(text).toContain('弱化路线')
    expect(text).toContain('隐藏路线')
    // 后端/用户内容保持原样（verbatim）：路线名与派生 claim 文本不翻译。
    expect(text).toContain('开放分支')
    expect(text).toContain('旧路线')
    expect(text).toContain('A confirmed requirement detail.')
  })

  it('floating window state persists independently of canvas layout', async () => {
    mockViews()
    const { wrapper, graphUi } = await mountWorkspace()
    await wrapper.find('[data-test="floating-window-routes"] [data-test="floating-window-close"]').trigger('click')
    expect(graphUi.floatingWindows.routes.open).toBe(false)
    await wrapper.find('[data-test="floating-window-inspector"] [data-test="floating-window-reset"]').trigger('click')
    expect(graphUi.floatingWindows.routes.open).toBe(true)
    expect(graphUi.floatingWindows.routes.width).toBe(320)
    expect(graphUi.floatingWindows.inspector.width).toBe(420)
  })
})
