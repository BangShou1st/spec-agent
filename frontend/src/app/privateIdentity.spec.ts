import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { accessSession, type Identity } from '@/features/access/accessSession'
import { useInputDraftStore } from '@/features/workspace/state/inputDraftStore'
import { useModelSettingsStore } from '@/features/model-settings/state/modelSettingsStore'

vi.mock('@/features/access/accessSession', async () => ({
  ...(await vi.importActual<typeof import('@/features/access/accessSession')>('@/features/access/accessSession')),
  refreshSession: vi.fn(),
}))
const actor = (user: string): Identity => ({ mode: 'OIDC', authenticated: true,
  userId: user, displayName: user, orgId: 'org', orgName: 'Org', orgRole: 'MEMBER',
  orgCapabilities: [], memberships: [{ orgId: 'org', orgName: 'Org', role: 'MEMBER' }] })

describe('private root identity', () => {
  it('clears settings and remounts local forms when the same user loses organization capabilities', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    const mounted = vi.fn()
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/settings',
      component: { template: '<p>Settings form</p>', mounted } }] })
    await router.push('/settings'); await router.isReady()
    mount(App, { global: { plugins: [pinia, router],
      stubs: { AccountBar: true, GlobalAssistantShell: true } } })
    accessSession.identity = { ...actor('alice'), orgRole: 'ADMIN', orgCapabilities: ['ORG_MANAGE', 'CREDENTIAL_MANAGE'] }
    accessSession.loading = false; await flushPromises()
    const settings = useModelSettingsStore(); settings.selectedModel = 'old setting'
    const epoch = settings.identityEpoch
    accessSession.identity = { ...actor('alice'), orgRole: 'ADMIN', orgCapabilities: ['CREDENTIAL_MANAGE', 'ORG_MANAGE'] }
    await flushPromises()
    expect(settings.identityEpoch).toBe(epoch)
    expect(mounted).toHaveBeenCalledTimes(1)
    accessSession.identity = actor('alice'); await flushPromises()
    expect(settings.selectedModel).toBeNull()
    expect(settings.identityEpoch).toBeGreaterThan(epoch)
    expect(mounted).toHaveBeenCalledTimes(2)
  })
  beforeEach(() => { accessSession.identity = null; accessSession.loading = true; accessSession.error = '' })
  it('retains same-user drafts during bootstrap, remounts for a different user and clears on logout', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    const drafts = useInputDraftStore()
    drafts.bindIdentity(JSON.stringify(['alice', 'org']))
    drafts.setDraft('shared-project', 'node', { selectedOptionId: null, freeText: 'private' })
    const mounted = vi.fn()
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/',
      component: { template: '<p>Project route</p>', mounted } }] })
    await router.push('/'); await router.isReady()
    const wrapper = mount(App, { global: { plugins: [pinia, router],
      stubs: { AccountBar: true, GlobalAssistantShell: true } } })
    expect(drafts.draftCount).toBe(1)
    accessSession.identity = actor('alice'); accessSession.loading = false
    await flushPromises()
    expect(drafts.getDraft('shared-project', 'node')?.freeText).toBe('private')
    expect(mounted).toHaveBeenCalledTimes(1)
    accessSession.identity = actor('bob'); await flushPromises()
    expect(drafts.draftCount).toBe(0)
    expect(mounted).toHaveBeenCalledTimes(2)
    drafts.setDraft('shared-project', 'node', { selectedOptionId: null, freeText: 'bob private' })
    accessSession.identity = null; await flushPromises()
    expect(drafts.draftCount).toBe(0)
    expect(wrapper.text()).toContain('登录 Spec Agent')
  })
})
