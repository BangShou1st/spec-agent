/**
 * Domain slices: narrow, type-level views over the workspace store.
 *
 * Each domain module receives one of these instead of the full
 * `WorkspaceStore`. Because the slice is a structural type, the real store
 * satisfies it without any wrapper — but any access to a member NOT listed
 * here is a compile error, so cross-domain state writes are limited at the
 * type level instead of by comment convention.
 *
 * Three kinds of members are expressed separately in every slice:
 *
 * 1. Read-only inputs (`ReadonlyPick`) — project identity and canonical
 *    backend reads (`graphView`, `activeState`, …). The slice may READ them
 *    (including nested properties and array entries), but reassignment AND
 *    nested mutation are compile errors: `DeepReadonly` makes every nested
 *    object and array deeply readonly, not just the top-level reference.
 * 2. Domain-owned writable state (`Pick`) — the flags/caches this domain
 *    sets, clears, and reconciles. Single-writer-per-UI-intent stays a
 *    review convention here; ownership across sessions is enforced at
 *    runtime by the session guards, not by the type system.
 * 3. Cross-domain commands — the established convention: cross-domain calls
 *    go through store actions (`store.xxx()`), never by importing a sibling
 *    domain module directly (that would create an import cycle).
 *
 * Negative compile-time proof lives in
 * `stores/workspace/__tests__/slicesReadonly.spec.ts`: every forbidden
 * cross-domain write is marked `@ts-expect-error`, so the typecheck fails if
 * the read-only contract ever regresses.
 *
 * To add a member to a slice you must justify it here, in the grouping
 * comments. That is the review gate.
 */
import type { WorkspaceStore } from '../workspaceStore'

/**
 * Deeply readonly view of a value. Functions are preserved as-is (mapped
 * types would destroy call signatures), arrays become readonly arrays with
 * readonly elements, and objects become objects with readonly properties
 * whose types are themselves deeply readonly.
 */
export type DeepReadonly<T> =
  T extends (...args: never[]) => unknown ? T
    : T extends readonly (infer U)[] ? readonly DeepReadonly<U>[]
      : T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
        : T

/**
 * Picks members of S and exposes them deeply read-only: no reassignment of
 * the member, no mutation of nested objects or arrays.
 */
type ReadonlyPick<S, K extends keyof S> = {
  readonly [P in K]: DeepReadonly<S[P]>
}

/**
 * What the async run-orchestration domain (`workspaceRuns.ts`) may touch:
 *
 * - Read-only inputs: project identity (captured at async start,
 *   re-validated after every await) and the canonical graph + Active
 *   pointer, read for route-tip/answer resolution and the explicit
 *   "current route" semantics (draft/retry/repair target the route the
 *   user is reading). Never written by this domain.
 * - Answer-run sessions — owned by this domain.
 * - Run orchestration flags — single-writer-per-UI-intent flags this domain
 *   sets and clears (drafting, repairing, projections, manual retry, fork
 *   draft retry).
 * - Global UI feedback (`feedback`/`error`) — intentionally shared surfaces;
 *   every write must be behind a stale-identity check.
 */
export type AnswerRunSlice =
  ReadonlyPick<
    WorkspaceStore,
    // Read-only inputs: project identity + canonical graph/Active pointer
    'projectId'
    | 'projectSessionId'
    | 'project'
    | 'graphView'
    | 'activeState'
  > & Pick<
    WorkspaceStore,
    // Answer-run sessions (owned by this domain)
    | 'answerRunSessions'
    | 'canonicalRepairableAnswerId'
    | 'focusedAnswerSession'
    | 'submittedRouteIdForCleanup'
    | 'resubmitAnswerPayload'
    | 'answerRunsInFlight'
    | 'submitting'
    // Run orchestration flags (single-writer)
    | 'drafting'
    | 'repairingAnswer'
    | 'pendingDraftRespondMessage'
    | 'forkDraftRetryRouteId'
    | 'manualModelRetry'
    | 'pendingRouteProjection'
    | 'routeCommandPending'
    // Shared UI feedback surfaces (write only behind a stale-identity check)
    | 'feedback'
    | 'error'
    // Cross-domain actions via the store facade
    | 'refreshWorkspace'
    | 'draftQuestion'
    | 'markPendingRouteFailed'
    | 'updatePendingRouteProjection'
    | 'setFocusAfterMutation'
    | 'focusAfterMutation'
    | 'retryForkDraft'
    | 'retryManualModelOperation'
    | 'submitAnswer'
    | 'pollAnswerRun'
    | 'pollDraftRun'
    | 'pollRunChainToTerminal'
    | 'finishSuccessfulAnswerRun'
    | 'reconcileFailedAnswerRun'
    | 'findFinalizedAnswerForActiveTip'
    | 'reconcileSpecRetry'
    | 'reconcileRegenerateRetry'
    | 'generateSpec'
    | 'regenerateNode'
  >

