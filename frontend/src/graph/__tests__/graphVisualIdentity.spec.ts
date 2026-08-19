import { describe, expect, it } from 'vitest'
import type { GraphWorkspaceView } from '@/api/types'
import {
  buildVisualInstances,
  visualNodeKeyFor,
} from '@/graph/graphVisualIdentity'
import { getVisualLineageEdgeMembership } from '@/graph/graphProjection'

const node = (id: string, parentNodeId: string | null) => ({
  id,
  projectId: 'p1',
  parentNodeId,
  supersedesNodeId: null,
  question: id,
  purpose: null,
  options: [],
  allowFreeAnswer: true,
  createdAt: '2026-08-19T00:00:00Z',
})

const route = (
  id: string,
  lineageNodeIds: string[],
  branchType?: 'fork' | 'reanswer' | 'regenerate',
  sourceRouteId?: string,
  branchAtNodeId?: string,
) => ({
  id,
  label: id,
  lifecycleStatus: 'open' as const,
  isActive: id === 'original',
  rootNodeId: lineageNodeIds[0] ?? null,
  tipNodeId: lineageNodeIds.at(-1) ?? null,
  createdFromNodeId: null,
  supersedesRouteId: null,
  replacementOfNodeId: null,
  branchType,
  sourceRouteId,
  branchAtNodeId,
  lineageNodeIds,
})

function view(overrides: Partial<GraphWorkspaceView> = {}): GraphWorkspaceView {
  return {
    projectId: 'p1',
    activeRouteId: 'original',
    routes: [
      route('original', ['q1', 'q2', 'q3']),
      route('fork', ['q1', 'q2'], 'fork', 'original', 'q2'),
    ],
    nodes: [node('q1', null), node('q2', 'q1'), node('q3', 'q2')],
    answers: [],
    ...overrides,
  }
}

describe('visual graph identity', () => {
  it('keeps Fork branch point shared and diverges at the first new child', () => {
    const graph = view()
    expect(visualNodeKeyFor(graph, 'original', 'q1')).toBe('q1')
    expect(visualNodeKeyFor(graph, 'fork', 'q1')).toBe('q1')
    expect(visualNodeKeyFor(graph, 'original', 'q2')).toBe('q2')
    expect(visualNodeKeyFor(graph, 'fork', 'q2')).toBe('q2')

    const forkWithChild = view({
      routes: [
        route('original', ['q1', 'q2', 'q3']),
        route('fork', ['q1', 'q2', 'q3b'], 'fork', 'original', 'q2'),
      ],
      nodes: [...view().nodes, node('q3b', 'q2')],
    })
    expect(visualNodeKeyFor(forkWithChild, 'fork', 'q3b')).toBe('route:fork:q3b')
  })

  it('qualifies the Re-answer visual instance immediately at the target', () => {
    const graph = view({
      activeRouteId: 'reanswer',
      routes: [
        route('original', ['q1', 'q2', 'q3']),
        route('reanswer', ['q1', 'q2'], 'reanswer', 'original', 'q2'),
      ],
    })
    expect(visualNodeKeyFor(graph, 'reanswer', 'q1')).toBe('q1')
    expect(visualNodeKeyFor(graph, 'reanswer', 'q2')).toBe('route:reanswer:q2')
    expect(buildVisualInstances(graph).filter((instance) => instance.canonicalNodeId === 'q2')).toHaveLength(2)
  })

  it('chains a Fork from Re-answer without merging into the original target', () => {
    const graph = view({
      routes: [
        route('original', ['q1', 'q2']),
        route('reanswer', ['q1', 'q2'], 'reanswer', 'original', 'q2'),
        route('fork-from-reanswer', ['q1', 'q2'], 'fork', 'reanswer', 'q2'),
      ],
      activeRouteId: 'fork-from-reanswer',
    })
    expect(visualNodeKeyFor(graph, 'fork-from-reanswer', 'q2')).toBe('route:reanswer:q2')
    expect(visualNodeKeyFor(graph, 'fork-from-reanswer', 'q2'))
      .not.toBe(visualNodeKeyFor(graph, 'original', 'q2'))
  })

  it('deduplicates shared visual endpoints and preserves route memberships', () => {
    const graph = view({
      routes: [
        route('original', ['q1', 'q2', 'q3']),
        route('fork-a', ['q1', 'q2', 'q3a'], 'fork', 'original', 'q2'),
        route('fork-b', ['q1', 'q2', 'q3b'], 'fork', 'original', 'q2'),
      ],
      nodes: [...view().nodes, node('q3a', 'q2'), node('q3b', 'q2')],
    })
    const edges = getVisualLineageEdgeMembership(graph)
    expect(edges.get('q1->q2')?.routeIds).toEqual(['original', 'fork-a', 'fork-b'])
    expect(edges.get('q2->route:fork-a:q3a')?.routeIds).toEqual(['fork-a'])
    expect(edges.get('q2->route:fork-b:q3b')?.routeIds).toEqual(['fork-b'])
  })
})
