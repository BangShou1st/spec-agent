import { computed, onScopeDispose, watch } from 'vue'
import { accessSession } from './accessSession'
import { PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'

/** Scope local member data and each request to the live actor/resource/authority. */
export function useMemberRequestScope(resource: () => string | null, permitted: () => boolean,
  denied: () => boolean, reset: () => void) {
  let generation = 0
  let disposed = false
  const key = computed(() => {
    const actor = accessSession.identity
    const target = resource()
    return actor?.authenticated && actor.userId && actor.orgId && target
      ? JSON.stringify([actor.userId, actor.orgId, actor.orgRole,
        [...actor.orgCapabilities].sort(), target]) : null
  })
  watch(key, () => { generation++; reset() }, { flush: 'sync' })
  watch(() => [permitted(), denied()] as const, ([, isDenied]) => {
    generation++
    if (isDenied) reset()
  }, { flush: 'sync' })
  onScopeDispose(() => { disposed = true; generation++; reset() })
  function capture() {
    const owner = key.value
    const epoch = generation
    const current = () => !disposed && owner !== null && key.value === owner
      && epoch === generation && permitted()
    return {
      current,
      async run<T>(work: () => Promise<T>): Promise<T> {
        if (!current()) throw new PrivateIdentityExpiredError()
        const result = await work()
        if (!current()) throw new PrivateIdentityExpiredError()
        return result
      },
    }
  }
  return { key, capture }
}
