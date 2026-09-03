import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import GraphCanvas from '@/components/graph/GraphCanvas.vue'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useVueFlow, type VueFlowStore } from '@vue-flow/core'
import { makeGraphWorkspaceView, makeNode } from '@/test/fixtures'
import type { GraphWorkspaceView } from '@/api/types'
import { HORIZONTAL_GAP } from '@/graph/graphLayout'

/**
 * Vue Flow stub: jsdom cannot render the real viewport; the canvas only
 * needs the props/events contract to be testable. All fit-style operations
 * are asserted through the deterministic `setViewport` path — the Vue Flow
 * measurement-dependent `fitView` must never be called by the canvas.
 */
const VueFlowStub = defineComponent({
  name: 'VueFlow',
  props: {
    nodes: { type: Array, default: () => [] },
    edges: { type: Array, default: () => [] },
  },
  emits: ['init', 'node-click', 'edge-click', 'node-drag', 'node-drag-stop', 'nodes-change', 'pane-click', 'connect', 'viewport-change-end', 'update-node-internals'],
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

/** Gives the canvas a concrete size for deterministic viewport math. */
function setCanvasSize(
  vf: VueFlowStore,
  width = 1200,
  height = 800,
): void {
  ;(vf.dimensions as unknown as { value: { width: number; height: number } }).value = {
    width,
    height,
  }
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
    expect(wrapper.text()).toContain('还没有任何内容')
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

  it('persists first-projected positions browser-locally so refreshes recognize existing nodes', () => {
    mountCanvas(viewWithNodes())
    const ui = useGraphUiStore()
    expect(ui.nodePositions.n1).toEqual({ x: 0, y: 0 })
    expect(ui.nodePositions.n2).toEqual({ x: HORIZONTAL_GAP, y: 0 })
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

  it('initial fit uses the deterministic setViewport path once nodes are mounted', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
    const fitViewSpy = vi.spyOn(vf, 'fitView')
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('init')
    await nextTick()
    await nextTick()
    expect(fitViewSpy).not.toHaveBeenCalled()
    expect(setViewport).toHaveBeenCalledTimes(1)
    // bounds n1(0,0,320,220)+n2(HORIZONTAL_GAP,0,...) -> center in viewport
    expect(setViewport).toHaveBeenCalledWith(
      expect.objectContaining({ x: 600 - ((320 + HORIZONTAL_GAP) / 2), y: 290, zoom: 1 }),
      expect.objectContaining({ duration: 0 }),
    )
  })

  it('locateRoute fits only that route visible nodes via setViewport, never focus', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
    const fitViewSpy = vi.spyOn(vf, 'fitView')
    const exposed = wrapper.vm as unknown as { locateRoute: (routeId: string) => Promise<void> }
    await exposed.locateRoute('r2')
    expect(fitViewSpy).not.toHaveBeenCalled()
    expect(setViewport).toHaveBeenCalledTimes(1)
    expect(setViewport).toHaveBeenCalledWith(
      expect.objectContaining({ x: 600 - ((320 + HORIZONTAL_GAP) / 2), y: 290, zoom: 1 }),
      expect.objectContaining({ duration: 400 }),
    )
    expect(useGraphUiStore().focusRouteId).toBeNull()
  })

  it('locateNode centers exactly the requested node via setViewport', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
    const exposed = wrapper.vm as unknown as { locateNode: (nodeId: string) => Promise<void> }
    await exposed.locateNode('n2')
    // n2 at (HORIZONTAL_GAP, 0) 320x220 -> centered in the viewport
    expect(setViewport).toHaveBeenCalledWith(
      expect.objectContaining({ x: 600 - (HORIZONTAL_GAP + 160), y: 290, zoom: 1 }),
      expect.objectContaining({ duration: 400 }),
    )
  })

  it('new active node reveal skips nodes already inside the viewport', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountCanvas(viewWithNodes(), { activeNodeId: 'n1' })
      const vf = useVueFlow('spec-agent-graph-canvas')
      setCanvasSize(vf)
      // 视口默认 zoom=1、x=0：n2 在 HORIZONTAL_GAP.. 与画布相交 → 不 reveal。
      const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
      await wrapper.setProps({ activeNodeId: 'n2' })
      await wrapper.vm.$nextTick()
      vi.advanceTimersByTime(550)
      expect(setViewport).not.toHaveBeenCalled()
      const ui = useGraphUiStore()
      expect(ui.nodePositions.n1).toEqual({ x: 0, y: 0 })
      expect(ui.nodePositions.n2).toEqual({ x: HORIZONTAL_GAP, y: 0 })
    } finally {
      vi.useRealTimers()
    }
  })

  it('new active node reveal centers a genuinely offscreen node via setViewport', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountCanvas(viewWithNodes(), { activeNodeId: 'n1' })
      const vf = useVueFlow('spec-agent-graph-canvas')
      setCanvasSize(vf)
      // 把视口平移到远处，让 n2 完全离开画布（jsdom 中 setViewport 是空操作，
      // 直接写 store ref）。
      ;(vf.viewport as unknown as { value: { x: number; y: number; zoom: number } }).value = {
        x: 5000,
        y: 0,
        zoom: 1,
      }
      const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
      await wrapper.setProps({ activeNodeId: 'n2' })
      await wrapper.vm.$nextTick()
      vi.advanceTimersByTime(550)
      expect(setViewport).toHaveBeenCalledTimes(1)
      expect(setViewport).toHaveBeenCalledWith(
        expect.objectContaining({
          x: 600 - (HORIZONTAL_GAP + 160),
          y: 290,
          zoom: 1,
        }),
        expect.objectContaining({ duration: 400 }),
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('adding a new node does not trigger an automatic whole-graph fit', async () => {
    vi.useFakeTimers()
    try {
      const current = viewWithNodes()
      const wrapper = mountCanvas(current)
      const vf = useVueFlow('spec-agent-graph-canvas')
      setCanvasSize(vf)
      const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
      const nextView = {
        ...current,
        routes: current.routes.map((route) =>
          route.id === 'r2'
            ? {
                ...route,
                tipNodeId: 'n3',
                lineageNodeIds: [...route.lineageNodeIds, 'n3'],
              }
            : route,
        ),
        nodes: [
          ...current.nodes,
          makeNode({ id: 'n3', projectId: PROJECT_ID, parentNodeId: 'n2' }),
        ],
      }
      await wrapper.setProps({ view: nextView })
      await nextTick()
      vi.runAllTimers()
      expect(setViewport).not.toHaveBeenCalled()
      expect(useGraphUiStore().nodePositions.n1).toEqual({ x: 0, y: 0 })
      expect(useGraphUiStore().nodePositions.n2).toEqual({ x: HORIZONTAL_GAP, y: 0 })
    } finally {
      vi.useRealTimers()
    }
  })

  it('changing route count does not trigger an automatic whole-graph fit', async () => {
    vi.useFakeTimers()
    try {
      const current = viewWithNodes()
      const wrapper = mountCanvas(current)
      const vf = useVueFlow('spec-agent-graph-canvas')
      setCanvasSize(vf)
      const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
      await wrapper.setProps({
        view: {
          ...current,
          routes: [
            ...current.routes,
            {
              ...current.routes[1],
              id: 'r3',
              label: 'Third route',
              isActive: false,
            },
          ],
        },
      })
      await nextTick()
      vi.runAllTimers()
      expect(setViewport).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('an explicit fit-view cancels the pending reveal so the user action wins', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountCanvas(viewWithNodes(), { activeNodeId: 'n1' })
      const vf = useVueFlow('spec-agent-graph-canvas')
      setCanvasSize(vf)
      ;(vf.viewport as unknown as { value: { x: number; y: number; zoom: number } }).value = {
        x: 5000,
        y: 0,
        zoom: 1,
      }
      const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
      await wrapper.setProps({ activeNodeId: 'n2' })
      await wrapper.vm.$nextTick()
      // 用户立刻点了适应视图：待执行的 reveal 被取消。
      await wrapper.find('[data-test="fit-view"]').trigger('click')
      vi.advanceTimersByTime(550)
      expect(setViewport).toHaveBeenCalledTimes(1)
      expect(setViewport).toHaveBeenCalledWith(
        expect.objectContaining({ x: 220, y: 290, zoom: 1 }),
        expect.objectContaining({ duration: 300 }),
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('toolbar fit-view uses the full canvas viewport', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
    const fitViewSpy = vi.spyOn(vf, 'fitView')
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(fitViewSpy).not.toHaveBeenCalled()
    expect(setViewport).toHaveBeenCalledWith(
      expect.objectContaining({ x: 220, y: 290, zoom: 1 }),
      expect.objectContaining({ duration: 300 }),
    )
  })

  it('auto-layout after confirm replaces all positions, persists them and fits via setViewport', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const setViewport = vi.spyOn(vf, 'setViewport').mockResolvedValue(true)
    const fitViewSpy = vi.spyOn(vf, 'fitView')
    await wrapper.find('[data-test="auto-layout"]').trigger('click')
    const ui = useGraphUiStore()
    expect(ui.nodePositions.n1).toBeDefined()
    expect(ui.nodePositions.n2).toBeDefined()
    expect(window.confirm).toHaveBeenCalledWith(
      '重新自动布局将覆盖当前项目手工调整过的节点位置。Runtime 历史不会改变。',
    )
    expect(fitViewSpy).not.toHaveBeenCalled()
    expect(setViewport).toHaveBeenCalledWith(
      expect.objectContaining({ x: 600 - ((320 + HORIZONTAL_GAP) / 2), y: 290, zoom: 1 }),
      expect.objectContaining({ duration: 300 }),
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

  it('normal exclusive node click selects and focuses its route', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('node-click', {
      event: new MouseEvent('click'),
      node: { id: 'n2', data: { routeIds: ['r2'], visibleRouteIds: ['r2'] } },
    })
    const ui = useGraphUiStore()
    expect(ui.primarySelectedNodeId).toBe('n2')
    expect(ui.focusRouteId).toBe('r2')
  })

  it('modified node selection never changes Focus', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const ui = useGraphUiStore()
    ui.setFocusRoute('r2')
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('node-click', {
      event: new MouseEvent('click', { ctrlKey: true }),
      node: { id: 'n2', data: { routeIds: ['r1'], visibleRouteIds: ['r1'] } },
    })
    expect(ui.focusRouteId).toBe('r2')
    flow.vm.$emit('node-click', {
      event: new MouseEvent('click', { shiftKey: true }),
      node: { id: 'n1', data: { routeIds: ['r1', 'r2'], visibleRouteIds: ['r1', 'r2'] } },
    })
    expect(ui.focusRouteId).toBe('r2')
  })

  it('shared node and edge clicks keep Focus explicit without activating Runtime', () => {
    const wrapper = mountCanvas(viewWithNodes({ activeRouteId: 'r3' }))
    const ui = useGraphUiStore()
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('node-click', {
      event: new MouseEvent('click'),
      node: { id: 'n1', data: { routeIds: ['r1', 'r2'], visibleRouteIds: ['r1'] } },
    })
    expect(ui.focusRouteId).toBe('r1')
    ui.clearFocusRoute()
    flow.vm.$emit('edge-click', {
      event: new MouseEvent('click'),
      edge: { id: 'shared-edge', data: { routeIds: ['r1', 'r2'], visibleRouteIds: ['r2'] } },
    })
    expect(ui.focusRouteId).toBeNull()
    expect(ui.selectedEdgeId).toBe('shared-edge')
    expect(ui.selectedSharedEdgeRouteIds).toEqual(['r1', 'r2'])
    expect(wrapper.props('view')).toMatchObject({ activeRouteId: 'r3' })
  })

  it('does not infer Active as Focus for an ambiguous shared node', () => {
    const wrapper = mountCanvas(viewWithNodes({ activeRouteId: 'r1' }))
    const ui = useGraphUiStore()
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('node-click', {
      event: new MouseEvent('click'),
      node: { id: 'n1', data: { routeIds: ['r1', 'r2'], visibleRouteIds: ['r1', 'r2'] } },
    })
    expect(ui.focusRouteId).toBeNull()
    expect(wrapper.props('view')).toMatchObject({ activeRouteId: 'r1' })
  })

  it('pane click clears both selection and browser Focus', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const ui = useGraphUiStore()
    ui.selectNode('n1')
    ui.setFocusRoute('r2')
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('pane-click', {})
    expect(ui.selectedNodeIds).toEqual([])
    expect(ui.primarySelectedNodeId).toBeNull()
    expect(ui.focusRouteId).toBeNull()
  })

  it('toolbar buttons emit zoom/show-all intents', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    const zoomIn = vi.spyOn(vf, 'zoomIn').mockResolvedValue(true)
    const zoomOut = vi.spyOn(vf, 'zoomOut').mockResolvedValue(true)
    await wrapper.find('[data-test="zoom-in"]').trigger('click')
    await wrapper.find('[data-test="zoom-out"]').trigger('click')
    expect(zoomIn).toHaveBeenCalled()
    expect(zoomOut).toHaveBeenCalled()
    await wrapper.find('[data-test="show-all"]').trigger('click')
    const ui = useGraphUiStore()
    expect(ui.focusRouteId).toBeNull()
  })

  it('projection edges come out as adaptive curves with directed handles', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    const edges = flow.props('edges') as unknown[]
    // Both node states use the stable 320px footprint; routing remains adaptive.
    expect(edges[0]).toMatchObject({
      id: 'n1->n2',
      type: 'adaptive',
      sourceHandle: 'source-right',
      targetHandle: 'target-left',
    })
  })

  it('drag-time rerouting flips handles when a node crosses to the other side', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    // 把 n2 拖到 n1 左侧：n2 center (-340, 110) -> horizontal left.
    ;(flow.vm as unknown as { $emit: (e: string, ...a: unknown[]) => void }).$emit('node-drag', {
      event: {},
      nodes: [{ id: 'n2', position: { x: -500, y: 0 } }],
      intersections: [],
    })
    await nextTick()
    const edges = flow.props('edges') as unknown[]
    expect(edges[0]).toMatchObject({
      sourceHandle: 'source-left',
      targetHandle: 'target-right',
    })
  })

  it('drag-time rerouting switches to vertical handles when the node is dragged below', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    // n2 (200, 500) center (360, 610): dx=200 < |dy| * 0.8 -> vertical, below.
    ;(flow.vm as unknown as { $emit: (e: string, ...a: unknown[]) => void }).$emit('node-drag', {
      event: {},
      nodes: [{ id: 'n2', position: { x: 200, y: 500 } }],
      intersections: [],
    })
    await nextTick()
    const edges = flow.props('edges') as unknown[]
    expect(edges[0]).toMatchObject({
      sourceHandle: 'source-bottom',
      targetHandle: 'target-top',
    })
  })

  it('a second crossing switches the same edge back: left -> right then right -> left', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    const emit = (flow.vm as unknown as { $emit: (e: string, ...a: unknown[]) => void }).$emit
    emit('node-drag', { event: {}, nodes: [{ id: 'n2', position: { x: -500, y: 0 } }], intersections: [] })
    await nextTick()
    expect((flow.props('edges') as unknown[])[0]).toMatchObject({
      sourceHandle: 'source-left',
      targetHandle: 'target-right',
    })
    emit('node-drag', { event: {}, nodes: [{ id: 'n2', position: { x: 360, y: 0 } }], intersections: [] })
    await nextTick()
    expect((flow.props('edges') as unknown[])[0]).toMatchObject({
      sourceHandle: 'source-right',
      targetHandle: 'target-left',
    })
  })

  it('drag-time rerouting never writes localStorage; only drag stop persists', () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    const ui = useGraphUiStore()
    const before = localStorage.getItem('spec-agent.graph-layout.v1.p1')
    // Mid-drag moves must stay browser-only.
    ;(flow.vm as unknown as { $emit: (e: string, ...a: unknown[]) => void }).$emit('node-drag', {
      event: {},
      nodes: [{ id: 'n2', position: { x: -500, y: 0 } }],
      intersections: [],
    })
    expect(localStorage.getItem('spec-agent.graph-layout.v1.p1')).toBe(before)
    expect(ui.nodePositions.n2).toEqual({ x: HORIZONTAL_GAP, y: 0 })
    // Only the drag stop persists the new position.
    flow.vm.$emit('node-drag-stop', {
      nodes: [{ id: 'n2', position: { x: -500, y: 0 } }],
    })
    expect(ui.nodePositions.n2).toEqual({ x: -500, y: 0 })
    const saved = JSON.parse(localStorage.getItem('spec-agent.graph-layout.v1.p1') ?? '{}')
    expect(saved.nodePositions.n2).toEqual({ x: -500, y: 0 })
  })
})

