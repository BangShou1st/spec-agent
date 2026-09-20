import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { INPUT_DRAFT_STORAGE_KEY, useInputDraftStore } from '../inputDraftStore'

describe('useInputDraftStore', () => {
  afterEach(() => vi.restoreAllMocks())
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

  it('restores text and multi-select drafts across reload and persists scoped cleanup', () => {
    const store = useInputDraftStore()
    const first = { selectedOptionId: 'a', selectedOptionIds: ['a', 'b'], freeText: 'one' }
    store.setDraft('p', 'n', first, 'r1')
    store.setDraft('p', 'n', { selectedOptionId: null, freeText: 'two' }, 'r2')
    store.setDraft('other-project', 'n', { selectedOptionId: null, freeText: 'other' }, 'r1')
    setActivePinia(createPinia())
    const restored = useInputDraftStore()
    expect(restored.getDraft('p', 'n', 'r1')).toEqual(first)
    expect(restored.getDraft('p', 'n', 'r2')?.freeText).toBe('two')
    restored.clearDraft('p', 'n', 'r1')
    setActivePinia(createPinia())
    const afterCleanup = useInputDraftStore()
    expect(afterCleanup.getDraft('p', 'n', 'r1')).toBeUndefined()
    expect(afterCleanup.getDraft('p', 'n', 'r2')?.freeText).toBe('two')
    expect(afterCleanup.getDraft('other-project', 'n', 'r1')?.freeText).toBe('other')
  })

  it('keeps input usable when browser storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('denied') })
    const store = useInputDraftStore()
    store.setDraft('p', 'n', { selectedOptionId: null, freeText: 'still editable' }, 'r')
    expect(store.getDraft('p', 'n', 'r')?.freeText).toBe('still editable')
    expect(() => store.clearDraft('p', 'n', 'r')).not.toThrow()
  })

  it('ignores corrupt or malformed saved input without blocking valid drafts', () => {
    sessionStorage.setItem(INPUT_DRAFT_STORAGE_KEY, '{broken json')
    expect(useInputDraftStore().draftCount).toBe(0)
    sessionStorage.setItem(INPUT_DRAFT_STORAGE_KEY, JSON.stringify([
      ['p:n:r:', { selectedOptionId: null, freeText: 'valid' }],
      ['p:bad:r:', { selectedOptionId: null, selectedOptionIds: [42], freeText: 'invalid' }],
      ['wrong'],
    ]))
    setActivePinia(createPinia())
    const store = useInputDraftStore()
    expect(store.draftCount).toBe(1)
    expect(store.getDraft('p', 'n', 'r')?.freeText).toBe('valid')
  })
})
