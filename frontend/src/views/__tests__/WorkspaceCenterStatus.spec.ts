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
  makeRequirementState,
  makeRoute,
} from '@/test/fixtures'

vi.mock('@/api/projects', () => ({ getProject: vi.fn() }))
vi.mock('@/api/workspace', () => ({ getActiveState: vi.fn(), listRoutes: vi.fn() }))
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
vi.mock('@/api/spec', () => ({ generateSpec: vi.fn(), listRouteSpecs: vi.fn() }))

import { getProject } from '@/api/projects'
import { getActiveState, listRoutes } from '@/api/workspace'
import { getRequirementState } from '@/api/requirementState'
import { getProjectGraph } from '@/api/graph'

const mockedGetProject = vi.mocked(getProject)
const mockedGetActiveState = vi.mocked(getActiveState)
const mockedListRoutes = vi.mocked(listRoutes)
const mockedGetRequirementState = vi.mocked(getRequirementState)
const mockedGetProjectGraph = vi.mocked(getProjectGraph)

const GraphCanvasStub = defineComponent({
  name: 'GraphCanvas',
  props: {
    view: { type: Object, default: null },
    activeNodeId: { type: String, default: null },
    submitting: Boolean,
    drafting: Boolean,
    pending: Boolean,
  },
  emits: ['draft', 'submit-answer', 'fork', 'regenerate', 'contextual-ai'],
  setup() {
    return () => h('div', { 'data-test': 'graph-canvas-stub' })
  },
})

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
  mockedGetProjectGraph.mockResolvedValue(makeGraphWorkspaceView({
    projectId: 'p1',
    activeRouteId: 'r1',
    routes: [
      {
        id: 'r1', label: '当前路线', lifecycleStatus: 'open', isActive: true,
        rootNodeId: 'n1', tipNodeId: 'n2', createdFromNodeId: null,
        supersedesRouteId: null, replacementOfNodeId: null, lineageNodeIds: ['n1', 'n2'],
      },
    ],
    nodes: [makeNode({ id: 'n1', projectId: 'p1' }), makeNode({ id: 'n2', projectId: 'p1', parentNodeId: 'n1' })],
  }))
  return active
}

async function mountWorkspace(projectId = 'p1') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(WorkspaceView, {
    props: { projectId },
    global: { plugins: [pinia], stubs: { GraphCanvas: GraphCanvasStub } },
  })
  await flushPromises()
  return { wrapper, store: useWorkspaceStore(), graphUi: useGraphUiStore() }
}

describe('WorkspaceView unified center status', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('renders only the reconcile CTA for an unknown outcome', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    store.answerOutcomeUnknown = true
    store.repairableAnswerId = 'a1'
    store.resubmitAnswerPayload = { selectedOptionId: null, freeText: 'x' }
    await flushPromises()

    expect(wrapper.find('[data-test="recovery-notice"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('提交结果暂时无法确认')
    const actions = wrapper.findAll('[data-test="recovery-action"]')
    expect(actions).toHaveLength(1)
    expect(actions[0].text()).toContain('同步状态')
    expect(wrapper.find('[data-test="answer-retry"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="answer-outcome-unknown"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="answer-resubmit"]').exists()).toBe(false)
  })

  it('renders resume CTA and never resubmit when the answer is saved', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    store.repairableAnswerId = 'a1'
    store.resubmitAnswerPayload = { selectedOptionId: null, freeText: 'x' }
    await flushPromises()

    expect(wrapper.text()).toContain('回答已经保存')
    const actions = wrapper.findAll('[data-test="recovery-action"]')
    expect(actions).toHaveLength(1)
    expect(actions[0].text()).toContain('继续生成')
  })

  it('renders one concise status for a normal active agent phase', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    store.pendingRouteProjection = {
      routeId: 'r1', sourceNodeId: null, runId: 'run-1',
      status: 'RUNNING', phase: 'DECIDING', message: null,
    }
    await flushPromises()

    const status = wrapper.find('[data-test="agent-status"]')
    expect(status.exists()).toBe(true)
    expect(status.text()).toContain('正在规划下一步')
  })

  it('renders a generic fallback without the raw phase code', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    store.pendingRouteProjection = {
      routeId: 'r1', sourceNodeId: null, runId: 'run-1',
      status: 'RUNNING', phase: 'SOME_NEW_INTERNAL_PHASE', message: null,
    }
    await flushPromises()

    const status = wrapper.find('[data-test="agent-status"]')
    expect(status.exists()).toBe(true)
    expect(status.text()).toContain('处理中…')
    expect(status.text()).not.toContain('SOME_NEW_INTERNAL_PHASE')
  })

  it('keeps an ordinary error banner when no recovery model applies', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    store.error = { code: 'UNKNOWN_ERROR', message: '操作失败，请稍后重试。' }
    await flushPromises()

    expect(wrapper.find('[data-test="recovery-notice"]').exists()).toBe(false)
    expect(wrapper.find('.error-banner').exists()).toBe(true)
  })

  it('renders the agent status exactly once with no legacy runtime-phase element', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    store.pendingRouteProjection = {
      routeId: 'r1', sourceNodeId: null, runId: 'run-1',
      status: 'RUNNING', phase: 'DECIDING', message: null,
    }
    await flushPromises()

    expect(wrapper.findAll('[data-test="agent-status"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="runtime-phase"]').exists()).toBe(false)
    // “正在规划下一步”在中央只出现一次（无重复渲染）。
    const occurrences = wrapper.text().split('正在规划下一步').length - 1
    expect(occurrences).toBe(1)
  })

  it('hides the one-line status once the run reaches a terminal phase', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    // 成功链终态：即使 answerRunId 仍保留，也不常驻“已完成”。
    store.answerRunId = 'run-done'
    store.answerRunStatus = 'SUCCEEDED'
    store.answerRunPhase = 'COMPLETED'
    await flushPromises()
    expect(wrapper.find('[data-test="agent-status"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('已完成')
  })

  it('renders the unknown fallback exactly once without the raw phase', async () => {
    mockViews()
    const { wrapper, store } = await mountWorkspace()
    store.pendingRouteProjection = {
      routeId: 'r1', sourceNodeId: null, runId: 'run-1',
      status: 'RUNNING', phase: 'SOME_NEW_INTERNAL_PHASE', message: null,
    }
    await flushPromises()

    expect(wrapper.findAll('[data-test="agent-status"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="runtime-phase"]').exists()).toBe(false)
    const occurrences = wrapper.text().split('处理中…').length - 1
    expect(occurrences).toBe(1)
    expect(wrapper.text()).not.toContain('SOME_NEW_INTERNAL_PHASE')
  })
})