describe('GraphCanvas onConnect (connection affordance)', () => {
  it('self-connection emits nothing (no relation proposal)', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    await flow.vm.$emit('connect', {
      source: 'n1',
      target: 'n1',
      sourceHandle: 'source-right',
      targetHandle: 'target-left',
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('relation-proposal')).toBeUndefined()
  })

  it('connection originating from a pending projection card is ignored', async () => {
    const wrapper = mountCanvas(viewWithNodes(), {
      pendingProjection: {
        routeId: ACTIVE_ROUTE,
        sourceNodeId: 'n1',
        runId: 'run-x',
        status: 'PENDING',
        phase: 'DECIDING',
        message: null,
      },
    })
    const flow = wrapper.findComponent(VueFlowStub)
    await flow.vm.$emit('connect', {
      source: 'pending:run-x',
      target: 'n1',
      sourceHandle: 'source-right',
      targetHandle: 'target-left',
    })
    await wrapper.vm.$nextTick()
    // pending:run-x is not a canonical Node; the frontend refuses to even
    // raise a relation proposal.
    expect(wrapper.emitted('relation-proposal')).toBeUndefined()
  })

  it('connection between two canonical nodes opens a pending proposal without calling the backend', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const flow = wrapper.findComponent(VueFlowStub)
    await flow.vm.$emit('connect', {
      source: 'n1',
      target: 'n2',
      sourceHandle: 'source-right',
      targetHandle: 'target-left',
    })
    await wrapper.vm.$nextTick()
    // 真实 mouse drag → ONLY a relation proposal; nothing is persisted and no
    // create-relation event is raised until the user confirms type/direction.
    expect(wrapper.emitted('relation-proposal')?.[0]?.[0]).toEqual({
      sourceNodeId: 'n1',
      targetNodeId: 'n2',
    })
    expect(wrapper.emitted('create-relation')).toBeUndefined()
  })
})

