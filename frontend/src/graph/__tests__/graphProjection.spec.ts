import { describe, expect, it } from 'vitest'
import { projectGraph, getNodeRouteMembership, getLineageEdgeMembership, selectPrimaryAnswer, getVisibleRouteIds } from '@/graph/graphProjection'
import type { GraphWorkspaceNodeView, GraphWorkspaceRelationView, GraphWorkspaceView, RouteLifecycleStatus } from '@/api/types'
import { HORIZONTAL_GAP, VERTICAL_GAP } from '@/graph/graphLayout'
import type { GraphPosition } from '@/graph/graphTypes'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'

const PROJECT_ID = 'p1'
const ACTIVE_ROUTE_ID = 'rA'
const ROUTE_B_ID = 'rB'
const ROUTE_C_ID = 'rC'
const ROUTE_D_ID = 'rD'

function node(id: string, parentNodeId: string | null, supersedesNodeId: string | null = null) {
  return {
    id,
    projectId: PROJECT_ID,
    parentNodeId,
    supersedesNodeId,
    question: 'Q ' + id,
    purpose: null,
    options: [],
    allowFreeAnswer: true,
    createdAt: '2026-08-18T00:00:00Z',
    kind: 'INTERACTION' as const,
    subtype: 'QUESTION',
    content: {},
    authorKind: 'AGENT' as const,
    knowledgeStatus: null,
    userEditableDraft: false,
  }
}

function route(id: string, lifecycleStatus: RouteLifecycleStatus, lineageNodeIds: string[]) {
  return {
    id,
    label: id,
    lifecycleStatus,
    isActive: id === ACTIVE_ROUTE_ID,
    rootNodeId: lineageNodeIds[0] ?? null,
    tipNodeId: lineageNodeIds[lineageNodeIds.length - 1] ?? null,
    createdFromNodeId: null,
    supersedesRouteId: null,
    replacementOfNodeId: id === ROUTE_D_ID ? 'b' : null,
    lineageNodeIds,
  }
}

function answer(routeId: string, nodeId: string, freeText = 'answer ' + routeId + nodeId) {
  return {
    id: routeId + '-' + nodeId + '-a1',
    routeId,
    nodeId,
    selectedOptionId: null,
    freeText,
    createdAt: '2026-08-18T00:00:00Z',
  }
}

/**
 * Route A: A -> B -> C (Active, open)
 * Route B: A -> B -> D (open fork)
 * Route C: A -> B -> E (archived fork)
 * Route D: A -> B' (open replacement: B' supersedes B)
 */
function fixture(): GraphWorkspaceView {
  return {
    projectId: PROJECT_ID,
    activeRouteId: ACTIVE_ROUTE_ID,
    routes: [
      route(ACTIVE_ROUTE_ID, 'open', ['a', 'b', 'c']),
      route(ROUTE_B_ID, 'open', ['a', 'b', 'd']),
      route(ROUTE_C_ID, 'archived', ['a', 'b', 'e']),
      route(ROUTE_D_ID, 'open', ['a', 'bprime']),
    ],
    nodes: [
      node('a', null),
      node('b', 'a'),
      node('c', 'b'),
      node('d', 'b'),
      node('bprime', 'a', 'b'),
      node('e', 'bprime'),
    ],
    answers: [
      answer(ACTIVE_ROUTE_ID, 'a', 'active a answer'),
      answer(ACTIVE_ROUTE_ID, 'b', 'active b answer'),
      answer(ROUTE_B_ID, 'b', 'route b answer'),
      answer(ROUTE_C_ID, 'e', 'archived e answer'),
    ],
    relations: [],
  }
}

const DEFAULT_FILTERS: Record<RouteLifecycleStatus, boolean> = {
  open: true,
  superseded: true,
  archived: true,
  deleted: false,
}

function uiState(overrides: Partial<{
  focusRouteId: string | null
  lifecycleFilters: Record<RouteLifecycleStatus, boolean>
  routeDisplayStates: Record<string, 'normal' | 'dimmed' | 'hidden'>
  expandedNodeIds: string[]
  showRelationLayer: boolean
}> = {}) {
  return {
    focusRouteId: null,
    lifecycleFilters: { ...DEFAULT_FILTERS },
    routeDisplayStates: {},
    expandedNodeIds: [],
    showRelationLayer: false,
    ...overrides,
  }
}

function project(overrides: {
  activeNodeId?: string | null
  uiState?: ReturnType<typeof uiState>
  savedPositions?: Record<string, GraphPosition>
} = {}) {
  return projectGraph({
    view: fixture(),
    activeNodeId: overrides.activeNodeId ?? 'c',
    uiState: overrides.uiState ?? uiState(),
    savedPositions: overrides.savedPositions ?? {},
  })
}

