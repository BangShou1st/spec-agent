import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RouteLineage from '@/components/RouteLineage.vue'
import { makeRouteLineageNode } from '@/test/fixtures'

function mountLineage(nodes: ReturnType<typeof makeRouteLineageNode>[], selected: string | null = null) {
  return mount(RouteLineage, { props: { nodes, selectedNodeId: selected } })
}

describe('RouteLineage', () => {
  it('renders nodes in root→tip order with indentation', () => {
    const root = makeRouteLineageNode({ id: 'n1', question: 'Root question' })
    const child = makeRouteLineageNode({ id: 'n2', parentNodeId: 'n1', question: 'Child question' })
    const wrapper = mountLineage([root, child])

    const questions = wrapper
      .findAll('[data-test="lineage-node"]')
      .map((n) => n.find('.lineage-node-question').text())
    expect(questions).toEqual(['Root question', 'Child question'])
  })

  it('marks the last node as the tip and the first as root', () => {
    const root = makeRouteLineageNode({ id: 'n1', question: 'Root question' })
    const tip = makeRouteLineageNode({ id: 'n2', parentNodeId: 'n1', question: 'Tip question' })
    const wrapper = mountLineage([root, tip])

    const nodes = wrapper.findAll('[data-test="lineage-node"]')
    expect(nodes[0].find('[data-test="root-node"]').exists()).toBe(true)
    expect(nodes[0].find('[data-test="tip-node"]').exists()).toBe(false)
    expect(nodes[1].find('[data-test="tip-node"]').exists()).toBe(true)
  })

  it('marks a node that supersedes another node', () => {
    const node = makeRouteLineageNode({ id: 'n2', parentNodeId: 'n1', supersedesNodeId: 'n1' })
    const wrapper = mountLineage([node])
    expect(wrapper.find('[data-test="supersedes-node"]').exists()).toBe(true)
  })

  it('emits node selection on click and highlights the selected node', async () => {
    const n1 = makeRouteLineageNode({ id: 'n1', question: 'Root' })
    const n2 = makeRouteLineageNode({ id: 'n2', parentNodeId: 'n1', question: 'Child' })
    const wrapper = mountLineage([n1, n2], 'n2')

    expect(wrapper.findAll('[data-test="lineage-node"]')[1].classes()).toContain('selected')

    await wrapper.findAll('[data-test="lineage-node"]')[0].trigger('click')
    expect(wrapper.emitted('selectNode')).toEqual([['n1']])
  })

  it('shows an honest empty state for a route without nodes', () => {
    const wrapper = mountLineage([])
    expect(wrapper.find('[data-test="lineage-empty"]').exists()).toBe(true)
  })
})
