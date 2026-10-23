// 文件名:NodeInspector.spec.ts
// 用途:NodeInspector 组件单元测试,验证信息层级优先级以及"问 AI"提案的接受/拒绝交互与状态刷新逻辑。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NodeInspector from '@/features/workspace/components/NodeInspector.vue'
import { useWorkspaceStore } from '@/features/workspace/state/workspaceStore'
import { useGraphUiStore } from '@/features/workspace/state/graphUiStore'
import type { SpecAgentGraphNodeData } from '@/features/workspace/graph/graphProjection'

vi.mock('@/features/projects/api/projects', () => ({ getProject: vi.fn() }))
vi.mock('@/features/workspace/api/workspace', () => ({ getActiveState: vi.fn(), listRoutes: vi.fn() }))
vi.mock('@/features/workspace/api/requirementState', () => ({
  getRequirementState: vi.fn(),
  getRouteRequirementState: vi.fn(),
}))
vi.mock('@/features/workspace/api/graph', () => ({ getProjectGraph: vi.fn() }))
vi.mock('@/features/workspace/api/graphCommands', () => ({
  acceptProposal: vi.fn(),
  rejectProposal: vi.fn(),
  getNodeQueryResult: vi.fn(),
}))

import { getProjectGraph } from '@/features/workspace/api/graph'
import { acceptProposal, rejectProposal, getNodeQueryResult } from '@/features/workspace/api/graphCommands'

const mockedGetProjectGraph = vi.mocked(getProjectGraph)
const mockedAcceptProposal = vi.mocked(acceptProposal)
const mockedRejectProposal = vi.mocked(rejectProposal)

