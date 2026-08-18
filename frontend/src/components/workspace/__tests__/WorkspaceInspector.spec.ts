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
  makeSpecSnapshot,
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

describe('workspace inspector', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    useGraphUiStore().initProject('p1')
  })

  it('with no focus the reading route is the active route', async () => {
    const store = await loadStore()
    const ui = useGraphUiStore()
    expect(ui.readingRouteId(store.activeRoute?.id ?? null)).toBe(store.activeRoute?.id)
  })

  it('Active=A + Focus=B reads requirement state and specs for B only', async () => {
    const activeA = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'rA' }),
      activeRoute: makeRoute({ id: 'rA', isActive: true }),
      activeNode: makeNode({ id: 'nA' }),
    })
    const store = await loadStore(activeA)
    const ui = useGraphUiStore()
    ui.setFocusRoute('rB')
    mockedGetRouteRequirementState.mockResolvedValue(makeRequirementState({ routeId: 'rB' }))
    mockedListRouteSpecs.mockResolvedValue([makeSpecSnapshot({ routeId: 'rB', id: 'spec-B' })])

    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(mockedGetRouteRequirementState).toHaveBeenCalledWith('p1', 'rB')
    expect(mockedListRouteSpecs).toHaveBeenCalledWith('p1', 'rB')
    expect(store.requirementStatesByRoute.rB?.routeId).toBe('rB')
  })

  it('no selection defaults to the requirement-state tab', async () => {
    await loadStore()
    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    expect(wrapper.find('[data-test="tab-requirement"]').classes()).toContain('active')
    expect(wrapper.find('[data-test="requirement-state-panel"]').exists()).toBe(true)
  })

  it('a selected node switches the default tab to details', async () => {
    await loadStore()
    const nodeData = {
      node: makeNode({ id: 'n1', question: 'Selection question' }),
      routeIds: ['rA'],
      answers: [],
      routeStates: [{ routeId: 'rA', answer: null }],
      primaryAnswer: null,
      readingRouteId: 'rA',
      isCurrent: false,
      canAnswer: false,
      isExpanded: false,
      isShared: false,
      visualWeight: 'normal' as const,
    }
    const wrapper = mount(WorkspaceInspector, { props: { nodeData } })
    expect(wrapper.find('[data-test="node-inspector"]').exists()).toBe(true)
  })

  it('clearing the selection returns to the requirement tab, never the spec tab', async () => {
    await loadStore()
    const nodeData = {
      node: makeNode({ id: 'n1', question: 'Selection question' }),
      routeIds: ['rA'],
      answers: [],
      routeStates: [{ routeId: 'rA', answer: null }],
      primaryAnswer: null,
      readingRouteId: 'rA',
      isCurrent: false,
      canAnswer: false,
      isExpanded: false,
      isShared: false,
      visualWeight: 'normal' as const,
    }
    const wrapper = mount(WorkspaceInspector, { props: { nodeData } })
    // 选中节点 → 详情。
    expect(wrapper.find('[data-test="node-inspector"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="tab-details"]').classes()).toContain('active')
    // 清除选择 → 默认需求状态（不是规格）。
    await wrapper.setProps({ nodeData: null })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-test="node-inspector"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="tab-requirement"]').classes()).toContain('active')
    expect(wrapper.find('[data-test="requirement-state-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="spec-snapshot-panel"]').exists()).toBe(false)
  })

  it('inspector shows route-specific answered and waiting states for a shared node', async () => {
    await loadStore()
    const nodeData = {
      node: makeNode({ id: 'n1', question: 'Shared node question' }),
      routeIds: ['rA', 'rB'],
      answers: [
        { routeId: 'rA', selectedOptionId: null, selectedOptionLabel: null, freeText: 'A answer', isPrimary: false },
      ],
      routeStates: [
        { routeId: 'rA', answer: { routeId: 'rA', selectedOptionId: null, selectedOptionLabel: null, freeText: 'A answer', isPrimary: false } },
        { routeId: 'rB', answer: null },
      ],
      primaryAnswer: null,
      readingRouteId: 'rB',
      isCurrent: false,
      canAnswer: false,
      isExpanded: false,
      isShared: true,
      visualWeight: 'focus' as const,
    }
    const wrapper = mount(WorkspaceInspector, { props: { nodeData } })
    expect(wrapper.find('[data-test="route-answer-rA"]').text()).toContain('A answer')
    // rB 没有回答 → 显式等待，绝不使用 A 的 answer。
    expect(wrapper.find('[data-test="route-answer-rB"]').text()).toContain('等待回答')
    expect(wrapper.find('[data-test="route-answer-rB"]').text()).not.toContain('A answer')
  })

  it('spec generation warning targets the active route while reading another', async () => {
    const activeA = makeActiveState({
      project: makeProject({ id: 'p1', activeRouteId: 'rA' }),
      activeRoute: makeRoute({ id: 'rA', isActive: true, tipNodeId: 'nA' }),
      activeNode: makeNode({ id: 'nA' }),
    })
    await loadStore(activeA)
    const ui = useGraphUiStore()
    ui.setFocusRoute('rB')
    const wrapper = mount(WorkspaceInspector, { props: { nodeData: null } })
    await wrapper.find('[data-test="tab-spec"]').trigger('click')
    expect(wrapper.text()).toContain('当前路线：rA')
    expect(wrapper.text()).toContain('你目前正在查看 rB，生成操作将针对当前路线 rA。')
  })
})