describe('graph projection', () => {
  it('renders shared nodes a and b exactly once', () => {
    const result = project()
    const ids = result.nodes.map((n) => n.id)
    expect(ids.filter((id) => id === 'a')).toHaveLength(1)
    expect(ids.filter((id) => id === 'b')).toHaveLength(1)
    expect(ids).toEqual(expect.arrayContaining(['a', 'b', 'c', 'd', 'bprime', 'e']))
  })

  it('projects an in-flight run as a virtual pending card and continuation edge', () => {
    const result = projectGraph({
      view: fixture(),
      activeNodeId: 'c',
      uiState: uiState(),
      savedPositions: {},
      pending: {
        routeId: ACTIVE_ROUTE_ID,
        sourceNodeId: 'c',
        runId: 'run-pending',
        status: 'RUNNING',
        phase: 'DECIDING',
        message: null,
      },
    })
    const pending = result.nodes.find((candidate) => candidate.id === 'pending:run-pending')
    expect(pending).toBeDefined()
    expect((pending?.data as SpecAgentGraphNodeData).runtimeStatus).toBe('RUNNING')
    expect((pending?.data as SpecAgentGraphNodeData).isLatest).toBe(true)
    expect(result.edges.find((edge) => edge.id === 'c->pending:run-pending')).toBeDefined()
  })

  it('renders the a->b lineage edge once with route memberships A/B/C', () => {
    const result = project()
    const edge = result.edges.find((e) => e.id === 'a->b')
    expect(edge).toBeDefined()
    expect(edge?.data?.kind).toBe('lineage')
    expect(edge?.data?.routeIds).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_C_ID])
    expect(edge?.data?.visibleRouteIds).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_C_ID])
  })

  it('shared canonical Answer content is identical across Focus Main / Branch / none', () => {
    // 最终模型：canonical Question Node 全局只有一个不可变 Answer 身份。Focus
    // 只改变阅读上下文，绝不改变展示的 Answer 内容。node b 被 A/B/C 共享，这里
    // 直接断言三条 Focus 状态下 primaryAnswer 内容完全一致。
    const freeText = (res: ReturnType<typeof project>) =>
      (res.nodes.find((n) => n.id === 'b')!.data as SpecAgentGraphNodeData).primaryAnswer?.freeText
    const focusA = freeText(project({ uiState: uiState({ focusRouteId: ACTIVE_ROUTE_ID }) }))
    const focusB = freeText(project({ uiState: uiState({ focusRouteId: ROUTE_B_ID }) }))
    const noFocus = freeText(project())
    expect(focusA).toBe(noFocus)
    expect(focusB).toBe(noFocus)
    // 三条路线引用同一 Answer（route A 的回答），内容稳定。
    expect(focusB).toBe('active b answer')
  })

  it('without focus a shared node shows its single Answer identity, never an active-route fallback', () => {
    // 该 fixture 中 b 被三条 route 共享 (A/B/C)；A 有 answer，B/C 没有。
    // 最终模型下 canonical Question 只有一个 immutable Answer identity：
    // 无 focus 时直接展示该答案（不存在按路线分歧的等待模式）。
    const result = project()
    const bNode = result.nodes.find((n) => n.id === 'b')!
    const data = bNode.data as SpecAgentGraphNodeData
    expect(data.primaryAnswer?.routeId).toBe(ACTIVE_ROUTE_ID)
    expect(data.answerPresentationMode).toBe('single-route')
    // "neutral" 只是无 focus 的 shared 节点视觉样式，不代表答案呈现分叉。
    expect(data.routeStates.some((s) => s.answer !== null)).toBe(true)
  })

  it('hiding route B removes only its exclusive node d', () => {
    const result = project({
      uiState: uiState({ routeDisplayStates: { [ROUTE_B_ID]: 'hidden' } }),
    })
    const ids = result.nodes.map((n) => n.id)
    expect(ids).not.toContain('d')
    expect(ids).toContain('a')
    expect(ids).toContain('b')
    const aData = result.nodes.find((n) => n.id === 'a')?.data as SpecAgentGraphNodeData
    expect(aData.routeIds).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_C_ID, ROUTE_D_ID])
    expect(aData.visibleRouteIds).toEqual([ACTIVE_ROUTE_ID, ROUTE_C_ID, ROUTE_D_ID])
    expect(aData.routeMembership?.map((membership) => membership.routeId)).toEqual([
      ACTIVE_ROUTE_ID,
      ROUTE_C_ID,
      ROUTE_D_ID,
    ])
    expect(result.edges.find((e) => e.id === 'a->b')?.data?.routeIds).toEqual([
      ACTIVE_ROUTE_ID,
      ROUTE_B_ID,
      ROUTE_C_ID,
    ])
    expect(result.edges.find((e) => e.id === 'a->b')?.data?.visibleRouteIds).toEqual([
      ACTIVE_ROUTE_ID,
      ROUTE_C_ID,
    ])
  })

  it('archived filter off removes e while shared nodes remain', () => {
    const filters = { ...DEFAULT_FILTERS, archived: false }
    const result = project({ uiState: uiState({ lifecycleFilters: filters }) })
    const ids = result.nodes.map((n) => n.id)
    expect(ids).not.toContain('e')
    expect(ids).toContain('a')
    expect(ids).toContain('b')
    // bprime belongs to the open replacement route and stays visible.
    expect(ids).toContain('bprime')
    const aData = result.nodes.find((n) => n.id === 'a')?.data as SpecAgentGraphNodeData
    expect(aData.routeIds).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_C_ID, ROUTE_D_ID])
    expect(aData.visibleRouteIds).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_D_ID])
    expect(aData.routeMembership?.map((membership) => membership.routeId)).toEqual([
      ACTIVE_ROUTE_ID,
      ROUTE_B_ID,
      ROUTE_D_ID,
    ])
    expect(result.edges.find((e) => e.id === 'a->b')?.data?.visibleRouteIds).toEqual([
      ACTIVE_ROUTE_ID,
      ROUTE_B_ID,
    ])
  })

  it('manual hidden state can never hide active-route elements', () => {
    const result = project({
      uiState: uiState({ routeDisplayStates: { [ACTIVE_ROUTE_ID]: 'hidden' } }),
    })
    const ids = result.nodes.map((n) => n.id)
    expect(ids).toContain('a')
    expect(ids).toContain('b')
    expect(ids).toContain('c')
  })

  it('active membership has the highest visual weight even with a persisted dim', () => {
    const result = project({
      uiState: uiState({ routeDisplayStates: { [ACTIVE_ROUTE_ID]: 'dimmed' } }),
    })
    const aNode = result.nodes.find((n) => n.id === 'a')!
    expect((aNode.data as SpecAgentGraphNodeData).visualWeight).toBe('active')
  })

  it('only the unanswered active node has canAnswer=true', () => {
    const result = project()
    const byId = new Map(result.nodes.map((n) => [n.id, n.data as SpecAgentGraphNodeData]))
    expect(byId.get('c')?.canAnswer).toBe(true)
    expect(byId.get('c')?.isCurrent).toBe(true)
    for (const id of ['a', 'b', 'd', 'bprime', 'e']) {
      expect(byId.get(id)?.canAnswer).toBe(false)
    }
  })

  it('replacement provenance is not rendered as a permanent graph edge', () => {
    const result = project()
    const repl = result.edges.find((e) => e.id === 'replacement:b->bprime')
    expect(repl).toBeUndefined()
    // lineage a->bprime exists for the replacement route D
    const lineage = result.edges.find((e) => e.id === 'a->bprime')
    expect(lineage?.data?.routeIds).toEqual([ROUTE_D_ID])
    // b is never the lineage parent of bprime
    expect(result.edges.find((e) => e.id === 'b->bprime')).toBeUndefined()
    const bprime = result.nodes.find((n) => n.id === 'bprime')!
    expect((bprime.data as SpecAgentGraphNodeData).node.parentNodeId).toBe('a')
  })

  it('exposes drag handle and non-connectable flow nodes', () => {
    const result = project()
    for (const n of result.nodes) {
      expect(n.type).toBe('question')
      expect(n.dragHandle).toBe('.graph-question-node__header')
    }
  })

  it('getNodeRouteMembership maps every node to its route ids', () => {
    const membership = getNodeRouteMembership(fixture())
    expect(membership.get('a')).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_C_ID, ROUTE_D_ID])
    expect(membership.get('d')).toEqual([ROUTE_B_ID])
    expect(membership.get('bprime')).toEqual([ROUTE_D_ID])
  })

  it('getLineageEdgeMembership dedupes shared lineage edges', () => {
    const membership = getLineageEdgeMembership(fixture())
    expect(membership.get('a->b')).toEqual({
      source: 'a',
      target: 'b',
      routeIds: [ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_C_ID],
    })
    expect(membership.get('a->bprime')).toEqual({
      source: 'a',
      target: 'bprime',
      routeIds: [ROUTE_D_ID],
    })
  })

  it('selectPrimaryAnswer: canonical Answer is stable across focus routes (never route-specific selection)', () => {
    const answers = [
      { nodeId: 'x', routeId: ACTIVE_ROUTE_ID, selectedOptionId: null, selectedOptionLabel: null, freeText: 'shared', isPrimary: false },
      { nodeId: 'x', routeId: ROUTE_B_ID, selectedOptionId: null, selectedOptionLabel: null, freeText: 'shared', isPrimary: false },
    ]
    // 不管 focus 在哪条路线，返回的始终是同一个 canonical 内容。
    expect(selectPrimaryAnswer('x', answers, ROUTE_B_ID, ACTIVE_ROUTE_ID, [])?.freeText).toBe('shared')
    expect(selectPrimaryAnswer('x', answers, ACTIVE_ROUTE_ID, ACTIVE_ROUTE_ID, [])?.freeText).toBe('shared')
    expect(selectPrimaryAnswer('x', answers, null, ACTIVE_ROUTE_ID, [])?.freeText).toBe('shared')
  })

  it('selectPrimaryAnswer: returns the single canonical Answer identity, ignoring the focus route', () => {
    const answers = [
      { nodeId: 'x', routeId: ACTIVE_ROUTE_ID, selectedOptionId: null, selectedOptionLabel: null, freeText: 'canonical', isPrimary: false },
      { nodeId: 'x', routeId: ROUTE_B_ID, selectedOptionId: null, selectedOptionLabel: null, freeText: 'other', isPrimary: false },
    ]
    // 即使 focus=B，返回的仍是同一 canonical 内容（全局唯一不可变身份）。
    expect(selectPrimaryAnswer('x', answers, ROUTE_B_ID, ACTIVE_ROUTE_ID, [])?.freeText).toBe('canonical')
    expect(selectPrimaryAnswer('x', answers, null, ACTIVE_ROUTE_ID, [])?.freeText).toBe('canonical')
  })

  it('selectPrimaryAnswer: single-route (non-shared) returns the route answer even without focus', () => {
    const answers = [
      { nodeId: 'x', routeId: ACTIVE_ROUTE_ID, selectedOptionId: 'opt-a', selectedOptionLabel: 'A', freeText: null, isPrimary: false },
    ]
    // 唯一 route，no focus → primary 来自该 route，不借用 active fallback。
    const primary = selectPrimaryAnswer('x', answers, null, ACTIVE_ROUTE_ID, [])
    expect(primary?.routeId).toBe(ACTIVE_ROUTE_ID)
  })

  it('selectPrimaryAnswer: multi-route + no focus returns the first referenced Answer (single identity)', () => {
    // 最终模型：一个 canonical Question 全局只有一个 immutable Answer，
    // 多条 route 的引用享有同一内容。无 focus 时直接展示首个引用。
    const answers = [
      { nodeId: 'x', routeId: ACTIVE_ROUTE_ID, selectedOptionId: 'opt-a', selectedOptionLabel: 'A', freeText: 'same', isPrimary: false },
      { nodeId: 'x', routeId: ROUTE_B_ID, selectedOptionId: 'opt-a', selectedOptionLabel: 'A', freeText: 'same', isPrimary: false },
    ]
    const primary = selectPrimaryAnswer('x', answers, null, ACTIVE_ROUTE_ID, [])
    expect(primary?.routeId).toBe(ACTIVE_ROUTE_ID)
    expect(primary?.freeText).toBe('same')
  })

  it('selectPrimaryAnswer: multi-route waiting node returns null primary (nothing to show)', () => {
    // 共享节点任何 route 都还没回答时,primary 保持 null,卡片显示等待。
    const view = {
      projectId: PROJECT_ID,
      activeRouteId: ACTIVE_ROUTE_ID,
      routes: [route(ACTIVE_ROUTE_ID, 'open', ['a', 'b']), route(ROUTE_B_ID, 'open', ['a', 'b'])],
      nodes: [node('a', null), node('b', 'a')],
      answers: [],
      relations: [],
    }
    const result = projectGraph({
      view,
      activeNodeId: 'b',
      uiState: uiState(),
      savedPositions: {},
    })
    const b = result.nodes.find((n) => n.id === 'b')!
    const data = b.data as SpecAgentGraphNodeData
    expect(data.primaryAnswer).toBeNull()
    expect(data.answerPresentationMode).toBe('single-route')
  })

  it('projects floating route-less nodes as always-visible standalone instances', () => {
    const view = fixture()
    const floatingNode: GraphWorkspaceNodeView = {
      id: 'float-1',
      projectId: view.projectId,
      parentNodeId: null,
      supersedesNodeId: null,
      question: '',
      purpose: null,
      options: [],
      allowFreeAnswer: false,
      createdAt: '2026-01-01T00:00:00Z',
      kind: 'KNOWLEDGE',
      subtype: 'IDEA',
      content: {},
      authorKind: 'USER',
      knowledgeStatus: 'PROPOSED',
      userEditableDraft: true,
    }
    view.nodes.push(floatingNode)
    const result = projectGraph({
      view,
      activeNodeId: 'b',
      uiState: {
        focusRouteId: null,
        lifecycleFilters: { open: true, superseded: true, archived: true, deleted: false },
        routeDisplayStates: {},
        expandedNodeIds: [],
      },
      savedPositions: {},
    })
    const floating = result.nodes.find((node) => node.id === 'float-1')
    expect(floating).toBeDefined()
    const data = floating!.data as SpecAgentGraphNodeData
    expect(data.routeIds).toEqual([])
    expect(data.isShared).toBe(false)
    expect(data.canAnswer).toBe(false)
    expect(data.visualWeight).toBe('normal')
    // 浮动节点没有任何 lineage 边。
    expect(result.edges.some((edge) => edge.source === 'float-1' || edge.target === 'float-1')).toBe(false)
  })

  it('renders manual semantic relations as distinct relation edges when relation layer is ON', () => {
    const view = fixture()
    const relation: GraphWorkspaceRelationView = {
      id: 'rel-1',
      sourceNodeId: 'b',
      targetNodeId: 'bprime',
      relationType: 'RELATED_TO',
      origin: 'USER',
      createdByProposalId: null,
      createdAt: '2026-01-01T00:00:00Z',
    }
    view.relations = [relation]
    const result = projectGraph({
      view,
      activeNodeId: 'b',
      uiState: {
        focusRouteId: null,
        lifecycleFilters: { open: true, superseded: true, archived: true, deleted: false },
        routeDisplayStates: {},
        expandedNodeIds: [],
        showRelationLayer: true,
      },
      savedPositions: {},
    })
    const relationEdge = result.edges.find((edge) => edge.id === 'relation:rel-1')
    expect(relationEdge).toBeDefined()
    expect(relationEdge?.data?.kind).toBe('relation')
    expect(relationEdge?.data?.relationType).toBe('RELATED_TO')
    expect(relationEdge?.class).toContain('graph-edge--relation')
  })

  it('relation endpoint uses focus visual instance when focused route has a unique visible instance', () => {
    // Shared node b belongs to routes A, B, C and to replacement D's parent a.
    // With focusRouteId = B, the relation edge should anchor on b's instance
    // (B-visible), and that instance has its own visualNodeKey.
    const view = fixture()
    view.relations = [{
      id: 'rel-1', sourceNodeId: 'a', targetNodeId: 'b', relationType: 'RELATED_TO',
      origin: 'USER', createdByProposalId: null, createdAt: '2026-01-01T00:00:00Z',
    }]
    const result = projectGraph({
      view,
      activeNodeId: 'c',
      uiState: uiState({ focusRouteId: ROUTE_B_ID, showRelationLayer: true }),
      savedPositions: {},
    })
    const rel = result.edges.find((edge) => edge.id === 'relation:rel-1')
    expect(rel).toBeDefined()
    // visual instance for shared node on B is "b" (same canonical id in this
    // simple case). The relation edge endpoint must anchor on a visible
    // instance: the projection must NOT silently pick Active (A) just
    // because A has the active route membership.
    expect(rel?.source).toBe('a')
    expect(rel?.target).toBe('b')
  })

  it('default relation layer is OFF: no relation edges on canvas even with focus + visible instance', () => {
    // Canvas must never be cluttered with semantic relations unless the user
    // explicitly opens the relation layer. The canonical fact still lives in
    // view.relations and is reachable from the Inspector.
    const view = fixture()
    view.relations = [{
      id: 'rel-1', sourceNodeId: 'a', targetNodeId: 'b', relationType: 'RELATED_TO',
      origin: 'USER', createdByProposalId: null, createdAt: '2026-01-01T00:00:00Z',
    }]
    const result = projectGraph({
      view,
      activeNodeId: 'c',
      uiState: uiState({ focusRouteId: ROUTE_B_ID }),
      savedPositions: {},
    })
    expect(result.edges.find((edge) => edge.id === 'relation:rel-1')).toBeUndefined()
    expect(view.relations).toHaveLength(1)
  })

  it('relation edge endpoint is suppressed when no focus and the endpoint has no visible instance', () => {
    // Force a relation whose target lives only on an archived route; with
    // the archived filter OFF the target has zero visible instances, so the
    // edge must NOT be drawn presentationally.
    const view = fixture()
    const filters = { ...DEFAULT_FILTERS, archived: false }
    view.relations = [{
      id: 'rel-1', sourceNodeId: 'a', targetNodeId: 'e', relationType: 'RELATED_TO',
      origin: 'USER', createdByProposalId: null, createdAt: '2026-01-01T00:00:00Z',
    }]
    const result = projectGraph({
      view,
      activeNodeId: 'c',
      uiState: uiState({ lifecycleFilters: filters }),
      savedPositions: {},
    })
    const rel = result.edges.find((edge) => edge.id === 'relation:rel-1')
    expect(rel).toBeUndefined()
  })

  it('getVisibleRouteIds honors lifecycle filters and manual hide', () => {
    const view = fixture()
    const visible = getVisibleRouteIds(view, {
      lifecycleFilters: { ...DEFAULT_FILTERS, archived: false },
      routeDisplayStates: { [ROUTE_B_ID]: 'hidden' },
    })
    expect(visible.has(ACTIVE_ROUTE_ID)).toBe(true)
    expect(visible.has(ROUTE_B_ID)).toBe(false)
    expect(visible.has(ROUTE_C_ID)).toBe(false)
  })
})

