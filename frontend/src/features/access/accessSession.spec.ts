import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { accessSession, refreshSession, renewSession, SESSION_REFRESH_TIMEOUT_MS, logout, switchOrganization, type Identity } from './accessSession'
import { configureCsrf, identityHeaders, requestOrg, selectRequestOrg } from '@/shared/http/requestIdentity'

const actor = (user: string, org: string): Identity => ({ mode: 'OIDC', authenticated: true,
  userId: user, displayName: user, orgId: org, orgName: org, orgRole: 'MEMBER',
  orgCapabilities: [], memberships: [{ orgId: org, orgName: org, role: 'MEMBER' }] })
const bootstrap = { mode: 'OIDC', authenticated: true, loginUrl: '/login' }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

describe('session refresh ownership', () => {
  const navigate = vi.fn()
  const fetchMock = vi.fn<typeof fetch>()
  beforeEach(() => {
    navigate.mockReset(); fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('window', { location: { assign: navigate } })
    accessSession.identity = null; accessSession.loading = false; accessSession.error = ''
    selectRequestOrg(null); configureCsrf(null)
  })
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
  it('does not publish an actor or organization before CSRF bootstrap completes', async () => {
    const csrf = deferred<Response>()
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockResolvedValueOnce(response(actor('alice', 'a')))
      .mockReturnValueOnce(csrf.promise)
    const pending = refreshSession(); await flushPromises()
    expect(accessSession.identity).toBeNull(); expect(requestOrg()).toBeNull()
    csrf.resolve(response({ headerName: 'X-CSRF', token: 'fake-a' })); await pending
    expect(accessSession.identity?.userId).toBe('alice'); expect(requestOrg()).toBe('a')
    expect(identityHeaders('POST')['X-CSRF']).toBe('fake-a')
  })
  it('rejects an old actor response after the new refresh has committed', async () => {
    const old = deferred<Response>()
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockReturnValueOnce(old.promise)
    const first = refreshSession(); await flushPromises()
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockResolvedValueOnce(response(actor('bob', 'b')))
      .mockResolvedValueOnce(response({ headerName: 'X-CSRF', token: 'fake-b' }))
    await refreshSession()
    old.resolve(response(actor('alice', 'a'))); await first
    expect(accessSession.identity?.userId).toBe('bob'); expect(requestOrg()).toBe('b')
    expect(identityHeaders('POST')['X-CSRF']).toBe('fake-b')
  })
  it('does not restore an identity or CSRF from a response arriving after logout', async () => {
    const csrf = deferred<Response>()
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockResolvedValueOnce(response(actor('alice', 'a')))
      .mockReturnValueOnce(csrf.promise)
    const old = refreshSession(); await flushPromises()
    fetchMock.mockResolvedValueOnce(response({}))
    await logout()
    csrf.resolve(response({ headerName: 'X-CSRF', token: 'stale' })); await old
    expect(accessSession.identity).toBeNull(); expect(requestOrg()).toBeNull()
    expect(identityHeaders('POST')['X-CSRF']).toBeUndefined()
    expect(navigate).toHaveBeenCalledWith('/')
  })
  it('does not replace a newly selected organization with the old refresh result', async () => {
    const old = deferred<Response>()
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockReturnValueOnce(old.promise)
    const pending = refreshSession(); await flushPromises()
    switchOrganization('new-org')
    old.resolve(response(actor('alice', 'old-org'))); await pending
    expect(requestOrg()).toBe('new-org'); expect(accessSession.identity).toBeNull()
    expect(navigate).toHaveBeenCalledWith('/projects')
  })
  it('blocks refresh while logout is pending and releases the loading state on failure', async () => {
    accessSession.identity = actor('alice', 'a')
    configureCsrf({ headerName: 'X-CSRF', token: 'logout-token' })
    const pendingResponse = deferred<Response>(); fetchMock.mockReturnValueOnce(pendingResponse.promise)
    const pending = logout(); const rejected = expect(pending).rejects.toThrow('退出失败')
    expect(accessSession.identity).toBeNull()
    expect(identityHeaders('POST')['X-CSRF']).toBeUndefined()
    expect(fetchMock.mock.calls[0]![1]?.headers).toEqual({ 'X-CSRF': 'logout-token' })
    await refreshSession(); expect(fetchMock).toHaveBeenCalledTimes(1)
    pendingResponse.resolve(response({}, 500)); await rejected
    expect(accessSession.loading).toBe(false); expect(accessSession.identity).toBeNull()
    expect(accessSession.error).toContain('退出失败')
  })
  it('does not retain old CSRF when current refresh fails', async () => {
    configureCsrf({ headerName: 'X-CSRF', token: 'old' })
    fetchMock.mockRejectedValueOnce(new Error('offline'))
    await refreshSession()
    expect(accessSession.identity).toBeNull()
    expect(identityHeaders('POST')['X-CSRF']).toBeUndefined()
  })
  it('keeps the new refresh loading when an old response is rejected', async () => {
    const old = deferred<Response>(), csrf = deferred<Response>()
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockReturnValueOnce(old.promise)
    const first = refreshSession(); await flushPromises()
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockResolvedValueOnce(response(actor('bob', 'b')))
      .mockReturnValueOnce(csrf.promise)
    const second = refreshSession(); await flushPromises()
    old.resolve(response(actor('alice', 'a'))); await first
    expect(accessSession.loading).toBe(true)
    expect(accessSession.identity).toBeNull()
    csrf.resolve(response({ headerName: 'X-CSRF', token: 'fake-b' })); await second
    expect(accessSession.identity?.userId).toBe('bob'); expect(accessSession.loading).toBe(false)
  })
  it('keeps live request scope correct when browser storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('storage denied') })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('storage denied') })
    expect(() => selectRequestOrg('live-org')).not.toThrow()
    expect(identityHeaders()['X-Spec-Agent-Org-Id']).toBe('live-org')
    expect(() => selectRequestOrg(null)).not.toThrow()
    expect(identityHeaders()['X-Spec-Agent-Org-Id']).toBeUndefined()
  })
  it('updates capabilities in the background without hiding a valid page', async () => {
    accessSession.identity = { ...actor('alice', 'a'), orgCapabilities: ['CREDENTIAL_MANAGE'] }
    fetchMock.mockResolvedValueOnce(response(bootstrap)).mockResolvedValueOnce(response(actor('alice', 'a')))
      .mockResolvedValueOnce(response({ headerName: 'X-CSRF', token: 'same-session' }))
    const pending = renewSession()
    expect(accessSession.loading).toBe(false)
    await pending
    expect(accessSession.identity?.orgCapabilities).toEqual([])
  })
  it('expires a stuck identity check and refuses its late response', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    accessSession.identity = actor('alice', 'a')
    const late = deferred<Response>(); fetchMock.mockReturnValueOnce(late.promise)
    const pending = renewSession()
    await vi.advanceTimersByTimeAsync(SESSION_REFRESH_TIMEOUT_MS)
    expect(accessSession.identity).toBeNull()
    expect(accessSession.loading).toBe(false)
    expect(accessSession.error).toContain('无法确认账户状态')
    late.resolve(response(bootstrap)); await pending
    expect(accessSession.identity).toBeNull()
  })
})
