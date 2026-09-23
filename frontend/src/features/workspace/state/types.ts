/**
 * Store-level domain types shared by `workspaceStore.ts` and its per-domain
 * modules under `state/`.
 *
 * They used to be declared inside `workspaceStore.ts`. They are pure type
 * declarations with no store access, so the domain modules import them from
 * here — type-only imports keep the store <-> domain-module relation free of
 * any runtime cycle. `workspaceStore.ts` re-exports them, so the public type
 * surface of the store is unchanged.
 */
import type { RegenerateNodeRequest, SubmitAnswerRequest } from '@/shared/contracts/types'

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

/**
 * Lifecycle of ONE answer submit attempt (one user action, one client
 * request id, one backend run chain).
 *
 * Every lifecycle fact of the attempt lives on the session — never on a
 * shared single-value store field — so two concurrent answers on different
 * routes can never clear, overwrite, or recover each other's state:
 * - success cleanup removes exactly its own session and clears exactly its
 *   own draft (projectId + routeId + nodeId captured at submit time),
 * - failure / unknown-outcome recovery reads and writes only its own session,
 * - resubmit reuses only its own payload.
 */
export type AnswerRunSessionStatus =
  /** Create sent / run polled; this route is locked against a second cycle. */
  | 'RUNNING'
  /** Answer persisted but the follow-up generation is incomplete → repair. */
  | 'REPAIRABLE'
  /** Canonical reads prove nothing landed → provably-safe one-shot resubmit. */
  | 'RESUBMITTABLE'
  /** Terminal state could not be observed → reconcile before any retry. */
  | 'UNKNOWN'

/** One observed answer run attempt. Removed from the store once fully handled. */
export interface AnswerRunSessionState {
  /** Client request identity generated at submit time (idempotency key). */
  clientRequestId: string
  /** Project the submit action started in — fixed for the whole lifecycle. */
  projectId: string
  /** Route the answered node belonged to at submit time (cleanup identity). */
  routeId: string | null
  nodeId: string
  /** Original payload; the only proven-safe resubmit body for this attempt. */
  payload: SubmitAnswerRequest
  /** Backend run id once the create call returned; null until then. */
  runId: string | null
  /** Latest observed run phase (progress display). */
  phase: string | null
  /** Latest observed runtime status (progress display). */
  runStatus: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | null
  status: AnswerRunSessionStatus
  /** Set when the Answer persisted but follow-up generation did not finish. */
  repairableAnswerId: string | null
  /** True when this session repairs a historical checkpoint, not a live tip. */
  historicalRecovery?: boolean
}
