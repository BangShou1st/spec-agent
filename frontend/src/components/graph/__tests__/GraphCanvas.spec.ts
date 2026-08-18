import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import GraphCanvas from '@/components/graph/GraphCanvas.vue'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useVueFlow } from '@vue-flow/core'
import { makeGraphWorkspaceView, makeNode } from '@/test/fixtures'
import type { GraphWorkspaceView } from '@/api/types'

/**
 * Vue Flow stub: jsdom cannot render the real viewport; the canvas only
 * needs the props/events contract to be testable.
 */
const VueFlowStub = defineComponent({
  name: 'VueFlow',
  props: {
    nodes: { type: Array, default: () => [] },
    edges: { type: Array, default: () => [] },
  },
  emits: ['node-drag-stop', 'nodes-change', 'pane-click'],
  setup() {
    return () => h('div', { class: 'vf-stub' })
  },
})

const PROJECT_ID = 'p1'
const ACTIVE_ROUTE = 'r1'

function viewWithNodes(overrides: Partial<GraphWorkspaceView> = {}): GraphWorkspaceView {
  return makeGraphWorkspaceView({
    projectId: PROJECT_ID,
    activeRouteId: ACTIVE_ROUTE,
    routes: [
      {
        id: ACTIVE_ROUTE,
        label: 'Initial route',
        lifecycleStatus: 'open',
        isActive: true,
        rootNodeId: 'n1',
        tipNodeId: 'n1',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1'],
      },
      {
        id: 'r2',
        label: 'Second route',
        lifecycleStatus: 'open',
        isActive: false,
        rootNodeId: 'n1',
        tipNodeId: 'n2',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1', 'n2'],
      },
    ],
    nodes: [
      makeNode({ id: 'n1', projectId: PROJECT_ID }),
      makeNode({ id: 'n2', projectId: PROJECT_ID, parentNodeId: 'n1' }),
    ],
    answers: [],
    ...overrides,
  })
}

function mountCanvas(view: GraphWorkspaceView | null, extra = {}) {
  const wrapper = mount(GraphCanvas, {
    props: {
      view,
      activeNodeId: 'n1',
      submitting: false,
      drafting: false,
      pending: false,
      ...extra,
    },
    global: {
      stubs: { VueFlow: VueFlowStub },
    },
  })
  return wrapper
}

describe('graph canvas', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useGraphUiStore().initProject(PROJECT_ID)
    vi.restoreAllMocks()
  })

  it('shows the centered start placeholder for an empty active project', async () => {
    const view = makeGraphWorkspaceView({
      projectId: PROJECT_ID,
      activeRouteId: ACTIVE_ROUTE,
      routes: [],
      nodes: [],
      answers: [],
    })
    const wrapper = mountCanvas(view)
    expect(wrapper.text()).toContain('开始需求澄清')
    expect(wrapper.text()).toContain('还没有生成任何问题。')
    await wrapper.find('[data-test="draft-question"]').trigger('click')
    expect(wrapper.emitted('draft')).toHaveLength(1)
    // The placeholder never creates a fake node or persists a coordinate.
    expect(useGraphUiStore().nodePositions).toEqual({})
  })

  it('renders flow nodes from the canonical projection', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    const nodes = flow.props('nodes') as unknown[]
    expect(nodes).toHaveLength(2)
    expect(flow.props('edges')).toHaveLength(1)
  })

  it('mirrors selection changes into graphUiStore', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('nodes-change', [
      { id: 'n1', type: 'select', selected: true },
      { id: 'n2', type: 'select', selected: false },
    ])
    const ui = useGraphUiStore()
    expect(ui.selectedNodeIds).toEqual(['n1'])
    expect(ui.primarySelectedNodeId).toBe('n1')
  })

  it('persists positions only on drag stop, never mid-drag', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('node-drag-stop', {
      nodes: [
        { id: 'n1', position: { x: 123, y: 45 } },
        { id: 'n2', position: { x: 483, y: 45 } },
      ],
    })
    const ui = useGraphUiStore()
    expect(ui.nodePositions.n1).toEqual({ x: 123, y: 45 })
    expect(ui.nodePositions.n2).toEqual({ x: 483, y: 45 })
    const saved = JSON.parse(localStorage.getItem('spec-agent.graph-layout.v1.p1') ?? '{}')
    expect(saved.nodePositions.n1).toEqual({ x: 123, y: 45 })
  })

  it('locateRoute fits only that route visible nodes and never sets focus', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    const fitSpy = vi.spyOn(vf, 'fitView').mockResolvedValue(true)
    const exposed = wrapper.vm as unknown as { locateRoute: (routeId: string) => Promise<void> }
    await exposed.locateRoute('r2')
    expect(fitSpy).toHaveBeenCalledWith(expect.objectContaining({ nodes: ['n1', 'n2'] }))
    expect(useGraphUiStore().focusRouteId).toBeNull()
  })

  it('auto-layout after confirm replaces all positions and persists them', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    vi.spyOn(vf, 'fitView').mockResolvedValue(true)
    await wrapper.find('[data-test="auto-layout"]').trigger('click')
    const ui = useGraphUiStore()
    expect(ui.nodePositions.n1).toBeDefined()
    expect(ui.nodePositions.n2).toBeDefined()
    expect(window.confirm).toHaveBeenCalledWith(
      '重新自动布局将覆盖当前项目手工调整过的节点位置。Runtime 历史不会改变。',
    )
  })

  it('auto-layout cancel keeps existing positions untouched', async () => {
    const ui = useGraphUiStore()
    ui.setNodePosition('n1', { x: 1, y: 2 })
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mountCanvas(viewWithNodes())
    await wrapper.find('[data-test="auto-layout"]').trigger('click')
    expect(ui.nodePositions.n1).toEqual({ x: 1, y: 2 })
  })

  it('pane click clears the selection', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const ui = useGraphUiStore()
    ui.selectNode('n1')
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('pane-click', {})
    expect(ui.selectedNodeIds).toEqual([])
    expect(ui.primarySelectedNodeId).toBeNull()
  })

  it('toolbar buttons emit zoom/fit/show-all intents', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    const zoomIn = vi.spyOn(vf, 'zoomIn').mockResolvedValue(true)
    const zoomOut = vi.spyOn(vf, 'zoomOut').mockResolvedValue(true)
    const fit = vi.spyOn(vf, 'fitView').mockResolvedValue(true)
    await wrapper.find('[data-test="zoom-in"]').trigger('click')
    await wrapper.find('[data-test="zoom-out"]').trigger('click')
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(zoomIn).toHaveBeenCalled()
    expect(zoomOut).toHaveBeenCalled()
    expect(fit).toHaveBeenCalled()
    await wrapper.find('[data-test="show-all"]').trigger('click')
    const ui = useGraphUiStore()
    expect(ui.focusRouteId).toBeNull()
  })
})