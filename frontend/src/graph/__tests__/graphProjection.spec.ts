import { describe, expect, it } from 'vitest'
import { projectGraph, getNodeRouteMembership, getLineageEdgeMembership, selectPrimaryAnswer, getVisibleRouteIds } from '@/graph/graphProjection'
import type { RouteLifecycleStatus } from '@/api/types'
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
function fixture() {
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
}> = {}) {
  return {
    focusRouteId: null,
    lifecycleFilters: { ...DEFAULT_FILTERS },
    routeDisplayStates: {},
    expandedNodeIds: [],
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

  it('renders the a->b lineage edge once with route memberships A/B/C', () => {
    const result = project()
    const edge = result.edges.find((e) => e.id === 'a->b')
    expect(edge).toBeDefined()
    expect(edge?.data?.kind).toBe('lineage')
    expect(edge?.data?.routeIds).toEqual([ACTIVE_ROUTE_ID, ROUTE_B_ID, ROUTE_C_ID])
  })

  it('focus route answer outranks the active route answer', () => {
    const result = project({ uiState: uiState({ focusRouteId: ROUTE_B_ID }) })
    const bNode = result.nodes.find((n) => n.id === 'b')!
    const data = bNode.data as SpecAgentGraphNodeData
    expect(data.primaryAnswer?.routeId).toBe(ROUTE_B_ID)
    expect(data.primaryAnswer?.freeText).toBe('route b answer')
    expect(data.answers.find((a) => a.routeId === ROUTE_B_ID)?.isPrimary).toBe(true)
  })

  it('without focus the active route answer is primary', () => {
    const result = project()
    const bNode = result.nodes.find((n) => n.id === 'b')!
    const data = bNode.data as SpecAgentGraphNodeData
    expect(data.primaryAnswer?.routeId).toBe(ACTIVE_ROUTE_ID)
    expect(data.answers.find((a) => a.routeId === ACTIVE_ROUTE_ID)?.isPrimary).toBe(true)
  })

  it('hiding route B removes only its exclusive node d', () => {
    const result = project({
      uiState: uiState({ routeDisplayStates: { [ROUTE_B_ID]: 'hidden' } }),
    })
    const ids = result.nodes.map((n) => n.id)
    expect(ids).not.toContain('d')
    expect(ids).toContain('a')
    expect(ids).toContain('b')
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

  it('replacement edge is separate from lineage and never becomes parent', () => {
    const result = project()
    const repl = result.edges.find((e) => e.id === 'replacement:b->bprime')
    expect(repl).toBeDefined()
    expect(repl?.source).toBe('b')
    expect(repl?.target).toBe('bprime')
    expect(repl?.data?.kind).toBe('replacement')
    expect(repl?.data?.routeIds).toEqual([ROUTE_D_ID])
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

  it('selectPrimaryAnswer prefers focus over active and returns null when absent', () => {
    const answers = [
      { nodeId: 'x', routeId: ACTIVE_ROUTE_ID, selectedOptionId: null, selectedOptionLabel: null, freeText: 'a', isPrimary: false },
      { nodeId: 'x', routeId: ROUTE_B_ID, selectedOptionId: null, selectedOptionLabel: null, freeText: 'b', isPrimary: false },
    ]
    const focused = selectPrimaryAnswer('x', answers, ROUTE_B_ID, ACTIVE_ROUTE_ID, [])
    expect(focused?.freeText).toBe('b')
    const active = selectPrimaryAnswer('x', answers, null, ACTIVE_ROUTE_ID, [])
    expect(active?.freeText).toBe('a')
    const none = selectPrimaryAnswer('y', answers, null, ACTIVE_ROUTE_ID, [])
    expect(none).toBeNull()
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
