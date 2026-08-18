import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SpecSnapshotPanel from '@/components/SpecSnapshotPanel.vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  makeActiveState,
  makeRoute,
  makeSourceReference,
  makeSpecGeneration,
  makeSpecSnapshot,
} from '@/test/fixtures'

vi.mock('@/api/projects', () => ({
  getProject: vi.fn(),
}))

vi.mock('@/api/workspace', () => ({
  draftNextQuestion: vi.fn(),
  getActiveState: vi.fn(),
  listRoutes: vi.fn(),
  submitAnswer: vi.fn(),
}))

vi.mock('@/api/requirementState', () => ({
  getRequirementState: vi.fn(),
}))

vi.mock('@/api/routes', () => ({
  activateRoute: vi.fn(),
  archiveRoute: vi.fn(),
  deleteRoute: vi.fn(),
  forkNode: vi.fn(),
  getRouteLineage: vi.fn(),
  regenerateNode: vi.fn(),
  restoreRoute: vi.fn(),
}))

vi.mock('@/api/spec', () => ({
  generateSpec: vi.fn(),
  listRouteSpecs: vi.fn(),
}))

import { generateSpec as apiGenerateSpec, listRouteSpecs } from '@/api/spec'
import type { Pinia } from 'pinia'

function mountPanel(pinia: Pinia) {
  return mount(SpecSnapshotPanel, {
    global: { plugins: [pinia] },
  })
}

