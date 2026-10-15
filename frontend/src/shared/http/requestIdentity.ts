// Request scope is per tab. It contains no OIDC credentials or browser session secrets.
let selectedOrg: string | null = null
try { selectedOrg = typeof sessionStorage === 'undefined' ? null : sessionStorage.getItem('spec-agent.org') } catch { /* Use per-tab memory. */ }
let csrf: { headerName: string; token: string } | null = null

export function selectRequestOrg(orgId: string | null) {
  selectedOrg = orgId
  try { if (typeof sessionStorage !== 'undefined') {
    if (orgId) sessionStorage.setItem('spec-agent.org', orgId)
    else sessionStorage.removeItem('spec-agent.org')
  } } catch { /* The live request scope remains correct when storage is unavailable. */ }
}
export function requestOrg() { return selectedOrg }
export function configureCsrf(value: { headerName: string; token: string } | null) { csrf = value }
export function identityHeaders(method = 'GET'): Record<string, string> {
  const headers: Record<string, string> = {}
  if (selectedOrg) headers['X-Spec-Agent-Org-Id'] = selectedOrg
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase()) && csrf?.token) headers[csrf.headerName] = csrf.token
  return headers
}
