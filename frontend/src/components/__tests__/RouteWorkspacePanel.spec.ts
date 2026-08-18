import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RouteWorkspacePanel from '@/components/RouteWorkspacePanel.vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { makeRoute, makeRouteLineage } from '@/test/fixtures'

vi.mock('@/api/routes', () => ({
  activateRoute: vi.fn(),
  archiveRoute: vi.fn(),
  deleteRoute: vi.fn(),
  forkNode: vi.fn(),
  getRouteLineage: vi.fn(),
  regenerateNode: vi.fn(),
  restoreRoute: vi.fn(),
}))

import { getRouteLineage } from '@/api/routes'

function mountPanel(options: {
  routes: ReturnType<typeof makeRoute>[]
  selectedRouteId?: string | null
  selectedNodeId?: string | null
}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(RouteWorkspacePanel, {
    props: {
      projectTitle: 'Project alpha',
      routes: options.routes,
      selectedRouteId: options.selectedRouteId ?? null,
      selectedNodeId: options.selectedNodeId ?? null,
      commandPending: false,
      pendingRouteCommand: null,
    },
    global: { plugins: [pinia] },
  })
}

/** Creates a shared pinia + store so tests can seed state the panel reads. */
function createStoreWithRoutes(options: {
  routes: ReturnType<typeof makeRoute>[]
  selectedRouteId?: string | null
  selectedNodeId?: string | null
}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useWorkspaceStore()
  return {
    pinia,
    store,
    wrapper: mount(RouteWorkspacePanel, {
      props: {
        projectTitle: 'Project alpha',
        routes: options.routes,
        selectedRouteId: options.selectedRouteId ?? null,
        selectedNodeId: options.selectedNodeId ?? null,
        commandPending: false,
        pendingRouteCommand: null,
      },
      global: { plugins: [pinia] },
    }),
  }
}