describe('incremental placement on canonical refresh', () => {
  it('keeps manually moved parents and siblings untouched and places a new child to the parent right', () => {
    // 已有 graph：a -> b -> c；parent b 已手工移动到 {900, 600}，sibling c 已有位置。
    // 已有 graph：a -> b -> c（route A，当前）；a/b 被第二条路线共享。
    // parent b 已手工移动到 {900, 600}，sibling c 已有位置。
    const view = {
      projectId: PROJECT_ID,
      activeRouteId: ACTIVE_ROUTE_ID,
      routes: [route(ACTIVE_ROUTE_ID, 'open', ['a', 'b', 'c'])],
      nodes: [node('a', null), node('b', 'a'), node('c', 'b')],
      answers: [],
      relations: [],
    }
    const saved: Record<string, GraphPosition> = {
      a: { x: 0, y: 0 },
      b: { x: 900, y: 600 },
      c: { x: 1260, y: 600 },
    }
    // canonical refresh：新分支路线 B 新增 child d（挂在 b 下）。
    const refreshed = {
      ...view,
      routes: [route(ACTIVE_ROUTE_ID, 'open', ['a', 'b', 'c']), route(ROUTE_B_ID, 'open', ['a', 'b', 'd'])],
      nodes: [...view.nodes, node('d', 'b')],
    }
    const result = projectGraph({
      view: refreshed,
      activeNodeId: null,
      uiState: uiState(),
      savedPositions: saved,
    })
    const pos = (id: string) => result.nodes.find((n) => n.id === id)?.position
    // 所有已有节点坐标完全不变。
    expect(pos('a')).toEqual({ x: 0, y: 0 })
    expect(pos('b')).toEqual({ x: 900, y: 600 })
    expect(pos('c')).toEqual({ x: 1260, y: 600 })
    // 新 child 位于 parent 右侧（parent.x + HORIZONTAL_GAP）。
    expect(pos('d')?.x).toBe(900 + HORIZONTAL_GAP)
    // 直接槽位（y=600）被 sibling c 占用：d 落在最近可用垂直槽位。
    expect(pos('d')?.y).toBe(600 + VERTICAL_GAP)
  })

  it('first-ever projection without saved positions still lays out deterministically left-to-right', () => {
    const view = {
      projectId: PROJECT_ID,
      activeRouteId: ACTIVE_ROUTE_ID,
      routes: [route(ACTIVE_ROUTE_ID, 'open', ['a', 'b', 'c'])],
      nodes: [node('a', null), node('b', 'a'), node('c', 'b')],
      answers: [],
      relations: [],
    }
    const result = projectGraph({
      view,
      activeNodeId: null,
      uiState: uiState(),
      savedPositions: {},
    })
    const pos = (id: string) => result.nodes.find((n) => n.id === id)?.position
    expect(pos('a')).toEqual({ x: 0, y: 0 })
    expect(pos('b')).toEqual({ x: HORIZONTAL_GAP, y: 0 })
    expect(pos('c')).toEqual({ x: HORIZONTAL_GAP * 2, y: 0 })
  })
})