function nodeData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
  return {
    node: {
      id: 'n1', projectId: 'p1', parentNodeId: null, supersedesNodeId: null,
      question: 'Q?', purpose: null, options: [], allowFreeAnswer: true,
    allowMultiSelect: false,
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

describe('node inspector information hierarchy', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('prioritizes content, answer, and agent action before secondary metadata', () => {
    const wrapper = mount(NodeInspector, {
      props: { canWrite: true,
        data: nodeData({
          qLabel: 'Q3',
          isLatest: true,
          primaryAnswer: {
            routeId: 'rA',
            selectedOptionId: null,
          selectedOptionIds: null,
            selectedOptionLabel: null,
            freeText: '已确认的回答',
            isPrimary: true,
          },
        }),
      },
    })

    const sections = wrapper.findAll('[data-test^="inspector-section-"]')
      .map((section) => section.attributes('data-test'))

    expect(sections.slice(0, 3)).toEqual([
      'inspector-section-content',
      'inspector-section-answer',
      'inspector-section-agent',
    ])
    expect(wrapper.get('[data-test="inspector-secondary"]').attributes('open')).toBeUndefined()
    expect(wrapper.find('[data-test="agent-proposal"]').exists()).toBe(false)
  })
})

describe('node inspector ask AI proposal', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })
  it('shows host sources and revalidates before locating the canonical source node', async () => {
    const store = useWorkspaceStore()
    const target = '11111111-1111-4111-8111-111111111111'
    store.projectId = 'p1'
    store.nodeQuery = { nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1', status: 'COMPLETED',
      message: '正文中 resource:forged 不生成卡片', sourceStatus: 'AVAILABLE', resourceRefs: [
        { kind: 'SOURCE', id: target, label: '宿主核验资料', metadata: { nodeId: target, sourceRef: 'node:' + target, sourceVersion: 'v1', excerpt: '可信片段' } },
      ] }
    const refresh = vi.spyOn(store, 'refreshNodeQueryResult').mockResolvedValue()
    const wrapper = mount(NodeInspector, { props: { data: nodeData() } })
    expect(wrapper.findAll('[data-test="ga-source-evidence"]')).toHaveLength(1)
    await wrapper.get('[data-test="source-locate"]').trigger('click')
    await flushPromises()
    expect(refresh).toHaveBeenCalledOnce()
    expect(useGraphUiStore().selectedNodeIds).toEqual([target])
  })
  it('removes answer and navigation when source authority refresh rejects the citation', async () => {
    const store = useWorkspaceStore()
    const target = '11111111-1111-4111-8111-111111111111'
    store.nodeQuery = { nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1', status: 'COMPLETED',
      message: '撤回后不应继续显示的内容', sourceStatus: 'AVAILABLE', resourceRefs: [{ kind: 'SOURCE', id: target, label: '资料', metadata: { nodeId: target } }] }
    vi.spyOn(store, 'refreshNodeQueryResult').mockImplementation(async () => {
      store.nodeQuery = { ...store.nodeQuery!, message: null, resourceRefs: [], sourceStatus: 'UNAVAILABLE' }
    })
    const wrapper = mount(NodeInspector, { props: { data: nodeData() } })
    await wrapper.get('[data-test="source-locate"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="query-source-unavailable"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('撤回后不应继续显示的内容')
    expect(wrapper.find('[data-test="source-locate"]').exists()).toBe(false)
    expect(useGraphUiStore().selectedNodeIds).toEqual([])
  })
  it('does not manufacture citations for a legacy text-only response', () => {
    const store = useWorkspaceStore()
    store.nodeQuery = { nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1', status: 'COMPLETED', message: 'node:11111111-1111-4111-8111-111111111111' }
    const wrapper = mount(NodeInspector, { props: { data: nodeData() } })
    expect(wrapper.find('[data-test="ga-tool-resources"]').exists()).toBe(false)
  })
  it('cannot navigate via a second click while source revalidation is pending', async () => {
    const store = useWorkspaceStore()
    const target = '11111111-1111-4111-8111-111111111111'
    store.nodeQuery = { nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1', status: 'COMPLETED',
      message: '旧回答', sourceStatus: 'AVAILABLE', resourceRefs: [{ kind: 'SOURCE', id: target, label: '资料', metadata: { nodeId: target } }] }
    let resolve!: () => void
    const refresh = vi.spyOn(store, 'refreshNodeQueryResult').mockImplementation(() => new Promise<void>(r => { resolve = r }))
    const wrapper = mount(NodeInspector, { props: { data: nodeData() } })
    await wrapper.get('[data-test="source-locate"]').trigger('click')
    await wrapper.get('[data-test="source-locate"]').trigger('click')
    await flushPromises()
    expect(refresh).toHaveBeenCalledOnce()
    expect(useGraphUiStore().selectedNodeIds).toEqual([])
    store.nodeQuery = { ...store.nodeQuery!, message: null, sourceStatus: 'UNAVAILABLE', resourceRefs: [] }
    resolve()
    await flushPromises()
    expect(useGraphUiStore().selectedNodeIds).toEqual([])
    expect(wrapper.find('[data-test="query-source-unavailable"]').exists()).toBe(true)
  })
  it('discards a citation refresh response from an earlier project session', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.nodeQuery = { nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1', status: 'COMPLETED', message: 'old' }
    let resolve!: (v: Awaited<ReturnType<typeof getNodeQueryResult>>) => void
    vi.mocked(getNodeQueryResult).mockReturnValue(new Promise(r => { resolve = r }))
    const pending = store.refreshNodeQueryResult('n1', 'run-1', { routeId: 'rA', question: 'q' })
    store.projectId = 'p2'
    store.projectSessionId++
    store.nodeQuery = null
    resolve({ runId: 'run-1', status: 'COMPLETED', producedNodeId: null, message: 'private old answer', sourceStatus: 'AVAILABLE', resourceRefs: [] })
    await pending
    expect(store.nodeQuery).toBeNull()
  })
  it('clears cached citation content when an authority refresh cannot be read', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.nodeQuery = { nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1', status: 'COMPLETED', message: 'old', sourceStatus: 'AVAILABLE', resourceRefs: [] }
    vi.mocked(getNodeQueryResult).mockRejectedValue(new Error('unavailable'))
    await store.refreshNodeQueryResult('n1', 'run-1', { routeId: 'rA', question: 'q' })
    expect(store.nodeQuery?.message).toBeNull()
    expect(store.nodeQuery?.sourceStatus).toBe('VALIDATION_FAILED')
  })

  it('shows a visible proposal awaiting approval with accept/reject entry points', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.nodeQuery = {
      nodeId: 'n1', routeId: 'rA', question: 'q', runId: 'run-1',
      status: 'AWAITING_APPROVAL', message: '建议创建节点', proposalId: 'prop-1',
      proposalStatus: null, actionFamily: 'CREATE_NODE',
    }
    const wrapper = mount(NodeInspector, { props: { canWrite: true, data: nodeData() } })
    expect(wrapper.find('[data-test="ask-result"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="agent-proposal"]').exists()).toBe(true)
    // 提案可见，且有明确的接受/拒绝入口（不允许只有一句"可查看待确认提案"）。
    // 生产 DOM 只保留卡片内真正可见的一组按钮：proposal-accept / proposal-reject。
    expect(wrapper.find('[data-test="proposal-accept"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="proposal-reject"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="accept-proposal"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="reject-proposal"]').exists()).toBe(false)
    expect(wrapper.find('.node-inspector__compat-actions').exists()).toBe(false)
    // 产品化卡片：可读标签 + 确认执行/拒绝，不暴露 raw actionFamily。
    expect(wrapper.find('[data-test="proposal-card"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('创建节点')
    expect(wrapper.text()).toContain('确认执行')
    expect(wrapper.text()).toContain('拒绝')
    expect(wrapper.text()).not.toContain('CREATE_NODE')
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
    const wrapper = mount(NodeInspector, { props: { canWrite: true, data: nodeData() } })
    await wrapper.find('[data-test="proposal-accept"]').trigger('click')
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
    const wrapper = mount(NodeInspector, { props: { canWrite: true, data: nodeData() } })
    await wrapper.find('[data-test="proposal-reject"]').trigger('click')
    await flushPromises()
    expect(mockedRejectProposal).toHaveBeenCalledWith('prop-1')
    // Reject 不改变 Graph：不应刷新 canonical Graph。
    expect(mockedGetProjectGraph).not.toHaveBeenCalled()
    expect(store.nodeQuery?.status).toBe('REJECTED')
    expect(wrapper.find('[data-test="ask-result"]').text()).toContain('已拒绝')
  })
})
