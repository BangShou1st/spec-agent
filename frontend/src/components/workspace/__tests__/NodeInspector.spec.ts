import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NodeInspector from '@/components/workspace/NodeInspector.vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'

vi.mock('@/api/projects', () => ({ getProject: vi.fn() }))
vi.mock('@/api/workspace', () => ({ getActiveState: vi.fn(), listRoutes: vi.fn() }))
vi.mock('@/api/requirementState', () => ({
  getRequirementState: vi.fn(),
  getRouteRequirementState: vi.fn(),
}))
vi.mock('@/api/graph', () => ({ getProjectGraph: vi.fn() }))
vi.mock('@/api/graphCommands', () => ({
  acceptProposal: vi.fn(),
  rejectProposal: vi.fn(),
}))

import { getProjectGraph } from '@/api/graph'
import { acceptProposal, rejectProposal } from '@/api/graphCommands'

const mockedGetProjectGraph = vi.mocked(getProjectGraph)
const mockedAcceptProposal = vi.mocked(acceptProposal)
const mockedRejectProposal = vi.mocked(rejectProposal)

function nodeData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
  return {
    node: {
      id: 'n1', projectId: 'p1', parentNodeId: null, supersedesNodeId: null,
      question: 'Q?', purpose: null, options: [], allowFreeAnswer: true,
      createdAt: '2026-01-01T00:00:00Z', kind: 'INTERACTION', subtype: 'QUESTION',
      content: {}, authorKind: 'AGENT', knowledgeStatus: null, userEditableDraft: false,
    },
    canonicalNodeId: 'n1',
    projectId: 'p1',
    routeIds: ['rA'],
    visibleRouteIds: ['rA'],
    answers: [],
    routeStates: [],
    primaryAnswer: null,
    answerPresentationMode: 'single-route' as const,
    readingRouteId: 'rA',
    isCurrent: false,
    canAnswer: false,
    isExpanded: false,
    isShared: false,
    isLatest: false,
    qLabel: null,
    visualWeight: 'normal' as const,
    ...overrides,
  }
}

describe('node inspector ask AI proposal', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('shows a visible proposal awaiting approval with accept/reject entry points', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.nodeQuery = {
      nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1',
      status: 'AWAITING_APPROVAL', message: '建议创建节点', proposalId: 'prop-1',
      proposalStatus: null, actionFamily: 'CREATE_NODE',
    }
    const wrapper = mount(NodeInspector, { props: { data: nodeData() } })
    expect(wrapper.find('[data-test="ask-result"]').exists()).toBe(true)
    // 提案可见，且有明确的接受/拒绝入口（不允许只有一句"可查看待确认提案"）。
    expect(wrapper.find('[data-test="accept-proposal"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="reject-proposal"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('CREATE_NODE')
  })

  it('accepting a proposal refreshes the graph and shows the accepted state', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.nodeQuery = {
      nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1',
      status: 'AWAITING_APPROVAL', message: 'm', proposalId: 'prop-1',
      proposalStatus: null, actionFamily: 'CREATE_NODE',
    }
    mockedAcceptProposal.mockResolvedValue({
      proposalId: 'prop-1', status: 'ACCEPTED', actionFamily: 'CREATE_NODE',
      producedNodeId: 'n2', relationId: null,
    })
    mockedGetProjectGraph.mockResolvedValue({
      projectId: 'p1', activeRouteId: 'rA', routes: [], nodes: [], answers: [], relations: [],
    })
    const wrapper = mount(NodeInspector, { props: { data: nodeData() } })
    await wrapper.find('[data-test="accept-proposal"]').trigger('click')
    await flushPromises()
    expect(mockedAcceptProposal).toHaveBeenCalledWith('prop-1')
    // Accept 成功后刷新 canonical Graph（后端可能已产生节点/关系）。
    expect(mockedGetProjectGraph).toHaveBeenCalled()
    expect(store.nodeQuery?.status).toBe('ACCEPTED')
    expect(wrapper.find('[data-test="ask-result"]').text()).toContain('已接受')
  })

  it('rejecting a proposal leaves the graph unchanged and shows the rejected state', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.nodeQuery = {
      nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1',
      status: 'AWAITING_APPROVAL', message: 'm', proposalId: 'prop-1',
      proposalStatus: null, actionFamily: 'CREATE_NODE',
    }
    mockedRejectProposal.mockResolvedValue({ proposalId: 'prop-1', status: 'REJECTED' })
    const wrapper = mount(NodeInspector, { props: { data: nodeData() } })
    await wrapper.find('[data-test="reject-proposal"]').trigger('click')
    await flushPromises()
    expect(mockedRejectProposal).toHaveBeenCalledWith('prop-1')
    // Reject 不改变 Graph：不应刷新 canonical Graph。
    expect(mockedGetProjectGraph).not.toHaveBeenCalled()
    expect(store.nodeQuery?.status).toBe('REJECTED')
    expect(wrapper.find('[data-test="ask-result"]').text()).toContain('已拒绝')
  })
})