describe('focus reading-context visual semantics (Active=A, Focus=B)', () => {
  it('B exclusive elements get focus weight, A exclusive history is dimmed, shared nodes take focused presentation', () => {
    const result = project({ uiState: uiState({ focusRouteId: ROUTE_B_ID }) })
    const weight = (id: string) =>
      (result.nodes.find((n) => n.id === id)?.data as SpecAgentGraphNodeData).visualWeight
    // B 专属节点/边 → focus（阅读上下文突出）。
    expect(weight('d')).toBe('focus')
    // A 专属历史节点/边 → dimmed；Focus 不会让 Active 路线保持最高视觉权重。
    expect(weight('c')).toBe('dimmed')
    expect(weight('e')).toBe('dimmed')
    // 共享 A+B 节点 → focused/read context 呈现优先。
    expect(weight('a')).toBe('focus')
    expect(weight('b')).toBe('focus')
    const edge = (id: string) => result.edges.find((e) => e.id === id)
    expect(edge('a->b')?.data?.visualWeight).toBe('focus')
    expect(edge('b->c')?.data?.visualWeight).toBe('dimmed')
  })

  it('Active current node stays clearly current while Focus dims its route visuals', () => {
    const result = project({ activeNodeId: 'c', uiState: uiState({ focusRouteId: ROUTE_B_ID }) })
    const c = result.nodes.find((n) => n.id === 'c')!
    const data = c.data as SpecAgentGraphNodeData
    // 路线视觉不抢过 Focus，但 current 标记与可回答性保留。
    expect(data.visualWeight).toBe('dimmed')
    expect(data.isCurrent).toBe(true)
    expect(data.canAnswer).toBe(true)
  })

  it('exiting Focus restores the previous manual dim state and active weight', () => {
    const manual: Record<string, 'normal' | 'dimmed' | 'hidden'> = { [ROUTE_B_ID]: 'dimmed' }
    const focused = project({
      uiState: uiState({ focusRouteId: ROUTE_C_ID, routeDisplayStates: manual }),
    })
    const weightF = (id: string) =>
      (focused.nodes.find((n) => n.id === id)?.data as SpecAgentGraphNodeData).visualWeight
    expect(weightF('e')).toBe('focus')
    expect(weightF('d')).toBe('dimmed')
    // 取消聚焦：B 回到手工 dim 状态（不是 normal），A 恢复 active。
    const restored = project({ uiState: uiState({ routeDisplayStates: manual }) })
    const weightR = (id: string) =>
      (restored.nodes.find((n) => n.id === id)?.data as SpecAgentGraphNodeData).visualWeight
    expect(weightR('d')).toBe('dimmed')
    expect(weightR('c')).toBe('active')
    expect(weightR('e')).toBe('normal')
  })
})

