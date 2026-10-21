# C16 WAIT V4 execution contract

V4 adds a run-owned `pendingDependencies` projection alongside the immutable graph
snapshot. V2/V3 shapes and their WAIT rejection/legacy behavior remain unchanged.
The default production prompt is unchanged. Enforced decisions use V4 when the
Host dependency port is installed; shadow decisions continue to use V2.

The projection is `pending-dependencies.v1`, frozen once per run with snapshot id,
context hash, project, route and anchor, canonical JSON and a payload digest.
It contains at most twenty explicitly registered, unexpired PENDING invocations
owned by non-failed runs on the same project/route/anchor. RUNNING is never a
dependency. Only visible capabilities with a DeferredCapabilityAdapter and current
adapter authorization can be projected. Replay preserves the original payload
while rechecking current project, invocation and source authority. No credentials
or downstream job token enter the projection.

V4 WAIT requires exactly `payload.dependencyRef`, a projected invocation UUID.
Java and Python reject absent, invented and foreign references. Subscription
revalidates durable run/snapshot/project/route/anchor and current authorization.
It locks the invocation before recording one immutable subscription per waiting
run/proposal. A dependency completed between freeze and subscribe is accepted as
READY; completion and subscription serialize on the invocation lock. Unexpired
PENDING becomes WAITING. Expired PENDING and invalid owners fail closed.

WAIT registration and run terminalization/outbox occur in the same fenced Host
transaction. WAIT is a logical parked state: the run remains COMPLETED, with its
durable subscription WAITING/READY/INVALIDATED, rather than adding a run status.
Completion/timeout changes WAITING to READY and requests both invocation-owner
and matching subscriber continuation generations in its result transaction,
without filtering out subscribers whose terminalization is not yet visible.
Duplicate completion loses the PENDING CAS; duplicate subscription cannot replace
its dependency. Existing generation checks and unique continuation child arbitrate
redelivery. Stale route/anchor or failed dependency owners invalidate subscriptions;
failed subscribers are never revived. Project deletion cascades the new rows.
Current project authorization and execution fencing remain mandatory when creating
or running a child.

Known permanent project/organization revocation or disabled-user refusals stop an
existing WAIT subscription. The dispatcher first lets the failed evaluation
transaction end, then invalidates the still-uncontinued subscription and marks
the evaluated outbox generation in a new transaction. Missing actor context,
unknown authorization scope and transient exceptions remain failures/retryable;
ordinary continuation checks retain their previous behavior.

A resumed child receives the exact subscribed completed result in its frozen V4
extension, independent of the recent-observation window. It is external evidence,
not confirmed Graph truth, and is reauthorized on projection/replay. This avoids
a wake-up that omits the fact which caused it. A pending invocation's original
owner continues to use existing automatic parking; another authorized decision
on the same route/anchor can explicitly WAIT for it. No extra autonomous model
call is introduced merely to obtain a WAIT proposal.

V102 supplies projections and subscriptions. V98–V101 remain untouched. Mixed
version operation, database migration, real transaction races, process recovery
and E2E require isolated PostgreSQL acceptance and remain NOT RUN in this change.
The first production adapter is now implemented in code; see
[retrieval readiness adapter](C16_RETRIEVAL_READINESS_ADAPTER.md). It uses the real
rebuild worker, not a simulated timer. Database and process acceptance remain
NOT RUN; implementation is not a claim of production acceptance.
