/**
 * Store-level domain types shared by `workspaceStore.ts` and its per-domain
 * modules under `stores/workspace/`.
 *
 * They used to be declared inside `workspaceStore.ts`. They are pure type
 * declarations with no store access, so the domain modules import them from
 * here — type-only imports keep the store <-> domain-module relation free of
 * any runtime cycle. `workspaceStore.ts` re-exports them, so the public type
 * surface of the store is unchanged.
 */
import type { RegenerateNodeRequest } from '@/api/types'

/** Precise route command in flight, used for pending labels and lockouts. */
export type PendingRouteCommand =
  | 'activate'
  | 'restore'
  | 'archive'
  | 'delete'
  | 'fork'
  | 'reanswer'
  | 'regenerate'
  | null

export type RetryState = 'ready' | 'needs_reconcile' | 'ambiguous'

export type ManualModelRetryIntent =
  | {
      kind: 'draft'
      beforeRouteId: string | null
      beforeTipNodeId: string | null
      state: RetryState
    }
  | {
      kind: 'spec'
      routeId: string
      beforeSpecIds: string[]
      state: RetryState
    }
  | {
      kind: 'regenerate'
      nodeId: string
      payload: RegenerateNodeRequest
      beforeRouteIds: string[]
      beforeActiveRouteId: string | null
      state: RetryState
    }

export type MutationFocusTarget = {
  routeId: string
  nodeId: string | null
}
