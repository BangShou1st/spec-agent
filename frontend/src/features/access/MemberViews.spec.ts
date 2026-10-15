import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { apiClient } from '@/shared/http/client'
import { accessSession } from './accessSession'
import OrganizationMembersView from './OrganizationMembersView.vue'
import ProjectMembersView from './ProjectMembersView.vue'

const controls = vi.hoisted(() => ({ access: null as unknown as { loading: ReturnType<typeof ref<boolean>>; canManage: ReturnType<typeof ref<boolean>> } }))
vi.mock('./useProjectAccess', () => ({ useProjectAccess: () => controls.access }))
vi.mock('@/shared/http/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), put: vi.fn(), delete: vi.fn() } }))
const api = vi.mocked(apiClient)
let wrapper: VueWrapper | undefined
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
function identity(org = 'org-a', capabilities = ['ORG_MANAGE', 'ORG_OWNER']) {
  accessSession.identity = { mode: 'OIDC', authenticated: true, userId: 'user-a', orgId: org, orgRole: 'OWNER', orgCapabilities: capabilities, displayName: 'Alice', orgName: org, memberships: [] }
}
const orgMember = { userId: 'target', displayName: 'Old Private Member', role: 'MEMBER', status: 'ACTIVE' }
const projectMember = { userId: 'target', displayName: 'Old Project Member', role: 'VIEWER' }
beforeEach(() => { vi.clearAllMocks(); identity(); controls.access = { loading: ref(false), canManage: ref(true) }; api.get.mockResolvedValue([]) })
afterEach(() => { wrapper?.unmount(); wrapper=undefined })

it('rejects old organization list after scope change and renders only the new list', async () => {
  const old = deferred<unknown>()
  api.get.mockImplementation(path => path === '/organizations/org-a/members' ? old.promise as never : Promise.resolve([{ ...orgMember, displayName: 'New Member' }]) as never)
  wrapper = mount(OrganizationMembersView)
  identity('org-b')
  await flushPromises()
  old.resolve([orgMember]); await flushPromises()
  expect(wrapper.text()).toContain('New Member')
  expect(wrapper.text()).not.toContain('Old Private Member')
  expect(api.get).toHaveBeenCalledWith('/organizations/org-b/members')
})

it('does not follow a completed old organization grant into the new organization', async () => {
  const old = deferred<unknown>()
  api.post.mockReturnValue(old.promise as never)
  wrapper = mount(OrganizationMembersView); await flushPromises()
  await wrapper.get('#member-user-id').setValue('new-target')
  await wrapper.get('form').trigger('submit')
  expect(api.post).toHaveBeenCalledWith('/organizations/org-a/members', { userId: 'new-target', role: 'MEMBER' })
  identity('org-b'); await flushPromises()
  const calls = api.get.mock.calls.length
  await wrapper.get('#member-user-id').setValue('new-draft')
  old.resolve(undefined); await flushPromises()
  expect(api.get).toHaveBeenCalledTimes(calls)
  expect((wrapper.get('#member-user-id').element as HTMLInputElement).value).toBe('new-draft')
})

it('removes private members, drafts and removal confirmation on organization revocation', async () => {
  api.get.mockResolvedValue([orgMember] as never)
  wrapper = mount(OrganizationMembersView); await flushPromises()
  await wrapper.get('#member-user-id').setValue('private-draft')
  await wrapper.get('li button').trigger('click')
  expect(wrapper.find('[data-test="ui-confirm"]').exists()).toBe(true)
  identity('org-a', []); await flushPromises()
  expect(wrapper.text()).not.toContain(orgMember.displayName)
  expect(wrapper.find('[data-test="ui-confirm"]').exists()).toBe(false)
  identity(); await flushPromises()
  expect((wrapper.get('#member-user-id').element as HTMLInputElement).value).toBe('')
  expect(wrapper.find('[data-test="ui-confirm"]').exists()).toBe(false)
  expect(api.delete).not.toHaveBeenCalled()
})

it('resets OWNER selection when owner authority is removed without removing organization management', async () => {
  wrapper = mount(OrganizationMembersView); await flushPromises()
  await wrapper.get('#member-role').setValue('OWNER')
  await wrapper.get('#member-user-id').setValue('target')
  identity('org-a', ['ORG_MANAGE']); await flushPromises()
  expect((wrapper.get('#member-role').element as HTMLSelectElement).value).toBe('MEMBER')
  expect(wrapper.get('#member-role').findAll('option').map(o => o.attributes('value'))).toEqual(['MEMBER','ADMIN'])
  expect(api.post).not.toHaveBeenCalled()
})

it('does not load members or show mutation forms for a project viewer', async () => {
  controls.access.canManage.value = false
  wrapper = mount(ProjectMembersView, { props: { projectId: 'p-a' }, global: { stubs: { RouterLink: true } } }); await flushPromises()
  expect(api.get).not.toHaveBeenCalled()
  expect(wrapper.find('form').exists()).toBe(false)
  expect(wrapper.text()).toContain('你没有项目成员管理权限')
})

it('binds project URLs to the current prop and discards a prior project response', async () => {
  const old = deferred<unknown>()
  api.get.mockImplementation(path => path === '/projects/p-a/members' ? old.promise as never : Promise.resolve([{ ...projectMember, displayName: 'Current Project Member' }]) as never)
  wrapper = mount(ProjectMembersView, { props: { projectId: 'p-a' }, global: { stubs: { RouterLink: true } } })
  await wrapper.setProps({ projectId: 'p-b' }); await flushPromises()
  old.resolve([projectMember]); await flushPromises()
  expect(wrapper.text()).toContain('Current Project Member')
  expect(wrapper.text()).not.toContain('Old Project Member')
  await wrapper.get('#project-member-user').setValue('new-target')
  await wrapper.get('form').trigger('submit')
  expect(api.put).toHaveBeenCalledWith('/projects/p-b/members/new-target', { role: 'VIEWER' })
})

it('clears project members and pending deletion when PROJECT_MANAGE is revoked', async () => {
  api.get.mockResolvedValue([projectMember] as never)
  wrapper = mount(ProjectMembersView, { props: { projectId: 'p-a' }, global: { stubs: { RouterLink: true } } }); await flushPromises()
  await wrapper.get('li button').trigger('click')
  expect(wrapper.find('[data-test="ui-confirm"]').exists()).toBe(true)
  controls.access.canManage.value = false; await flushPromises()
  expect(wrapper.text()).not.toContain(projectMember.displayName)
  expect(wrapper.find('[data-test="ui-confirm"]').exists()).toBe(false)
  controls.access.canManage.value = true; await flushPromises()
  expect(wrapper.find('[data-test="ui-confirm"]').exists()).toBe(false)
  expect(api.delete).not.toHaveBeenCalled()
})

it('preserves a draft across a pending permission refresh while blocking old writes', async () => {
  const old = deferred<unknown>()
  api.put.mockReturnValue(old.promise as never)
  wrapper = mount(ProjectMembersView, { props: { projectId: 'p-a' }, global: { stubs: { RouterLink: true } } }); await flushPromises()
  await wrapper.get('#project-member-user').setValue('draft-user')
  await wrapper.get('form').trigger('submit')
  controls.access.loading.value = true; controls.access.canManage.value = false; await flushPromises()
  expect(wrapper.find('form').exists()).toBe(false)
  controls.access.canManage.value = true; controls.access.loading.value = false; await flushPromises()
  const calls = api.get.mock.calls.length
  old.resolve(undefined); await flushPromises()
  expect(api.get).toHaveBeenCalledTimes(calls)
  expect((wrapper.get('#project-member-user').element as HTMLInputElement).value).toBe('draft-user')
  expect(wrapper.get('form button').attributes('disabled')).toBeUndefined()
})

it('ignores an unmounted grant completion and never follows it with a list request', async () => {
  const old = deferred<unknown>()
  api.post.mockReturnValue(old.promise as never)
  wrapper = mount(OrganizationMembersView); await flushPromises()
  await wrapper.get('#member-user-id').setValue('target')
  await wrapper.get('form').trigger('submit')
  const calls = api.get.mock.calls.length
  wrapper.unmount(); wrapper=undefined
  old.resolve(undefined); await flushPromises()
  expect(api.get).toHaveBeenCalledTimes(calls)
})
