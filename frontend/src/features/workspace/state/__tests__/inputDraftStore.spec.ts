// 文件名:inputDraftStore.spec.ts
// 用途:inputDraftStore 单元测试:验证草稿按 项目+节点+路线 键隔离、会话存储恢复与清除时机。
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { INPUT_DRAFT_STORAGE_KEY, useInputDraftStore } from '@/features/workspace/state/inputDraftStore'

describe('useInputDraftStore', () => {
  it('keeps a verified owners drafts on reload but clears them for another user in the same organization', () => {
    setActivePinia(createPinia())
    const owner = useInputDraftStore()
    owner.bindIdentity(JSON.stringify(['alice', 'org']))
    owner.setDraft('shared-project', 'node', { selectedOptionId: null, freeText: 'alice private' })
    setActivePinia(createPinia())
    const restored = useInputDraftStore()
    restored.bindIdentity(JSON.stringify(['alice', 'org']))
    expect(restored.getDraft('shared-project', 'node')?.freeText).toBe('alice private')
    restored.bindIdentity(JSON.stringify(['bob', 'org']))
    expect(restored.getDraft('shared-project', 'node')).toBeUndefined()
    expect(sessionStorage.getItem(INPUT_DRAFT_STORAGE_KEY)).toBeNull()
  })
  it('does not adopt unattributed legacy drafts and clears a users drafts on logout', () => {
    sessionStorage.removeItem('spec-agent:input-drafts:owner:v1')
    setActivePinia(createPinia())
    const store = useInputDraftStore()
    store.setDraft('p', 'n', { selectedOptionId: null, freeText: 'legacy' })
    store.bindIdentity(JSON.stringify(['alice', 'org']))
    expect(store.getDraft('p', 'n')).toBeUndefined()
    store.setDraft('p', 'n', { selectedOptionId: null, freeText: 'authenticated draft' })
    store.bindIdentity(null)
    expect(store.draftCount).toBe(0)
    expect(sessionStorage.getItem(INPUT_DRAFT_STORAGE_KEY)).toBeNull()
  })
  it('clears all route drafts for a revoked project without clearing another project', () => {
    setActivePinia(createPinia())
    const store = useInputDraftStore()
    store.setDraft('project-a', 'n1', { selectedOptionId: null, freeText: 'private draft' }, 'r1')
    store.setDraft('project-a', 'n1', { selectedOptionId: null, freeText: 'another route' }, 'r2')
    store.setDraft('project-ab', 'n1', { selectedOptionId: null, freeText: 'keep' })
    store.clearProject('project-a')
    expect(store.getDraft('project-a', 'n1', 'r1')).toBeUndefined()
    expect(store.getDraft('project-a', 'n1', 'r2')).toBeUndefined()
    expect(store.getDraft('project-ab', 'n1')?.freeText).toBe('keep')
    expect(sessionStorage.getItem(INPUT_DRAFT_STORAGE_KEY)).not.toContain('private draft')
  })
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

    // 模拟拖动不会触碰 store——只有用不同节点 id 重新挂载才会加载
    // 另一个键。
    const draft = store.getDraft('proj-1', 'node-1')
    expect(draft?.freeText).toBe('keep me')
  })

  it('submit does not clear draft until explicit clear', () => {
    const store = useInputDraftStore()
    store.setDraft('proj-1', 'node-1', { selectedOptionId: 'opt-a', freeText: '' })
    // 提交之后草稿保留,直到 run 完成并调用 clearDraft。
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