describe('shared node route-specific waiting state', () => {
  function sharedView() {
    return {
      projectId: PROJECT_ID,
      activeRouteId: ACTIVE_ROUTE_ID,
      routes: [route(ACTIVE_ROUTE_ID, 'open', ['a', 'b']), route(ROUTE_B_ID, 'open', ['a', 'b'])],
      nodes: [node('a', null), node('b', 'a')],
      answers: [answer(ACTIVE_ROUTE_ID, 'b', 'A answer on shared b')],
      relations: [],
    }
  }

  it('Shared answered Question always shows the same canonical Answer regardless of Focus (B has no answer)', () => {
    const result = projectGraph({
      view: sharedView(),
      activeNodeId: null,
      uiState: uiState({ focusRouteId: ROUTE_B_ID }),
      savedPositions: {},
    })
    const b = result.nodes.find((n) => n.id === 'b')!
    const data = b.data as SpecAgentGraphNodeData
    expect(data.readingRouteId).toBe(ROUTE_B_ID)
    // 最终模型：canonical Question 只有一个不可变 Answer 身份。即使 Focus=B
    // 且 B 未答，也展示 A 的同一答案（绝不返回 null 制造 route-specific 分叉）。
    expect(data.primaryAnswer?.freeText).toBe('A answer on shared b')
    // routeStates 如实记录每条路线 answer 存在（不伪造 B 的 primary）。
    expect(data.routeStates.map((s) => s.routeId)).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID])
    expect(data.routeStates.find((s) => s.routeId === ACTIVE_ROUTE_ID)?.answer?.freeText).toBe('A answer on shared b')
    expect(data.routeStates.find((s) => s.routeId === ROUTE_B_ID)?.answer).toBeNull()
  })

  it('shared node with inherited answer on a non-active route still surfaces the real per-route state', () => {
    const view = {
      projectId: PROJECT_ID,
      activeRouteId: ROUTE_B_ID,
      routes: [route(ACTIVE_ROUTE_ID, 'open', ['a', 'b']), route(ROUTE_B_ID, 'open', ['a', 'b'])],
      nodes: [node('a', null), node('b', 'a')],
      answers: [answer(ACTIVE_ROUTE_ID, 'b', 'old route answer')],
      relations: [],
    }
    const result = projectGraph({
      view,
      activeNodeId: 'b',
      uiState: uiState(),
      savedPositions: {},
    })
    const b = result.nodes.find((n) => n.id === 'b')!
    const data = b.data as SpecAgentGraphNodeData
    // 最终模型：canonical Question 只有一个 immutable Answer identity，无 focus
    // 时直接展示该答案（不借 Active/first/last 冒充 —— 它就是同一个答案）。
    expect(data.primaryAnswer?.routeId).toBe(ACTIVE_ROUTE_ID)
    expect(data.answerPresentationMode).toBe('single-route')
    expect(data.routeStates.find((s) => s.routeId === ROUTE_B_ID)?.answer).toBeNull()
    expect(data.routeStates.find((s) => s.routeId === ACTIVE_ROUTE_ID)?.answer?.freeText).toBe('old route answer')
  })

  it('readingRouteId follows explicit focus and stays neutral for shared nodes otherwise', () => {
    const view = sharedView()
    const focused = projectGraph({
      view,
      activeNodeId: null,
      uiState: uiState({ focusRouteId: ROUTE_B_ID }),
      savedPositions: {},
    })
    const bFocused = focused.nodes.find((n) => n.id === 'b')!
    expect((bFocused.data as SpecAgentGraphNodeData).readingRouteId).toBe(ROUTE_B_ID)
    const unfocused = projectGraph({
      view,
      activeNodeId: null,
      uiState: uiState(),
      savedPositions: {},
    })
    const bActive = unfocused.nodes.find((n) => n.id === 'b')!
    expect((bActive.data as SpecAgentGraphNodeData).readingRouteId).toBeNull()
  })
})

