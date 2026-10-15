import { reactive } from 'vue'
import { configureCsrf, identityHeaders, requestOrg, selectRequestOrg } from '@/shared/http/requestIdentity'

export interface Membership { orgId: string; orgName: string; role: string }
export interface Identity {
  mode: 'LOCAL' | 'OIDC'; authenticated: boolean; userId: string | null; displayName: string | null
  orgId: string | null; orgName: string | null; orgRole: string | null; orgCapabilities: string[]; memberships: Membership[]
}
export const accessSession = reactive({ loading: true, identity: null as Identity | null,
  loginUrl: null as string | null, error: '', revision: 0 })

let sessionGeneration = 0
let refreshController: AbortController | null = null
let logoutPending = false

function invalidateRefresh(): number {
  sessionGeneration++
  refreshController?.abort()
  refreshController = null
  return sessionGeneration
}

export function refreshSession() { return performRefresh(true) }
export function renewSession() { return performRefresh(false) }
export const SESSION_REFRESH_TIMEOUT_MS = 5_000

async function performRefresh(foreground: boolean) {
  if (logoutPending) return
  const generation = invalidateRefresh()
  const controller = new AbortController()
  refreshController = controller
  const current = () => generation === sessionGeneration
  if (foreground) accessSession.loading = true
  accessSession.error = ''
  const deadline = setTimeout(() => {
    if (!current()) return
    sessionGeneration++
    refreshController = null
    controller.abort()
    configureCsrf(null)
    accessSession.identity = null
    accessSession.error = '无法确认账户状态，请重试。'
    accessSession.loading = false
  }, SESSION_REFRESH_TIMEOUT_MS)
  try {
    const status = await fetch('/api/session', { credentials: 'same-origin', signal: controller.signal })
    if (!current()) return
    if (!status.ok) throw new Error('无法确认登录状态，请重试。')
    const bootstrap = await status.json() as { mode: string; authenticated: boolean; loginUrl: string | null }
    if (!current()) return
    accessSession.loginUrl = bootstrap.loginUrl
    if (!bootstrap.authenticated && bootstrap.mode === 'OIDC') {
      accessSession.identity = null
      configureCsrf(null)
      return
    }
    const response = await fetch('/api/me', { credentials: 'same-origin', headers: identityHeaders(), signal: controller.signal })
    if (!current()) return
    if (response.status === 401) { accessSession.identity = null; configureCsrf(null); return }
    if (!response.ok) throw new Error('无法读取账户信息，请重试。')
    const identity = await response.json() as Identity
    if (!current()) return
    // Do not publish the new actor or change request scope before its CSRF
    // bootstrap succeeds. Every asynchronous boundary is generation checked.
    const csrfResponse = await fetch('/api/csrf', { credentials: 'same-origin', signal: controller.signal })
    if (!current()) return
    if (!csrfResponse.ok) throw new Error('无法准备会话，请重新登录。')
    const csrf = await csrfResponse.json() as { headerName: string; token: string }
    if (!current()) return
    if (requestOrg() && !identity.memberships.some(m => m.orgId === requestOrg())) selectRequestOrg(null)
    if (identity.orgId) selectRequestOrg(identity.orgId)
    configureCsrf(csrf)
    accessSession.identity = identity
    accessSession.revision++
  } catch (error) {
    if (!current()) return
    accessSession.error = error instanceof Error ? error.message : '无法连接服务，请重试。'
    configureCsrf(null)
    accessSession.identity = null
  } finally {
    clearTimeout(deadline)
    if (current()) { refreshController = null; accessSession.loading = false }
  }
}
export function switchOrganization(orgId: string) {
  invalidateRefresh()
  accessSession.loading = true
  accessSession.identity = null
  accessSession.revision++
  configureCsrf(null)
  selectRequestOrg(orgId)
  window.location.assign('/projects')
}
export async function logout() {
  if (logoutPending) return
  const generation = invalidateRefresh()
  logoutPending = true
  const headers = identityHeaders('POST')
  accessSession.loading = true
  accessSession.identity = null
  accessSession.revision++
  selectRequestOrg(null); configureCsrf(null)
  try {
    const response = await fetch('/api/logout', { method: 'POST', credentials: 'same-origin', headers })
    if (generation !== sessionGeneration) return
    if (!response.ok) throw new Error('退出失败，请重试。')
    accessSession.identity = null
    accessSession.revision++
    selectRequestOrg(null); configureCsrf(null)
    window.location.assign('/')
  } catch (error) {
    if (generation === sessionGeneration) accessSession.error = '退出失败，请重试。'
    throw error
  } finally {
    logoutPending = false
    if (generation === sessionGeneration) accessSession.loading = false
  }
}
