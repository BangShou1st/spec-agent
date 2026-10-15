import { beforeEach, describe, expect, it } from 'vitest'
import { accessSession } from './accessSession'
import { useOrganizationCapability } from './useOrganizationCapability'

describe('organization capability projection', () => {
  beforeEach(() => { accessSession.identity = null })
  it('defaults to deny and requires both authentication and a selected organization', () => {
    const allowed = useOrganizationCapability('CREDENTIAL_MANAGE')
    expect(allowed.value).toBe(false)
    accessSession.identity = { mode: 'OIDC', authenticated: false, userId: 'u', orgId: 'o',
      displayName: 'User', orgName: 'Org', orgRole: 'ADMIN', orgCapabilities: ['CREDENTIAL_MANAGE'], memberships: [] }
    expect(allowed.value).toBe(false)
    accessSession.identity.authenticated = true; accessSession.identity.orgId = null
    expect(allowed.value).toBe(false)
  })
  it('uses live capabilities rather than deriving rights from a role label', () => {
    accessSession.identity = { mode: 'OIDC', authenticated: true, userId: 'u', orgId: 'o',
      displayName: 'User', orgName: 'Org', orgRole: 'ADMIN', orgCapabilities: [], memberships: [] }
    const allowed = useOrganizationCapability('CREDENTIAL_MANAGE')
    expect(allowed.value).toBe(false)
    accessSession.identity.orgCapabilities = ['CREDENTIAL_MANAGE']
    expect(allowed.value).toBe(true)
    accessSession.identity.orgCapabilities = []
    expect(allowed.value).toBe(false)
  })
})
