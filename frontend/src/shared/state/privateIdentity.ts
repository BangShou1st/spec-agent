export class PrivateIdentityExpiredError extends Error {}
const roots = new WeakMap<object, object>()

/** Protect actual continuation reads/writes as well as promise settlement. */
export function identityScopedStore<T extends { identityEpoch: number }>(store: T, epoch: number): T {
  const root = (roots.get(store) ?? store) as T
  const scoped = new Proxy(root, {
    get(target, key) {
      if (target.identityEpoch !== epoch) throw new PrivateIdentityExpiredError()
      return Reflect.get(target, key, target)
    },
    set(target, key, value) {
      if (target.identityEpoch !== epoch) throw new PrivateIdentityExpiredError()
      return Reflect.set(target, key, value, target)
    },
  })
  roots.set(scoped, root)
  return scoped
}

/** The work factory prevents a stale continuation from starting another call. */
export async function privateIdentityWork<T>(store: { identityEpoch: number }, epoch: number,
  work: () => Promise<T>): Promise<T> {
  if (store.identityEpoch !== epoch) throw new PrivateIdentityExpiredError()
  const result = await work()
  if (store.identityEpoch !== epoch) throw new PrivateIdentityExpiredError()
  return result
}
