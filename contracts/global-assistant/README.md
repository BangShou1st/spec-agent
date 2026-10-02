# Global Assistant LangChain contracts — preparation v1

Date: 2026-10-02. Status: native model/checkpoint/capability RPC implemented and
locally integrated with the product coordinator; production cutover remains gated
on the human-reviewed retrieval evaluation, one blocked legacy temp cleanup and production acceptance.
Actual HTTPS, long-summary, browser and fresh-JVM recovery evidence is recorded in the implementation status.

These contracts are independent of Project Agent. Unknown fields/versions are
rejected. JSON Schema files are generated from the Python strict DTOs; Java
native model DTOs consume the same fixtures. No request accepts userId,
permissions, provider URL, key or headers. Internal authentication is the
existing `X-Spec-Agent-Internal-Token` header. Host identity is resolved from
persisted GA run + executionEpoch + lease, never from caller claims.

## Messages and model inference

`POST /internal/v1/global-assistant/model-inference` (mounted, authenticated):
`ga-model-inference.v1` has runId, executionEpoch, leaseId, callId,
callType (`AGENT`/`SUMMARY`), modelBindingId, messages, tools, toolChoice
(`auto`/`none`/`required`), maxOutputTokens, stream. SUMMARY has no tools,
toolChoice=none and stream=false. This endpoint returns one validated response.
`POST /model-inference/stream` requires AGENT/stream=true and returns authenticated
`application/x-ndjson` `ga-model-stream.v1`: callId, sequence, type, payload.
TEXT_DELTA contains text only; COMPLETED contains the fully validated ModelResponse
after host ledger commit; FAILED contains only GA_MODEL_STREAM_FAILED. No provider
SSE, reasoning, usage or partial arguments enter the text channel. Model stream
frames are limited to 262144 UTF-8 bytes, 2 MiB total and 8192 events.

Messages are text system/user/assistant/tool. Only assistant may have
toolCalls; only tool has toolCallId. Calls use stable id, supplier-safe name
and an object arguments map. Each assistant call must be answered exactly
once, before the next assistant/user message. Unknown, duplicate, orphan or
unfinished tool calls are rejected. The response carries content, toolCalls,
finishReason (`stop`/`tool_calls`), and nonnegative token usage; it cannot
silently discard malformed/truncated tool arguments or reasoning fields.

Budgets: maxModelCalls=6, maxToolCalls=5, maxOutputTokens=8192,
maxDurationSeconds=180; 128 messages, 12 tools, 262144 UTF-8 wire bytes.
Framework limits supplement host atomic counters, not replace them.

## Execution and capabilities

Execution envelope `ga-execution.v1`: product thread/run/message identity,
epoch/lease, engineVersion=langchain-ga.v1, modelBindingId, policyVersion,
catalogHash, historyBoundary, bounded history, UI context, structuredRefs,
approved descriptors and budget. Current messageId must not occur in history.
Catalog names match `[A-Za-z0-9_-]{1,64}`; deterministic dot→underscore mapping
rejects collisions. Catalog is generated from the existing registry, not a
new business registry. SHA-256 covers canonical JSON descriptors, including
version/schema. The host checks it again before invocation.

`ga-capability-invocation.v1` includes run/epoch/lease, stable toolCallId,
capabilityId, descriptorVersion, catalogHash, arguments and argumentsHash.
Hash is SHA-256 of sorted-key compact UTF-8 JSON. Claim/replay identity is
runId + toolCallId, bound to catalog/version/arguments hash; different
arguments under the same identity fail closed. Invocation results include
status (`SUCCEEDED`/`FAILED`/`USER_INPUT_REQUIRED`/`APPROVAL_REQUIRED`), bounded
content/sourceRefs/errorCode. APPROVAL_REQUIRED is rejected in phase 1.
User input ends the current run. ui.navigate emits existing UI_ACTION only
after host destination/resource validation; it never accepts URLs.

`POST /internal/v1/global-assistant/capabilities` is mounted and authenticated.
The host requires a matching recorded native model call, frozen catalog/hash,
current host capability admission and exact arguments. Invalid attempts consume
budget. Completed identities replay; RESERVED/UNKNOWN semantic mutations cannot
be reissued under another tool-call ID. No-new-observation repeats stop the
framework without another model request. Local writes retain stable semantic
idempotency keys through the existing CapabilityRuntime. This is not an
exactly-once guarantee for external/file effects.

## Checkpoints, ownership and events

`ga-checkpoint.v1` operations: GET, LIST (descending checkpoint ID, exclusive
before cursor, limit <=100, metadata equality filter), PUT, PUT_WRITES,
DELETE_THREAD. GET/LIST replies include checkpoint, metadata, parent config,
pending writes; PUT returns config + new version. PUT_WRITES stores
checkpointId/taskId/channel/index/taskPath, including LangGraph negative
write indices. All reads/writes are restricted to the lease-bound thread and
namespace `ga:langchain-ga.v1`; caller cannot enumerate all threads.

PUT and PUT_WRITES require expectedVersion; the host locks the execution row,
checks epoch/lease/run/cancel and applies CAS atomically. Exact duplicate
request replay returns its saved result. Old lease/cancel/terminal/deleted run
rejects every new action. Checkpoint payload is a bounded typed JSON codec
(max 1 MiB), with codec/framework/state versions and SHA-256; arbitrary
Python constructors and pickle are forbidden. Keep full parent chains and
intermediate writes; retention initially follows conversation deletion.
Saver must implement sync/async get_tuple/list/put/put_writes/delete_thread.
Optional copy/prune/run deletion fail closed until explicitly supported.

