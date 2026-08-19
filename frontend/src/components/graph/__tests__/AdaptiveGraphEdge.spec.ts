import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { Position } from '@vue-flow/core'
import AdaptiveGraphEdge from '@/components/graph/AdaptiveGraphEdge.vue'
import type { SpecAgentGraphEdgeData } from '@/graph/graphProjection'

function mountEdge(data: SpecAgentGraphEdgeData) {
  return mount(AdaptiveGraphEdge, {
    props: {
      id: 'edge-1',
      source: 'a',
      target: 'b',
      sourceNode: {} as never,
      targetNode: {} as never,
      sourceX: 0,
      sourceY: 100,
      targetX: 320,
      targetY: 100,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      sourceHandleId: 'source-right',
      targetHandleId: 'target-left',
      markerStart: '',
      markerEnd: 'url(#arrow)',
      interactionWidth: 20,
      type: 'adaptive',
      data,
      events: {} as never,
    },
  })
}

describe('AdaptiveGraphEdge', () => {
  it('renders a restrained cubic curve and passes the marker through', () => {
    const wrapper = mountEdge({
      kind: 'lineage',
      routeIds: ['r1'],
      visibleRouteIds: ['r1'],
      visualWeight: 'focus',
    })
    const path = wrapper.find('[data-test="adaptive-graph-edge"]')
    expect(path.attributes('d')).toContain('C')
    expect(path.attributes('marker-end')).toBe('url(#arrow)')
    expect(path.classes()).toContain('graph-edge--focus')
  })

  it('keeps replacement warning and dashed presentation on the custom path', () => {
    const wrapper = mountEdge({
      kind: 'replacement',
      routeIds: ['r1'],
      visibleRouteIds: ['r1'],
      visualWeight: 'dimmed',
    })
    const path = wrapper.find('[data-test="adaptive-graph-edge"]')
    expect(path.classes()).toEqual(expect.arrayContaining([
      'graph-edge--replacement',
      'graph-edge--dimmed',
    ]))
    expect(path.attributes('style')).toContain('stroke-dasharray')
  })
})
