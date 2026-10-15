import { accessSession } from '@/features/access/accessSession'

export function canCreatePersonalConnection(): boolean {
  return !!accessSession.identity?.authenticated && !!accessSession.identity.orgId
}
/** USER rows are only visible to their owner in the server-scoped query. */
export function canManageConnection(connection: { ownerKind?: string } | null | undefined): boolean {
  if (!canCreatePersonalConnection()) return false
  if (connection?.ownerKind === 'USER') return true
  return connection?.ownerKind === 'ORG'
    && !!accessSession.identity?.orgCapabilities.includes('CREDENTIAL_MANAGE')
}

/** Capability APIs require an enabled, tested/connected connection. */
export function canReadConnectionCapabilities(connection: { enabled: boolean; status: string } | null | undefined): boolean {
  return !!connection?.enabled && (connection.status === 'TESTED' || connection.status === 'CONNECTED')
}