describe('RouteWorkspacePanel', () => {
  it('renders project title, route labels, and lifecycle badges', () => {
    const wrapper = mountPanel({
      routes: [
        makeRoute({ id: 'r1', label: 'Initial route', lifecycleStatus: 'open' }),
        makeRoute({ id: 'r2', label: 'Old attempt', lifecycleStatus: 'superseded' }),
      ],
    })
    expect(wrapper.text()).toContain('Project alpha')
    expect(wrapper.text()).toContain('Initial route')
    expect(wrapper.text()).toContain('Old attempt')
    expect(wrapper.text()).toContain('Open')
    expect(wrapper.text()).toContain('Superseded')
  })

  it('keeps lifecycle badge and active indicator separate', () => {
    const openNotActive = makeRoute({ id: 'r1', lifecycleStatus: 'open', isActive: false })
    const openActive = makeRoute({ id: 'r2', lifecycleStatus: 'open', isActive: true })
    const wrapper = mountPanel({ routes: [openNotActive, openActive] })

    expect(wrapper.findAll('[data-test="active-route"]')).toHaveLength(1)
    // The active route still shows an Open lifecycle badge, not an "active"
    // lifecycle value.
    expect(wrapper.findAll('[data-test="lifecycle-badge"]').map((b) => b.text())).toEqual([
      'Open',
      'Open',
    ])
  })

  it('hides archived and deleted routes under a collapsed section', async () => {
    const wrapper = mountPanel({
      routes: [
        makeRoute({ id: 'r1', label: 'Main route', lifecycleStatus: 'open' }),
        makeRoute({ id: 'r2', label: 'Archived route', lifecycleStatus: 'archived' }),
        makeRoute({ id: 'r3', label: 'Deleted route', lifecycleStatus: 'deleted' }),
      ],
    })
    expect(wrapper.text()).toContain('Archived / Deleted (2)')
    // Deleted/archived routes remain present and recoverable, not removed.
    await wrapper.find('details').trigger('toggle')
    expect(wrapper.text()).toContain('Archived route')
    expect(wrapper.text()).toContain('Deleted route')
  })

  it('emits route selection when a route is clicked', async () => {
    const wrapper = mountPanel({ routes: [makeRoute({ id: 'r1', label: 'Main' })] })
    await wrapper.find('[data-test="select-route"]').trigger('click')
    expect(wrapper.emitted('selectRoute')).toEqual([['r1']])
  })

  it('loads the selected route lineage lazily from the backend store action', async () => {
    const getRouteLineageMock = vi.mocked(getRouteLineage)
    getRouteLineageMock.mockResolvedValue(makeRouteLineage({ routeId: 'r1' }))
    const { store, wrapper } = createStoreWithRoutes({
      routes: [makeRoute({ id: 'r1', label: 'Main', tipNodeId: 'lnode-2' })],
      selectedRouteId: 'r1',
    })
    store.projectId = 'project-1'
    store.selectedRouteId = 'r1'
    await flushPromises()

    expect(getRouteLineageMock).toHaveBeenCalledWith('project-1', 'r1')
    expect(wrapper.find('[data-test="route-lineage"]').exists()).toBe(true)
    expect(store.routeLineages['r1'].nodes).toHaveLength(2)
  })

  it('renders lineage nodes from the store and emits node selection', async () => {
    const { store, wrapper } = createStoreWithRoutes({
      routes: [makeRoute({ id: 'r1', label: 'Main', tipNodeId: 'lnode-2' })],
      selectedRouteId: 'r1',
    })
    store.routeLineages = { r1: makeRouteLineage({ routeId: 'r1' }) }
    await flushPromises()
    expect(wrapper.findAll('[data-test="lineage-node"]')).toHaveLength(2)

    await wrapper.findAll('[data-test="lineage-node"]')[1].trigger('click')
    expect(wrapper.emitted('selectNode')).toEqual([['lnode-2']])
  })

  it('shows lifecycle-appropriate actions per route', () => {
    const wrapper = mountPanel({
      routes: [
        makeRoute({ id: 'r-open', lifecycleStatus: 'open', isActive: false }),
        makeRoute({ id: 'r-super', lifecycleStatus: 'superseded' }),
        makeRoute({ id: 'r-archived', lifecycleStatus: 'archived' }),
        makeRoute({ id: 'r-deleted', lifecycleStatus: 'deleted' }),
      ],
    })
    const cards = wrapper.findAll('[data-test="route-card"]')
    expect(cards[0].find('[data-test="activate-route"]').exists()).toBe(true)
    expect(cards[0].find('[data-test="restore-route"]').exists()).toBe(false)
    expect(cards[1].find('[data-test="restore-route"]').exists()).toBe(true)
    // Archive is hidden for archived/deleted; delete hidden for deleted.
    expect(cards[2].find('[data-test="archive-route"]').exists()).toBe(false)
    expect(cards[3].find('[data-test="delete-route"]').exists()).toBe(false)
  })

  it('emits activate/restore/archive/delete route commands', async () => {
    const wrapper = mountPanel({
      routes: [
        makeRoute({ id: 'r-open', lifecycleStatus: 'open', isActive: false }),
        makeRoute({ id: 'r-deleted', lifecycleStatus: 'deleted' }),
      ],
    })
    await wrapper.find('[data-test="activate-route"]').trigger('click')
    expect(wrapper.emitted('activate')).toEqual([['r-open']])

    await wrapper.find('[data-test="restore-route"]').trigger('click')
    expect(wrapper.emitted('restore')).toEqual([['r-deleted']])

    const wrapper2 = mountPanel({
      routes: [makeRoute({ id: 'r1', lifecycleStatus: 'open', isActive: false })],
    })
    await wrapper2.find('[data-test="archive-route"]').trigger('click')
    expect(wrapper2.emitted('archive')).toEqual([['r1']])
    await wrapper2.find('[data-test="delete-route"]').trigger('click')
    expect(wrapper2.emitted('delete')).toEqual([['r1']])
  })
})