describe('GraphCanvas viewport settlement contract', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useGraphUiStore().initProject(PROJECT_ID)
    vi.restoreAllMocks()
  })

  /**
   * Contract:
   * - data-viewport-settled MUST NOT advance when applyViewport starts
   * - while setViewport Promise unresolved it MUST remain unchanged
   * - after Promise resolves it advances exactly once
   * - viewport-change-end is a separate path; it advances once per user/pan
   *   interaction and must not double-count the same programmatic transition
   * - overlapping requests: stale Promise resolution must not mark newer request as settled
   */
  function deferred<T = boolean>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void
    const promise = new Promise<T>((res) => { resolve = res })
    return { promise, resolve }
  }

  function settled(wrapper: ReturnType<typeof mountCanvas>): string | undefined {
    return wrapper.find('[data-test="graph-canvas"]').attributes('data-viewport-settled')
  }

  it('does not advance settled revision at applyViewport start — only after Promise resolves', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const first = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(first.promise as unknown as ReturnType<typeof vf.setViewport>)

    // Trigger one programmatic viewport transition (toolbar fit-view uses duration 300)
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    // While the transition Promise is still pending, settled must NOT have advanced
    const during = settled(wrapper)
    expect(setViewport).toHaveBeenCalledTimes(1)
    // The bug was: revision incremented immediately at start, so `during` already changed.
    // Correct: during is still undefined (no settled write yet).
    expect(during).toBeUndefined()
    expect(wrapper.emitted('viewport-settled')).toBeUndefined()

    first.resolve(true)
    await first.promise
    // Vue's next microtask chain after .then() must have written exactly once
    await nextTick()
    await nextTick()
    const after = settled(wrapper)
    expect(after).toBe('1')
    expect(wrapper.emitted('viewport-settled')).toHaveLength(1)
  })

  it('while setViewport is pending, settled remains unchanged (no premature write)', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    // Prime with an already-settled transition so baseline is 1
    const d1 = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(d1.promise as unknown as ReturnType<typeof vf.setViewport>)
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    d1.resolve(true)
    await d1.promise
    await nextTick()
    await nextTick()
    expect(settled(wrapper)).toBe('1')

    // Second request: keep it pending indefinitely
    const d2 = deferred<boolean>()
    setViewport.mockReturnValue(d2.promise as unknown as ReturnType<typeof vf.setViewport>)
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(settled(wrapper)).toBe('1')
    // Still 1 even after a few ticks while d2 is unresolved
    await nextTick()
    await nextTick()
    expect(settled(wrapper)).toBe('1')
    expect(wrapper.emitted('viewport-settled')).toHaveLength(1)
  })

  it('after Promise resolves, settled advances exactly once', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const d = deferred<boolean>()
    vi.spyOn(vf, 'setViewport').mockReturnValue(d.promise as unknown as ReturnType<typeof vf.setViewport>)
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(settled(wrapper)).toBeUndefined()
    d.resolve(true)
    await d.promise
    await nextTick()
    await nextTick()
    expect(settled(wrapper)).toBe('1')
    expect(wrapper.emitted('viewport-settled')).toHaveLength(1)
    // No second increment from the same transition
    await nextTick()
    expect(settled(wrapper)).toBe('1')
    expect(wrapper.emitted('viewport-settled')).toHaveLength(1)
  })

  it('overlapping requests: stale resolve must not mark newer request as settled', async () => {
    const wrapper = mountCanvas(viewWithNodes())
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const dA = deferred<boolean>()
    const dB = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(dA.promise as unknown as ReturnType<typeof vf.setViewport>)

    // Request A
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(1)
    expect(settled(wrapper)).toBeUndefined()

    // Request B starts before A resolves
    setViewport.mockReturnValue(dB.promise as unknown as ReturnType<typeof vf.setViewport>)
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(2)
    expect(settled(wrapper)).toBeUndefined()

    // A resolves late — must be ignored
    dA.resolve(true)
    await dA.promise
    await nextTick()
    await nextTick()
    expect(settled(wrapper)).toBeUndefined()
    expect(wrapper.emitted('viewport-settled')).toBeUndefined()

    // B resolves — now exactly one settlement for the latest request
    dB.resolve(true)
    await dB.promise
    await nextTick()
    await nextTick()
    expect(settled(wrapper)).toBe('2')
    expect(wrapper.emitted('viewport-settled')).toHaveLength(1)
  })
})

