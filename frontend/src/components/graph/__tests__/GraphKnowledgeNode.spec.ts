import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GraphKnowledgeNode from '@/components/graph/GraphKnowledgeNode.vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { makeGraphWorkspaceView } from '@/test/fixtures'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'
import type { GraphWorkspaceNodeView, GraphWorkspaceRouteView } from '@/api/types'

/**
 * Vue Flow Handle stub: jsdom cannot run the real useVueFlow/useNode
 * context outside a VueFlow instance (same approach as GraphQuestionNode.spec).
 */
const HandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: true,
  render() {
    return h('div', { class: 'vue-flow__handle' })
  },
})

interface Mounted {
  wrapper: ReturnType<typeof mount>
  workspace: ReturnType<typeof useWorkspaceStore>
}

function mountWithRoutes(
  routes: GraphWorkspaceRouteView[],
  dataOverrides: Partial<SpecAgentGraphNodeData> = {},
): Mounted {
  const pinia = createPinia()
  setActivePinia(pinia)
  const workspace = useWorkspaceStore()
  workspace.graphView = makeGraphWorkspaceView({ routes })
  const wrapper = mount(GraphKnowledgeNode, {
    props: { data: nodeData(dataOverrides) },
    global: { stubs: { Handle: HandleStub }, plugins: [pinia] },
  })
  return { wrapper, workspace }
}

function ideaNode(overrides: Partial<GraphWorkspaceNodeView> = {}): GraphWorkspaceNodeView {
  return {
    id: 'idea-1',
    projectId: 'p1',
    parentNodeId: null,
    supersedesNodeId: null,
    question: '',
    purpose: null,
    options: [],
    allowFreeAnswer: false,
    allowMultiSelect: false,
    createdAt: '2026-08-18T00:00:00Z',
    kind: 'KNOWLEDGE',
    subtype: 'IDEA',
    content: { text: '想做一个数据看板' },
    authorKind: 'USER',
    knowledgeStatus: 'PROPOSED',
    userEditableDraft: true,
    ...overrides,
  }
}

function nodeData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
  return {
    node: ideaNode(),
    projectId: 'p1',
    routeIds: [],
    visibleRouteIds: [],
    answers: [],
    routeStates: [],
    primaryAnswer: null,
    answerPresentationMode: 'single-route',
    readingRouteId: null,
    isCurrent: false,
    canAnswer: false,
    isExpanded: false,
    isShared: false,
    isLatest: false,
    qLabel: null,
    visualWeight: 'active',
    ...overrides,
  }
}

function route(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    label: id,
    lifecycleStatus: 'open' as const,
    isActive: false,
    rootNodeId: 'root-1',
    tipNodeId: 'root-1',
    createdFromNodeId: null,
    supersedesRouteId: null,
    replacementOfNodeId: null,
    lineageNodeIds: ['root-1'],
    ...overrides,
  }
}

describe('floating knowledge node next-step actions', () => {
  it('a floating idea offers 继续生成问题 (draft-from-node) and no 接入 buttons', async () => {
    const { wrapper, workspace } = mountWithRoutes([route('r1', { tipNodeId: 'tip-1' })])
    const draftFromNode = vi.fn().mockResolvedValue(true)
    workspace.draftQuestionFromNode = draftFromNode

    expect(wrapper.find('[data-test="floating-hint"]').exists()).toBe(true)
    // 接入只允许手动拖连线：不再渲染任何"接入"按钮。
    expect(wrapper.find('[data-test="connect-to-route"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="connect-and-draft"]').exists()).toBe(false)

    await wrapper.find('[data-test="draft-from-node"]').trigger('click')
    expect(draftFromNode).toHaveBeenCalledWith('idea-1', null)
  })

  it('a floating idea keeps offering 继续生成问题 with multiple open routes (new route, never guesses)', () => {
    const { wrapper } = mountWithRoutes([
      route('r1', { tipNodeId: 'tip-1' }),
      route('r2', { tipNodeId: 'tip-2' }),
    ])
    // 浮动想法的"继续生成问题"开新独立路线,与路线数量无关。
    expect(wrapper.find('[data-test="draft-from-node"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="connect-to-route"]').exists()).toBe(false)
  })

  it('a floating node with no open route still offers 继续生成问题', () => {
    const { wrapper } = mountWithRoutes([])
    const button = wrapper.find('[data-test="draft-from-node"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeUndefined()
  })

  it('an empty draft hides 继续生成问题 until it has content', () => {
    const { wrapper } = mountWithRoutes(
      [route('r1', { tipNodeId: 'tip-1' })],
      { node: ideaNode({ content: {} }) },
    )
    expect(wrapper.find('[data-test="draft-from-node"]').exists()).toBe(false)
  })
})

describe('routed knowledge node next-step actions', () => {
  it('the route tip offers 起草下一个问题 bound to the reading route', async () => {
    const { wrapper, workspace } = mountWithRoutes([route('r1')], {
      routeIds: ['r1'],
      visibleRouteIds: ['r1'],
      readingRouteId: 'r1',
      isTipOfReadingRoute: true,
      node: ideaNode({ parentNodeId: 'root-1' }),
    })
    const draft = vi.fn().mockResolvedValue(true)
    workspace.draftQuestion = draft

    const button = wrapper.find('[data-test="draft-next-question"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(draft).toHaveBeenCalledWith('r1')
  })

  it('a mid-route (non-tip) knowledge node never offers drafting next', () => {
    const { wrapper } = mountWithRoutes([route('r1')], {
      routeIds: ['r1'],
      visibleRouteIds: ['r1'],
      readingRouteId: 'r1',
      isTipOfReadingRoute: false,
      node: ideaNode({ parentNodeId: 'root-1' }),
    })
    expect(wrapper.find('[data-test="draft-next-question"]').exists()).toBe(false)
  })

  it('a routed node keeps 从这里继续 and loses the floating connect buttons', () => {
    const { wrapper } = mountWithRoutes([route('r1')], {
      routeIds: ['r1'],
      visibleRouteIds: ['r1'],
      readingRouteId: 'r1',
      node: ideaNode({ parentNodeId: 'root-1' }),
    })
    expect(wrapper.find('[data-test="continue-node"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="connect-to-route"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="floating-hint"]').exists()).toBe(false)
  })
})