describe('SpecSnapshotPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function prepareStore(overrides: {
    activeRoute?: ReturnType<typeof makeRoute> | null
    selectedRouteId?: string | null
  } = {}): { pinia: Pinia; store: ReturnType<typeof useWorkspaceStore> } {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.activeState = makeActiveState({
      activeRoute: overrides.activeRoute === undefined
        ? makeRoute({ id: 'r1', isActive: true, tipNodeId: 'lnode-2' })
        : overrides.activeRoute,
    })
    store.routes = [makeRoute({ id: 'r1', isActive: true, tipNodeId: 'lnode-2' })]
    store.selectedRouteId = overrides.selectedRouteId ?? 'r1'
    return { pinia, store }
  }

  it('loads the snapshot list for the selected route', async () => {
    const { pinia } = prepareStore()
    const snapshot = makeSpecSnapshot()
    vi.mocked(listRouteSpecs).mockResolvedValue([snapshot])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    expect(listRouteSpecs).toHaveBeenCalledWith('p1', 'r1')
    expect(wrapper.find('[data-test="spec-snapshot-item"]').exists()).toBe(true)
  })

  it('generates for the ACTIVE route only and is disabled without an active tip', async () => {
    const { pinia } = prepareStore({
      activeRoute: makeRoute({ id: 'r1', isActive: true, tipNodeId: null }),
    })
    vi.mocked(listRouteSpecs).mockResolvedValue([])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    const button = wrapper.find('[data-test="generate-spec"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="generate-spec-hint"]').exists()).toBe(true)
  })

  it('prevents duplicate generation and shows pending state while running', async () => {
    const { pinia } = prepareStore()
    let resolveGenerate: (v: ReturnType<typeof makeSpecGeneration>) => void = () => undefined
    vi.mocked(apiGenerateSpec).mockReturnValue(
      new Promise((resolve) => {
        resolveGenerate = resolve
      }),
    )
    vi.mocked(listRouteSpecs).mockResolvedValue([])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    await wrapper.find('[data-test="generate-spec"]').trigger('click')

    expect(apiGenerateSpec).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="generate-spec"]').text()).toContain('Generating spec…')
    expect(wrapper.find('[data-test="generate-spec"]').attributes('disabled')).toBeDefined()

    resolveGenerate(makeSpecGeneration())
    await flushPromises()
    expect(vi.mocked(apiGenerateSpec)).toHaveBeenCalledTimes(1)
  })

  it('selects the newly generated snapshot and renders its derived content', async () => {
    const { pinia, store } = prepareStore()
    const generation = makeSpecGeneration({
      specSnapshot: makeSpecSnapshot({
        id: 'spec-new',
        routeId: 'r1',
        sections: [
          { id: 's1', title: 'Overview', content: 'Generated overview content.' },
          { id: 's2', title: 'Scope', content: 'Generated scope content.' },
        ],
        unresolvedItems: [{ text: 'A generated unresolved aspect.', category: 'unresolved' }],
        sourceRefs: [makeSourceReference({ kind: 'node', refId: 'lnode-1' })],
      }),
    })
    vi.mocked(apiGenerateSpec).mockResolvedValue(generation)
    vi.mocked(listRouteSpecs).mockResolvedValue([generation.specSnapshot])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    await flushPromises()

    expect(store.selectedSpecId).toBe('spec-new')
    expect(wrapper.find('[data-test="spec-snapshot-detail"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Generated overview content.')
    expect(wrapper.text()).toContain('Generated scope content.')
    expect(wrapper.find('[data-test="unresolved-item"]').text()).toContain('A generated unresolved aspect.')
  })

  it('displays every source reference without inventing descriptions', async () => {
    const { pinia } = prepareStore()
    const snapshot = makeSpecSnapshot({
      id: 'spec-1',
      sourceRefs: [
        makeSourceReference({ kind: 'node', refId: 'lnode-1' }),
        makeSourceReference({ kind: 'answer', refId: 'answer-9' }),
      ],
    })
    vi.mocked(listRouteSpecs).mockResolvedValue([snapshot])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    await wrapper.find('[data-test="spec-snapshot-item"]').trigger('click')
    const refs = wrapper.findAll('[data-test="source-reference"]')
    expect(refs.map((r) => r.text())).toEqual(['node:lnode-1', 'answer:answer-9'])
  })

  it('clearly labels the snapshot as derived, never source of truth', async () => {
    const { pinia } = prepareStore()
    vi.mocked(listRouteSpecs).mockResolvedValue([makeSpecSnapshot({ id: 'spec-1' })])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    await wrapper.find('[data-test="spec-snapshot-item"]').trigger('click')
    expect(wrapper.find('[data-test="derived-label"]').text()).toContain('derived')
    expect(wrapper.text()).not.toContain('Source of Truth')
  })

  it('shows subdued provenance metadata for the snapshot', async () => {
    const { pinia } = prepareStore()
    const snapshot = makeSpecSnapshot({
      id: 'spec-1',
      createdByRunId: 'run-77',
      contextSnapshotId: 'context-88',
    })
    vi.mocked(listRouteSpecs).mockResolvedValue([snapshot])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    await wrapper.find('[data-test="spec-snapshot-item"]').trigger('click')
    const provenance = wrapper.find('[data-test="spec-provenance"]').text()
    expect(provenance).toContain('run-77')
    expect(provenance).toContain('context-88')
  })

  it('does not clear old snapshots when a new one is generated', async () => {
    const { pinia, store } = prepareStore()
    const oldSnapshot = makeSpecSnapshot({ id: 'spec-old', routeId: 'r1', createdAt: '2026-01-02T00:00:00Z' })
    const newSnapshot = makeSpecSnapshot({ id: 'spec-new', routeId: 'r1', createdAt: '2026-01-03T00:00:00Z' })
    vi.mocked(listRouteSpecs).mockResolvedValue([oldSnapshot])
    const wrapper = mountPanel(pinia)
    await flushPromises()

    expect(wrapper.findAll('[data-test="spec-snapshot-item"]')).toHaveLength(1)

    vi.mocked(apiGenerateSpec).mockResolvedValue(makeSpecGeneration({ specSnapshot: newSnapshot }))
    vi.mocked(listRouteSpecs).mockResolvedValue([oldSnapshot, newSnapshot])
    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    await flushPromises()

    expect(store.selectedSpecs.map((s) => s.id)).toEqual(['spec-old', 'spec-new'])
    expect(wrapper.findAll('[data-test="spec-snapshot-item"]')).toHaveLength(2)
  })

  it('cross-route generation displays the active route new snapshot, not the stale one', async () => {
    // active route = route-B, selected route = route-A (browsing old history).
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    const routeA = makeRoute({ id: 'route-A', isActive: false })
    const routeB = { ...makeRoute({ id: 'route-B', isActive: true }), tipNodeId: 'lnode-2' }
    store.activeState = makeActiveState({ activeRoute: routeB })
    store.routes = [routeA, routeB]
    store.selectedRouteId = 'route-A'

    const staleSnapshotA = makeSpecSnapshot({
      id: 'spec-old-A',
      routeId: 'route-A',
      sections: [{ id: 'sa1', title: 'Overview', content: 'Stale route-A overview content.' }],
    })
    const newSnapshotB = makeSpecSnapshot({
      id: 'spec-new-B',
      routeId: 'route-B',
      sections: [{ id: 'sb1', title: 'Overview', content: 'Fresh route-B overview content.' }],
    })
    vi.mocked(listRouteSpecs).mockImplementation((_projectId, routeId) =>
      Promise.resolve(routeId === 'route-A' ? [staleSnapshotA] : [newSnapshotB]),
    )
    const wrapper = mountPanel(pinia)
    await flushPromises()

    // The selected route A renders its old snapshot.
    expect(wrapper.text()).toContain('Stale route-A overview content.')
    expect(store.selectedRouteId).toBe('route-A')

    vi.mocked(apiGenerateSpec).mockResolvedValue(makeSpecGeneration({ specSnapshot: newSnapshotB }))

    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    await flushPromises()

    // Selection follows the backend-owned snapshot on the active route.
    expect(store.selectedRouteId).toBe('route-B')
    expect(store.selectedSpecId).toBe('spec-new-B')
    expect(store.selectedSpec?.routeId).toBe('route-B')

    // The panel now shows the B snapshot with B provenance, never A's stale one.
    expect(wrapper.text()).toContain('Fresh route-B overview content.')
    expect(wrapper.text()).not.toContain('Stale route-A overview content.')
    const provenance = wrapper.find('[data-test="spec-provenance"]').text()
    expect(provenance).toContain('spec-new-B')
    expect(provenance).toContain('route-B')
  })
})
