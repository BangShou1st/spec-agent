import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import HistoricalNodePanel from '@/components/HistoricalNodePanel.vue'
import { makeRoute, makeRouteLineageNode } from '@/test/fixtures'

function mountPanel(options: {
  node: ReturnType<typeof makeRouteLineageNode> | null
  route: ReturnType<typeof makeRoute> | null
  isTip?: boolean
  isActiveRoute?: boolean
  commandPending?: boolean
}) {
  return mount(HistoricalNodePanel, {
    props: {
      node: options.node,
      route: options.route,
      isTip: options.isTip ?? false,
      commandPending: options.commandPending ?? false,
      pendingRouteCommand: null,
      isActiveRoute: options.isActiveRoute ?? false,
    },
  })
}

const childNode = (overrides: Partial<ReturnType<typeof makeRouteLineageNode>> = {}) =>
  makeRouteLineageNode({
    id: 'lnode-2',
    parentNodeId: 'lnode-1',
    question: 'What scope is required?',
    purpose: 'Clarifies scope.',
    options: [
      { id: 'opt-a', label: 'Small scope', impact: 'Reduces scope' },
      { id: 'opt-b', label: 'Large scope', impact: null },
    ],
    ...overrides,
  })

describe('HistoricalNodePanel', () => {
  it('shows question, purpose, options with impacts, and provenance', () => {
    const wrapper = mountPanel({ node: childNode(), route: makeRoute(), isTip: true, isActiveRoute: true })

    expect(wrapper.find('[data-test="historical-question"]').text()).toBe('What scope is required?')
    expect(wrapper.find('[data-test="historical-purpose"]').text()).toBe('Clarifies scope.')
    const options = wrapper.findAll('[data-test="historical-option"]')
    expect(options[0].text()).toContain('Small scope')
    expect(options[0].text()).toContain('Reduces scope')
    expect(wrapper.find('[data-test="historical-provenance"]').text()).toContain('lnode-2')
    expect(wrapper.find('[data-test="historical-tip"]').exists()).toBe(true)
  })

  it('marks a node that supersedes another node', () => {
    const wrapper = mountPanel({
      node: childNode({ supersedesNodeId: 'lnode-1' }),
      route: makeRoute(),
      isActiveRoute: true,
    })
    expect(wrapper.find('[data-test="historical-supersedes"]').exists()).toBe(true)
  })

  it('emits back-to-active to return to the current clarification workflow', async () => {
    const wrapper = mountPanel({ node: childNode(), route: makeRoute(), isActiveRoute: true })
    await wrapper.find('[data-test="back-to-active"]').trigger('click')
    expect(wrapper.emitted('backToActive')).toHaveLength(1)
  })

  it('emits fork from here on an OPEN route node', async () => {
    const wrapper = mountPanel({
      node: childNode(),
      route: makeRoute({ lifecycleStatus: 'open', isActive: false }),
    })
    expect(wrapper.find('[data-test="fork-from-here"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="fork-from-here"]').trigger('click')
    expect(wrapper.emitted('fork')).toHaveLength(1)
  })

  it('disables fork and regenerate on non-OPEN routes with a restore hint', () => {
    const wrapper = mountPanel({
      node: childNode(),
      route: makeRoute({ lifecycleStatus: 'archived', isActive: false }),
    })
    expect(wrapper.find('[data-test="fork-from-here"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="regenerate-this-question"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="fork-disabled-reason"]').text()).toContain('Restore this route first')
    expect(wrapper.find('[data-test="regenerate-disabled-reason"]').text()).toContain('Restore this route first')
  })

  it('regenerate is disabled for root nodes with an explanation', () => {
    const root = makeRouteLineageNode({ id: 'lnode-1', parentNodeId: null, question: 'Root question' })
    const wrapper = mountPanel({
      node: root,
      route: makeRoute({ lifecycleStatus: 'open', isActive: true }),
      isActiveRoute: true,
    })
    expect(wrapper.find('[data-test="regenerate-this-question"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="regenerate-disabled-reason"]').text()).toContain(
      'Root question regeneration is not supported.',
    )
  })

  it('regenerate requires the active OPEN route and never auto-activates', () => {
    const openNotActive = mountPanel({
      node: childNode(),
      route: makeRoute({ lifecycleStatus: 'open', isActive: false }),
      isActiveRoute: false,
    })
    expect(openNotActive.find('[data-test="regenerate-this-question"]').attributes('disabled')).toBeDefined()
    expect(openNotActive.find('[data-test="regenerate-disabled-reason"]').text()).toContain(
      'Activate this route first to regenerate.',
    )
  })

  it('regenerate is enabled on the active OPEN route for a non-root node', async () => {
    const wrapper = mountPanel({
      node: childNode(),
      route: makeRoute({ lifecycleStatus: 'open', isActive: true }),
      isActiveRoute: true,
    })
    expect(wrapper.find('[data-test="regenerate-this-question"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="regenerate-this-question"]').trigger('click')
    expect(wrapper.emitted('regenerate')).toHaveLength(1)
  })

  it('disables all actions while a route command is pending', () => {
    const wrapper = mountPanel({
      node: childNode(),
      route: makeRoute({ lifecycleStatus: 'open', isActive: true }),
      isActiveRoute: true,
      commandPending: true,
    })
    expect(wrapper.find('[data-test="fork-from-here"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="regenerate-this-question"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="back-to-active"]').attributes('disabled')).toBeDefined()
  })
})
