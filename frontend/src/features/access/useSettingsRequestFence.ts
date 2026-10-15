import { onScopeDispose } from 'vue'
import { accessSession } from './accessSession'
import { PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'

export function useSettingsRequestFence() {
  const scope = () => {
    const actor = accessSession.identity
    return actor?.authenticated && actor.userId && actor.orgId
      ? JSON.stringify([actor.userId, actor.orgId, actor.orgRole, [...actor.orgCapabilities].sort()]) : null
  }
  const owner = scope()
  let disposed = false
  onScopeDispose(() => { disposed = true })
  const current = () => !disposed && owner !== null && scope() === owner
  return {
    current,
    async run<T>(work: () => Promise<T>): Promise<T> {
      if (!current()) throw new PrivateIdentityExpiredError()
      const value = await work()
      if (!current()) throw new PrivateIdentityExpiredError()
      return value
    },
  }
}