describe('replacement edge visibility', () => {
  it('does not render a replacement edge even when both endpoints are visible', () => {
    // Active route D: a -> bprime（bprime 替代 b）；路线 A: a -> b。隐藏 A。
    const view = {
      projectId: PROJECT_ID,
      activeRouteId: ROUTE_D_ID,
      routes: [route(ACTIVE_ROUTE_ID, 'open', ['a', 'b']), route(ROUTE_D_ID, 'open', ['a', 'bprime'])],
      nodes: [node('a', null), node('b', 'a'), node('bprime', 'a', 'b')],
      answers: [],
      relations: [],
    }
    const hidden = projectGraph({
      view,
      activeNodeId: null,
      uiState: uiState({ routeDisplayStates: { [ACTIVE_ROUTE_ID]: 'hidden' } }),
      savedPositions: {},
    })
    const ids = hidden.nodes.map((n) => n.id)
    expect(ids).toContain('a')
    expect(ids).toContain('bprime')
    expect(ids).not.toContain('b')
    // source b 不可见 → no replacement edge.
    expect(hidden.edges.find((e) => e.id === 'replacement:b->bprime')).toBeUndefined()
    // 显示全部路线后仍保持 lineage-only topology。
    const all = projectGraph({ view, activeNodeId: null, uiState: uiState(), savedPositions: {} })
    expect(all.edges.find((e) => e.id === 'replacement:b->bprime')).toBeUndefined()
  })
})