describe('GraphCanvas one-shot explicit fit revalidation', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useGraphUiStore().initProject(PROJECT_ID)
    vi.restoreAllMocks()
  })

  function deferred<T = boolean>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void
    const promise = new Promise<T>((res) => { resolve = res })
    return { promise, resolve }
  }

  function emptyViewWithRoute(): GraphWorkspaceView {
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
      ],
      nodes: [],
      answers: [],
    })
  }

  function viewWithRealNode(): GraphWorkspaceView {
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
      ],
      nodes: [makeNode({ id: 'n1', projectId: PROJECT_ID })],
      answers: [],
    })
  }

  function pendingOf(runId: string) {
    return {
      routeId: ACTIVE_ROUTE,
      sourceNodeId: null,
      runId,
      status: 'PENDING' as const,
      phase: null,
      message: null,
    }
  }

  function mountPendingCanvas(runId: string) {
    return mount(GraphCanvas, {
      props: {
        view: emptyViewWithRoute(),
        activeNodeId: null,
        submitting: false,
        drafting: false,
        pending: false,
        pendingProjection: pendingOf(runId),
      },
      global: {
        stubs: { VueFlow: VueFlowStub },
      },
    })
  }

  /** Simulates Vue Flow reporting measured dimensions for one flow node. */
  async function reportMeasured(
    wrapper: ReturnType<typeof mountPendingCanvas>,
    nodeId: string,
    width: number,
    height: number,
  ): Promise<void> {
    const vf = useVueFlow('spec-agent-graph-canvas')
    const storeNodes = vf.nodes.value as Array<{ id: string; dimensions?: { width: number; height: number } }>
    let target = storeNodes.find((node) => node.id === nodeId)
    if (!target) {
      target = { id: nodeId }
      storeNodes.push(target)
    }
    target.dimensions = { width, height }
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('update-node-internals', [nodeId])
    await nextTick()
  }

  it('RED1: explicit fit on pending, then measured real replacement revalidates exactly once', async () => {
    const wrapper = mountPendingCanvas('run-1')
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const d1 = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(d1.promise as unknown as ReturnType<typeof vf.setViewport>)

    // Explicit Fit View while only the pending card exists (measured 320x150
    // via fallback-aware collect; the write must use pending geometry).
    await reportMeasured(wrapper, 'pending:run-1', 320, 150)
    await nextTick()
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(1)
    d1.resolve(true)
    await d1.promise
    await nextTick()
    await nextTick()

    // Pending disappears; the real canonical node appears with measured size.
    const d2 = deferred<boolean>()
    setViewport.mockReturnValue(d2.promise as unknown as ReturnType<typeof vf.setViewport>)
    await wrapper.setProps({ view: viewWithRealNode(), pendingProjection: null, activeNodeId: 'n1' })
    await nextTick()
    await reportMeasured(wrapper, 'n1', 320, 286)
    await nextTick()
    await nextTick()

    // The one-shot revalidation must issue exactly one second write using
    // the real node geometry — not the stale pending fit.
    expect(setViewport).toHaveBeenCalledTimes(2)
    d2.resolve(true)
    await d2.promise
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(2)
  })

  it('RED2: replacement with unknown dimensions keeps intent armed until measured', async () => {
    const wrapper = mountPendingCanvas('run-2')
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const d1 = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(d1.promise as unknown as ReturnType<typeof vf.setViewport>)

    await reportMeasured(wrapper, 'pending:run-2', 320, 150)
    await nextTick()
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(1)
    d1.resolve(true)
    await d1.promise
    await nextTick()
    await nextTick()

    // Real node arrives but Vue Flow has not measured it yet: NO revalidation,
    // intent stays armed.
    const dLater = deferred<boolean>()
    setViewport.mockReturnValue(dLater.promise as unknown as ReturnType<typeof vf.setViewport>)
    await wrapper.setProps({ view: viewWithRealNode(), pendingProjection: null, activeNodeId: 'n1' })
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(1)

    // Measurement arrives later: exactly one revalidation fires.
    await reportMeasured(wrapper, 'n1', 320, 286)
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(2)
    dLater.resolve(true)
  })

  it('RED3: user zoom after pending fit cancels revalidation; programmatic settle does not', async () => {
    const wrapper = mountPendingCanvas('run-3')
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const d1 = deferred<boolean>()
    const zoomIn = vi.spyOn(vf, 'zoomIn').mockResolvedValue(true)
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(d1.promise as unknown as ReturnType<typeof vf.setViewport>)

    await reportMeasured(wrapper, 'pending:run-3', 320, 150)
    await nextTick()
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(1)
    // Programmatic settlement of the explicit fit itself must NOT cancel.
    d1.resolve(true)
    await d1.promise
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(1)

    // User zoom is a new viewport intent: cancels the pending-fit intent.
    await wrapper.find('[data-test="zoom-in"]').trigger('click')
    expect(zoomIn).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ view: viewWithRealNode(), pendingProjection: null, activeNodeId: 'n1' })
    await nextTick()
    await reportMeasured(wrapper, 'n1', 320, 286)
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(1)
  })

  it('RED3b: user pan/zoom gesture (viewport-change-end) after pending fit cancels revalidation', async () => {
    const wrapper = mountPendingCanvas('run-3b')
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const d1 = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(d1.promise as unknown as ReturnType<typeof vf.setViewport>)

    await reportMeasured(wrapper, 'pending:run-3b', 320, 150)
    await nextTick()
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(1)
    d1.resolve(true)
    await d1.promise
    await nextTick()
    await nextTick()

    // A genuine user pan/zoom gesture ends: cancels the pending-fit intent.
    const flow = wrapper.findComponent(VueFlowStub)
    flow.vm.$emit('viewport-change-end')
    await nextTick()

    await wrapper.setProps({ view: viewWithRealNode(), pendingProjection: null, activeNodeId: 'n1' })
    await nextTick()
    await reportMeasured(wrapper, 'n1', 320, 286)
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(1)
  })

  it('RED4: revalidation fires exactly once across later unrelated refreshes', async () => {
    const wrapper = mountPendingCanvas('run-4')
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const d1 = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(d1.promise as unknown as ReturnType<typeof vf.setViewport>)

    await reportMeasured(wrapper, 'pending:run-4', 320, 150)
    await nextTick()
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(1)
    d1.resolve(true)
    await d1.promise
    await nextTick()
    await nextTick()

    const d2 = deferred<boolean>()
    setViewport.mockReturnValue(d2.promise as unknown as ReturnType<typeof vf.setViewport>)
    await wrapper.setProps({ view: viewWithRealNode(), pendingProjection: null, activeNodeId: 'n1' })
    await nextTick()
    await reportMeasured(wrapper, 'n1', 320, 286)
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(2)
    d2.resolve(true)
    await d2.promise
    await nextTick()
    await nextTick()

    // Later unrelated refreshes (same view object shape, safeRegion-only
    // prop updates) must NOT trigger a third fit.
    await wrapper.setProps({ view: viewWithRealNode() })
    await nextTick()
    await nextTick()
    await wrapper.setProps({ submitting: true })
    await nextTick()
    await wrapper.setProps({ submitting: false })
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(2)
  })

  it('fit without pending leaves no revalidation intent', async () => {
    const wrapper = mount(GraphCanvas, {
      props: {
        view: viewWithRealNode(),
        activeNodeId: 'n1',
        submitting: false,
        drafting: false,
        pending: false,
      },
      global: {
        stubs: { VueFlow: VueFlowStub },
      },
    })
    const vf = useVueFlow('spec-agent-graph-canvas')
    setCanvasSize(vf)
    const d1 = deferred<boolean>()
    const setViewport = vi.spyOn(vf, 'setViewport').mockReturnValue(d1.promise as unknown as ReturnType<typeof vf.setViewport>)

    await reportMeasured(wrapper, 'n1', 320, 286)
    await nextTick()
    await wrapper.find('[data-test="fit-view"]').trigger('click')
    expect(setViewport).toHaveBeenCalledTimes(1)
    d1.resolve(true)
    await d1.promise
    await nextTick()
    await nextTick()

    // An unrelated refresh afterwards must not produce any automatic fit.
    await wrapper.setProps({ view: viewWithRealNode() })
    await nextTick()
    await nextTick()
    expect(setViewport).toHaveBeenCalledTimes(1)
  })
})
