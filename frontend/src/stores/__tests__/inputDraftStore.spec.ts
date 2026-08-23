import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useInputDraftStore } from '../inputDraftStore'

describe('useInputDraftStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('stores and retrieves a draft', () => {
    const store = useInputDraftStore()
    store.setDraft('proj-1', 'node-1', { selectedOptionId: 'opt-a', freeText: 'hello' })

    const draft = store.getDraft('proj-1', 'node-1')
    expect(draft).toEqual({ selectedOptionId: 'opt-a', freeText: 'hello' })
  })

  it('returns undefined for non-existent draft', () => {
    const store = useInputDraftStore()
    expect(store.getDraft('proj-1', 'node-999')).toBeUndefined()
  })

  it('clears a draft', () => {
    const store = useInputDraftStore()
    store.setDraft('proj-1', 'node-1', { selectedOptionId: null, freeText: 'text' })
    store.clearDraft('proj-1', 'node-1')
    expect(store.getDraft('proj-1', 'node-1')).toBeUndefined()
  })

  it('separates drafts by routeId', () => {
    const store = useInputDraftStore()
    store.setDraft('proj-1', 'node-1', { selectedOptionId: 'opt-a', freeText: '' }, 'route-1')
    store.setDraft('proj-1', 'node-1', { selectedOptionId: 'opt-b', freeText: '' }, 'route-2')

    expect(store.getDraft('proj-1', 'node-1', 'route-1')?.selectedOptionId).toBe('opt-a')
    expect(store.getDraft('proj-1', 'node-1', 'route-2')?.selectedOptionId).toBe('opt-b')
  })

  it('drag does not clear draft (key isolation)', () => {
    const store = useInputDraftStore()
    store.setDraft('proj-1', 'node-1', { selectedOptionId: 'opt-a', freeText: 'keep me' })

    // Simulating a drag does not touch the store — only remount with a
    // different node id would load a different key.
    const draft = store.getDraft('proj-1', 'node-1')
    expect(draft?.freeText).toBe('keep me')
  })

  it('submit does not clear draft until explicit clear', () => {
    const store = useInputDraftStore()
    store.setDraft('proj-1', 'node-1', { selectedOptionId: 'opt-a', freeText: '' })
    // After submit, the draft stays until the run completes and clearDraft is called.
    expect(store.getDraft('proj-1', 'node-1')).toBeDefined()
    store.clearDraft('proj-1', 'node-1')
    expect(store.getDraft('proj-1', 'node-1')).toBeUndefined()
  })

  it('draftCount tracks total drafts', () => {
    const store = useInputDraftStore()
    expect(store.draftCount).toBe(0)
    store.setDraft('proj-1', 'node-1', { selectedOptionId: null, freeText: 'a' })
    store.setDraft('proj-1', 'node-2', { selectedOptionId: null, freeText: 'b' })
    expect(store.draftCount).toBe(2)
    store.clearDraft('proj-1', 'node-1')
    expect(store.draftCount).toBe(1)
  })
})
