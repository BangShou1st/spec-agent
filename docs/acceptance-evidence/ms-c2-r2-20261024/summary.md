---
status: Historical
authority: Historical Reference Only
---

# MS-C2-R2 Mixed Responses Output Early Rejection

Date: 2026-10-24. Deterministic local runs only, no paid provider calls
(`LIVE_NOT_RUN`). The MS-C2 and MS-C2-R1 summaries are preserved unchanged.

## Defect

`GaResponsesAdapter.parse()` collected `message.output_text` and
`function_call` items independently. A completed response containing both
produced a provider-neutral response with non-empty content AND executable
tool calls. The defect surfaced only on the next model request (history
construction rejects text-carrying tool-call history), i.e. after the MODEL
ledger could be committed and tools could run.

## Fix

`parse()` now rejects any completed output that combines non-empty visible
text with one or more function calls, regardless of item order, before a
`GaModelContract.Response` is constructed. The guard message is a fixed
classification string and never echoes provider text, arguments, or
credentials.

## Pre-fix failure reproduction

`FakeResponsesProvider` gained `mixedTextAndToolOutput` /
`mixedToolBeforeText` modes. Before the production change,
`ResponsesGaNativeTransportTest.mixedTextAndFunctionCallOutputIsRejectedBeforeReleaseRegardlessOfOrder`
failed with "Expecting code to raise a throwable" (evidence:
`scratch/ms-c2-r1/pre-fix-r2-failures.txt`): both orders were accepted and
executable calls were released. After the guard, the same test passes for
message-first and call-first ordering.

## Safety proof (isolated PostgreSQL + fake provider + production broker)

`GaResponsesNativeToolEndToEndTest.mixedTextAndToolOutputIsRejectedBeforeAnyToolExecutionOrLedgerSuccess`:

- Broker rejects the mixed response before returning any executable ToolCall.
- Exactly one provider HTTP request occurs (no second model round).
- MODEL ledger: one row, status NOT SUCCEEDED (UNKNOWN per existing
  no-retry failure semantics; no manual ledger cleanup in the test).
- TOOL ledger: zero rows; no capability invocation can occur.

Normal loops re-verified after the guard: plain text, single tool
(function_call → Java CapabilityRuntime → function_call_output → final),
two read-only tools with exact call_id pairing, and the two-round
NATIVE_TOOLS probe. All MS-C2-R1 negative cases (legacy chat-completions
history, orphan/mismatched/duplicate outputs, invalid arguments, refusal,
non-completed status, unsupported items, unqualified native tools, frozen
binding drift) still fail closed.

## Regression results (local, 2026-10-24)

- Backend `testOffline`: 441 completed / 0 failed / 0 skipped.
- Backend `testDatabaseIntegration` (clean isolated DB): 995 completed /
  0 failed / 0 errors / 41 skipped (explicit environment gates).
- `testCrossLanguage`: passed.
- Python brain: 344 passed / 0 skipped.
- Contracts static: 239 passed / 0 skipped.
- Frontend: typecheck passed; unit 987 passed / 1 skipped; build passed.

## Boundary statement

No database migration, no change to `ga-model-inference.v2`, Python,
checkpoint, continuation sidecar, ledger idempotency, capability runtime,
approvals, or frozen binding rules. Mixed text+tool Responses output remains
an explicitly unsupported shape until a future design decides how to replay
it faithfully; real providers remain `LIVE_NOT_RUN`.
