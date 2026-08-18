import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RouteListPanel from '@/components/RouteListPanel.vue'
import { makeRoute } from '@/test/fixtures'

function mountPanel(routes: ReturnType<typeof makeRoute>[], projectTitle = 'Project alpha') {
  return mount(RouteListPanel, { props: { projectTitle, routes } })
}

describe('RouteListPanel', () => {
  it('renders the project title and route labels', () => {
    const wrapper = mountPanel([
      makeRoute({ id: 'r1', label: 'Initial route' }),
      makeRoute({ id: 'r2', label: 'Alternative route' }),
    ])
    expect(wrapper.text()).toContain('Project alpha')
    expect(wrapper.text()).toContain('Initial route')
    expect(wrapper.text()).toContain('Alternative route')
  })

  it('renders the lifecycle badge from backend lifecycle only', () => {
    const wrapper = mountPanel([
      makeRoute({ id: 'r1', lifecycleStatus: 'open' }),
      makeRoute({ id: 'r2', lifecycleStatus: 'superseded' }),
      makeRoute({ id: 'r3', lifecycleStatus: 'deleted' }),
    ])
    expect(wrapper.text()).toContain('Open')
    expect(wrapper.text()).toContain('Superseded')
    expect(wrapper.text()).toContain('Deleted')
  })

  it('does not imply OPEN equals ACTIVE', () => {
    // An open but non-active route gets no Active indicator.
    const openNotActive = makeRoute({ id: 'r1', lifecycleStatus: 'open', isActive: false })
    const openActive = makeRoute({ id: 'r2', lifecycleStatus: 'open', isActive: true })
    const wrapper = mountPanel([openNotActive, openActive])

    expect(wrapper.findAll('[data-test="active-route"]')).toHaveLength(1)
    const activeRoute = wrapper.findAll('[data-test="active-route"]')
    expect(activeRoute[0].text()).toBe('Active')
  })

  it('shows the tip node id for diagnostics', () => {
    const wrapper = mountPanel([makeRoute({ id: 'r1', tipNodeId: 'node-77' })])
    expect(wrapper.text()).toContain('node-77')
  })

  it('shows an empty state when there are no routes', () => {
    const wrapper = mountPanel([])
    expect(wrapper.text()).toContain('No routes yet.')
  })
})