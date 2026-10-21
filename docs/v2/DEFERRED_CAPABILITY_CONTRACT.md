# Deferred capability foundation and migration boundary

The original implementation below is the V2/V3 Host-side foundation.
The subsequent [C16 WAIT V4 implementation](C16_WAIT_V4.md) adds durable run-owned
dependency projections and subscriptions. Production deferred adapters and real
database/E2E qualification are still outstanding.
There is no production deferred adapter or public completion endpoint yet. V2/shadow
WAIT remains a legacy no-op; V3/enforced WAIT remains ineligible without a versioned
dependency projection. Do not enable WAIT merely because these runtime tests pass.

## Binding migration safety

V98 and V99 are immutable historical migration files. V99 has already reached
origin/dev; no tags, GitHub releases or deployment records establish whether it ran.
It overwrites every non-created/non-completed/non-failed row, including valid hashes.
The approval origin is normally completed; AWAITING_APPROVAL is a phase/event, not
an AgentRunStatus. An accepted proposal may create a distinct continuation with its
own first binding. A failed run's recovery also creates a new run, rather than
recomputing the failed run's binding. Request fingerprints are not model bindings.

LegacyBindingMigrationGuard is a Spring-discovered Flyway Callback. Immediately
before an unapplied V99, it requires a transaction and locks agent_runs against
concurrent writers. If V99 would overwrite a non-null, non-sentinel value, startup
fails closed. It neither changes the row nor edits/repairs Flyway history. A blocked
upgrade needs an explicitly reviewed, drained-worker upgrade procedure; there is
no automatic checksum repair or bypass. Running Flyway outside this application
must register the same callback; bare SQL/third-party CLI does not inherit it.

V100 adds an immutable-binding trigger: NULL may acquire its first value, and an
existing value may be verified unchanged. Every change to an existing non-null
value, including clearing it, is rejected, independent of run status. It does not
backfill rows, revive terminal runs, restore sentinel values, or infer old settings.
Already-overwritten hashes cannot be recovered without independent evidence.
Runtime binding now accepts only the six actual nonterminal status codes; unknown
historical strings are refused. Canonical digest encoding remains model-target-v1.

The callback/table-lock lifetime, Flyway auto-registration in a real deployment,
V100 SQL/trigger execution, rollback, and upgrade concurrency are NOT RUN. They
require an explicitly authorized isolated PostgreSQL instance. Local compilation
and JDBC mocks do not grant migration/deployment qualification.

## Explicit external-job registration

Only a DeferredCapabilityAdapter may return PENDING. It must implement both
invocation authorization and fresh completion authorization. It must submit a
recoverable external job using the runtime invocationKey as downstream idempotency
identity, and return PENDING only after that job has been registered. A synchronous
adapter cannot manufacture waiting by returning a status string.

Only the shared V3/enforced automatic decision path opts into deferred invocation.
Ordinary legacy, shadow, GA and proposal-acceptance callers do not opt in. Existing
synchronous adapters continue through their existing claim/execute/complete flow.
No Planner or alternate execution service is introduced.

V101 adds a persisted pending_deadline. The adapter selects a bounded 1–86400 second
timeout; model arguments do not control it. The runtime verifies result identity
before changing RUNNING to PENDING. completed_at stays NULL while pending.
PENDING replay returns the existing identity and never resubmits the external job.
RUNNING/IN_PROGRESS still denotes the old claim/crash window; it is never promoted
to a registered dependency or automatically replayed after restart.

## Completion, parking and recovery

The producer calls CapabilityRuntime.completeDeferred after registration commits,
using the exact invocation id/key/capability and a SUCCEEDED or FAILED result. The
registered adapter must verify current project/actor authority and relevant
revision/source ownership before the CAS. No model or unauthenticated HTTP caller
is allowed to report completion. Callback transport/authentication and provider
redelivery remain adapter responsibilities, not implemented by this foundation.

The repository transitions only PENDING rows whose deadline has not elapsed, using
the database wall clock rather than the start time of a potentially long authorization
transaction. The
first winner records the result; later or conflicting events return false without
replacing it. A missing/mismatched identity or revoked authorization is rejected.
False is not proof of a prior successful delivery: the adapter must distinguish
early delivery, expired jobs and already-completed records and arrange redelivery
when registration has not committed. Never acknowledge an early callback as done.

Result and wake-up commit in the same transaction through CapabilityCompletionPort.
The Host resolves the parent and project from persisted rows and always reopens
the same-project parent's continuation-check generation, even when its terminalization
is not yet visible. Filtering this enqueue by COMPLETED can lose a wake-up if the
terminalization consumer parks on the still-uncommitted PENDING result. Both writers
serialize through the outbox generation; nonterminal evaluation fails and stays
retryable until normal terminalization also requests a check. Failed parents remain
failed: terminality is enforced by the coordinator, not by suppressing enqueue.
The coordinator parks on PENDING even if another
invocation has a completed result. Once pending jobs settle, it uses the existing
single-child, stale-anchor, budget, authorization and worker-fencing paths. It
never restarts the completed origin run or reuses its model binding for a child.

The existing recovery poller scans persisted expired jobs in bounded SKIP LOCKED
batches and records DEPENDENCY_TIMEOUT as durable failed evidence. Timeout and
completion compete on one row; timeout and its outbox wake-up share a transaction.
An aborted timeout batch is retried on the next poll and does not prevent dispatch
of independently committed continuation checks during this poll.
Restart reuses stored deadlines/outbox generations, with no in-memory timer or
automatic external-job resubmission. A disabled worker does not run recovery scans.

V2/V3 snapshots expose only completed observations. PENDING is kept Host-owned;
Java and Python reject it as a completed observation. Shared completion fixtures
round-trip SUCCEEDED/FAILED in both languages. The separate V4 pending-dependency
projection, WAIT selection, fenced subscription and event-to-WAIT identity are
implemented in C16_WAIT_V4.md. They are not added to frozen V2/V3 wire formats.
The external producer and database/process qualification remain open.

## Required isolated-database acceptance (all NOT RUN)

- V97/V98/empty upgrades, including each real run status with NULL, canonical digest,
  sentinel and noncanonical historical values; valid existing fingerprints must
  block destructive V99 without changes, terminal/created values must survive.
- Completed approval origin plus accepted successor and failed-run recovery: distinct
  model binding identities; terminal origins cannot rebind or be reactivated.
- V100 same-value verification, first binding, rejection of overwrite/clear, repeated
  migration startup, DDL failure rollback/restart, CLI callback registration and
  concurrent writers. Test already-V99 targets without inventing erased digests.
- Deferred submission/registration commit gap and callback-before-commit redelivery;
  downstream idempotency, revoked actor/changed revision and source ownership.
- Concurrent duplicate/conflicting completion versus deadline, timeout scan races,
  completion before/after parent terminalization, outbox failure rollback and retry.
- Process termination/restart after each commit boundary, poller recovery, stale
  route/project, cycle budgets, one child under concurrent consumers, fresh child
  authorization and model binding. No result may revive a failed origin.
- Mixed-version deployment requires a drained-worker cutover: older binaries do
  not understand PENDING. This change does not qualify rolling mixed-version use.