describe('adaptive edge routing in the canonical projection', () => {
  // Projected positions use the stable 320px footprint and the new spacing.
  it('lineage edges render as adaptive curves with directed handles', () => {
    const result = project()
    const aToB = result.edges.find((e) => e.id === 'a->b')!
    expect(aToB.type).toBe('adaptive')
    expect(aToB.markerEnd).toBeDefined()
    // a center (160,110), b center (520,110): horizontal right.
    expect(aToB.sourceHandle).toBe('source-right')
    expect(aToB.targetHandle).toBe('target-left')
    // b->c uses the wider current-node center: b (520,110), c (930,110).
    const bToC = result.edges.find((e) => e.id === 'b->c')!
    expect(bToC.type).toBe('adaptive')
    expect(bToC.sourceHandle).toBe('source-right')
    expect(bToC.targetHandle).toBe('target-left')
  })

  it('replacement provenance does not create a dashed cross-canvas edge', () => {
    const result = project()
    const repl = result.edges.find((e) => e.id === 'replacement:b->bprime')!
    expect(repl).toBeUndefined()
    // supersedesNodeId is still not treated as parentNodeId.
    const bprime = result.nodes.find((n) => n.id === 'bprime')!
    expect((bprime.data as SpecAgentGraphNodeData).node.parentNodeId).toBe('a')
  })

  it('re-computes handles from saved positions, so a canonical refresh matches manual layout', () => {
    const saved: Record<string, GraphPosition> = {
      a: { x: 0, y: 0 },
      b: { x: 0, y: 300 },
      d: { x: 0, y: -300 },
    }
    const result = projectGraph({
      view: fixture(),
      activeNodeId: 'c',
      uiState: uiState(),
      savedPositions: saved,
    })
    // b placed directly below a -> vertical adaption on refresh.
    const aToB = result.edges.find((e) => e.id === 'a->b')!
    expect(aToB.sourceHandle).toBe('source-bottom')
    expect(aToB.targetHandle).toBe('target-top')
    // d dragged far above b -> top/bottom adaption.
    const bToD = result.edges.find((e) => e.id === 'b->d')!
    expect(bToD.sourceHandle).toBe('source-top')
    expect(bToD.targetHandle).toBe('target-bottom')
  })
})
