import { computed } from 'vue'
import { accessSession } from './accessSession'

/** UI projection only; each server operation still checks live authorization. */
export function useOrganizationCapability(capability: string) {
  return computed(() => !!accessSession.identity?.authenticated
    && !!accessSession.identity.orgId
    && accessSession.identity.orgCapabilities.includes(capability))
}