/**
 * Proposal (node query) domain: loading and polling node query proposals.
 * Owns `nodeQuery`, the two durable proposal lists, and their lifecycle
 * transitions; identity and canonical action helpers are read-only.
 */
export type ProposalSlice =
  ReadonlyPick<WorkspaceStore, 'projectId' | 'projectSessionId'> & Pick<
    WorkspaceStore,
    | 'nodeQuery'
    | 'nodeQueryProposals'
    | 'pendingConfirmableProposals'
    | 'nodeRouteIds'
    | 'loadNodeQueryProposals'
    | 'pollNodeQuery'
    | 'pollRunChainToTerminal'
    | 'refreshUndoRedoAvailability'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/**
 * Resource/graph-command domain: forking routes and resource graph commands.
 * Canonical graph + Active route are read-only inputs (route membership and
 * tip resolution); this domain never rewrites them locally.
 */
export type ResourceSlice =
  ReadonlyPick<
    WorkspaceStore,
    'projectId' | 'projectSessionId' | 'graphView' | 'activeState' | 'activeRoute'
  > & Pick<
    WorkspaceStore,
    | 'graphCommandPending'
    | 'forkNode'
    | 'draftQuestion'
    | 'setFocusAfterMutation'
    | 'refreshUndoRedoAvailability'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/**
 * Spec-dock domain: requirement/spec generation, export, and per-route spec
 * state. Identity, the Active pointer, and the run-orchestration lock
 * (`routeCommandPending`) are read-only inputs. The domain OWNS the
 * `spec`-kind entries of `manualModelRetry` (generation/reconciliation write
 * and clear them), its per-route caches, and its own loading/generation
 * flags.
 */
export type SpecDockSlice =
  ReadonlyPick<
    WorkspaceStore,
    'projectId' | 'projectSessionId' | 'activeState' | 'routeCommandPending'
  > & Pick<
    WorkspaceStore,
    | 'specsByRoute'
    | 'selectedSpecIdByRoute'
    | 'requirementStatesByRoute'
    | 'loadingRequirementRouteId'
    | 'loadingSpecs'
    | 'generatingSpec'
    | 'exportingSpec'
    | 'manualModelRetry'
    | 'reconcileSpecRetry'
    | 'pollRunChainToTerminal'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/**
 * Graph undo domain: undo/redo availability and graph history commands.
 * Only its own availability cache, lock flag, and the shared feedback
 * surfaces are writable.
 */
export type GraphUndoSlice =
  ReadonlyPick<WorkspaceStore, 'projectId' | 'projectSessionId'> & Pick<
    WorkspaceStore,
    | 'undoRedo'
    | 'graphCommandPending'
    | 'refreshUndoRedoAvailability'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/**
 * Route-command domain: route lifecycle commands (activate/close/…), draft
 * orchestration hand-off, and post-mutation focus. Canonical graph/Active
 * pointer and the answer-run lock (`submitting`) are read-only inputs.
 * Writes `drafting`/`manualModelRetry`/`forkDraftRetryRouteId` because route
 * commands drive runs; those flags remain single-writer-per-UI-intent by
 * convention.
 */
export type RouteCommandSlice =
  ReadonlyPick<
    WorkspaceStore,
    'projectId' | 'projectSessionId' | 'graphView' | 'activeState' | 'submitting'
  > & Pick<
    WorkspaceStore,
    | 'pendingRouteCommand'
    | 'routeCommandPending'
    | 'drafting'
    | 'manualModelRetry'
    | 'forkDraftRetryRouteId'
    | 'draftQuestion'
    | 'reconcileRegenerateRetry'
    | 'pollRunChainToTerminal'
    | 'refreshWorkspace'
    | 'setFocusAfterMutation'
    | 'feedback'
    | 'error'
  >

/**
 * Minimal identity view for shared helpers (`captureProjectSession`).
 * Identity is immutable for every consumer.
 */
export type ProjectSessionSlice = ReadonlyPick<
  WorkspaceStore,
  'projectId' | 'projectSessionId'
>