Storage design: separate execution (run FK, epoch, lease, pinned model,
engine, counters, CAS version), checkpoint (thread FK, namespace, id, parent,
versions/hash/payload) and writes tables; FK cascade on conversation deletion.
V43–V48 host migrations have been applied and verified in the test database;
production migration/cutover remains pending.
Retrieval follows the newer shared Python U0 contract in
[`../retrieval/README.md`](../retrieval/README.md): one Python RAG service,
OllamaEmbeddings qwen3-embedding:0.6b / 1024 dimensions, Java-managed existing
pgvector storage and final scope/source validation. Live embedding smoke is
recorded separately; shared RAG production cutover remains U1–U3 gated. No
parallel Java Ollama provider or second index is introduced.

`ga-execution-event.v1` includes runId/executionEpoch/eventId/sequence and a
closed typed payload. Execution HTTP sends STATUS, optional TEXT_DELTA(callId/text)
and TEXT_RESET(callId), then one COMPLETED / USER_INPUT_REQUIRED / FAILED; see
`ga-execution-event.schema.json`. A candidate text generation is retracted when
its model call resolves to a tool response. Tool arguments never become body text.
Host capability RPC emits existing product TOOL_STARTED/COMPLETED/FAILED and
verified UI_ACTION. Java incrementally commits candidate answer events under the
same run lock used for terminalization. Java validates ownership,
deduplicates UUID eventId and allocates public SSE sequence. Internal sequence
starts at 1. Reasoning/provider dumps never enter events. A disconnect does
not create a replacement executor. Resume is unavailable in phase 1;
restart terminates interrupted runs using the existing recovery behavior.

## Qualification gate

Offline framework tests prove adapter behavior, not live compatibility.
Legacy text transports cannot execute this contract. Native transport and
streaming acceptance, pinned binding, authenticated broker, durable saver,
event ownership, cancellation/steer and cross-language integration must all
pass before replacing the Java GA loop. See the implementation report for
the actual verified subset and remaining gates.
## Host persistence increment (V43–V48)

New runs remain `java-legacy.v1` until the coordinator explicitly initializes a
`langchain-ga.v1` execution before lifecycle claim. Existing runs never change
engine. Execution scope binds run/thread, model binding, provider/model and
host settings revision, epoch/lease and an absolute deadline. Java serializes
budget reservations under the existing run row lock; model calls (including
summary) share a maximum of six, tools a maximum of five. Replayed call IDs with
different payload hashes are rejected. Reserved/unknown calls are never
automatically reissued. Completion rechecks the execution fence.

Checkpoint heads are per thread and fixed namespace, with a monotonic CAS
version; checkpoints and intermediate writes share that lock/version. All new
storage cascades with existing conversation deletion. This increment does not
enable restart replay, permit arbitrary thread enumeration, or qualify the
product engine. Restart interruption remains owned by the existing lifecycle.

`POST /internal/v1/global-assistant/executions` on Python accepts the frozen
execution envelope and returns bounded NDJSON. `POST /execution-claim` on Java
accepts exactly runId/executionEpoch/leaseId/executionRequestHash; hash must match
the persisted canonical envelope. The one-shot claim returns the last completed
checkpoint boundary and prevents restarts/repeated starts of the same run.
Coordinator consumes bounded UTF-8 NDJSON incrementally, committing nonterminal
receipts and product drafts before the model finishes. The complete stream must
validate before a terminal message/event transaction. Scope/order/IDs/payloads,
final draft, recorded model/tool output and checkpoint are checked. Truncated or
forged streams retract earlier drafts and cannot publish a successful terminal.
Public history boundary and completed checkpoint move only on successful terminal
commit. Failed/interrupted chains never become a resume point. Completed duplicate
dispatches emit no new public message or event; no replacement executor is launched.

Public SSE disconnects do not cancel execution; Last-Event-ID replays persisted
envelopes without duplicate text. Execution-channel failure closes the stream and
sends one advisory scope-bound Python cancel (no retry); durable Java fencing is
authoritative. Phase 1 does not resume interrupted tools/checkpoints or replay side
effects. Real model read/write/query/navigation/question gates passed in the test
database; Skill model/service integration uses a Git fixture. Production browser,
real Git failure/cleanup windows and real long-summary acceptance remain pending.


## Additive shared retrieval and native read batches (2026-10-02)

Paired Java/Python deployment raises the catalog bound to 12 (currently 10 tools
when both explicit engines are selected; legacy selection remains 8). Existing
model/tool/time budgets remain 6/5/180s. A provider may ignore
parallel_tool_calls=false: the typed decoder aggregates up to 5 calls by index,
validates contiguous indexes, immutable call IDs, complete object arguments and
unique identities before any dispatch. Both broker and Python reject batches
containing a write or ui.navigate/user-input.request. Admitted read-only business
calls retain separate IDs/results, atomic host budgets and final source checks.
Reserved Zen bash/read remain non-executable and unauthorized.

TOOL_COMPLETED source cards are derived from verified host retrieval results,
never model prose. SOURCE metadata contains original sourceRef/version/hash and
UTF-16 position plus a bounded display excerpt. PROJECT links remain verified
UUID destinations. TOOL_FAILED is persisted also for argument/navigation/repeat
failures; terminal history projects unconfirmed calls as interrupted. History
replay restores process cards without re-executing navigation or tool effects.
