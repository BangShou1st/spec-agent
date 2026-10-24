---
status: Historical
authority: Historical Reference Only
---

# MS-C2-R1 Responses Native Tool History Protocol Fix

Date: 2026-10-24. Deterministic local runs only; no paid provider calls
(`LIVE_NOT_RUN`). The MS-C2 summary one directory up is preserved unchanged
as the delivery history of the original wire defect.

## Defect and root cause

The first MS-C2 `GaResponsesAdapter` serialized historical assistant tool
calls using the Chat Completions shape:

```json
{"role":"assistant","content":"","tool_calls":[
  {"call_id":"call_1","name":"project_search","arguments":"{\"query\":\"spec agent\"}"}]}
```

The Responses API requires each historical call to be an independent input
item, matched by `call_id`:

```json
{"model":"responses-model","input":[
  {"role":"user","content":"search projects"},
  {"type":"function_call","call_id":"call_1","name":"project_search",
   "arguments":"{\"query\":\"spec agent\"}"},
  {"type":"function_call_output","call_id":"call_1","output":"{\"status\":\"SUCCEEDED\"}"}],
 "store":false,"tool_choice":"none","max_output_tokens":256}
```

## Pre-fix failure reproduction

`FakeResponsesProvider` first received a strict inbound validator, then the
unmodified production adapter was run: the second-round request was rejected
with HTTP 400 `protocol violation: assistant tool_calls is chat-completions
shape; use function_call items`. Failing suites at that moment (recorded in
`scratch/ms-c2-r1/pre-fix-failures.txt`):

- `ResponsesGaNativeTransportTest.functionCallRoundTripPreservesCallIdAndOrder`
- `ModelProtocolHarnessTest.responsesNativeRoundTripQualifiesOnlyAfterTwoProtocolRequests`
- `ResponsesGaNativeTransportTest.malformedIncompleteAndDuplicateCallShapesFailClosed`

The production fix (independent `function_call` items, original `call_id`
preserved, no synthetic assistant messages) turned all three green. The
strict validation was kept; no assertion was deleted.

## Strict fake inbound checks

- No top-level `messages`, no `previous_response_id`, `store` must be false.
- `input` must be a non-empty array of objects.
- Messages may only be system/user/assistant with textual content; assistant
  `tool_calls` and `tool_call_id` are rejected as Chat Completions leakage.
- `function_call` items require a unique non-blank `call_id`, valid tool name,
  and a JSON-object `arguments` string; duplicates and calls after their own
  output are rejected.
- `function_call_output` items must reference an existing call exactly once
  with textual `output`; orphans and duplicates are rejected.
- Every call must have an output before the request ends; final assistant
  text may not precede the end of tool history.
- Unknown item types (including reasoning items) are rejected.

## Coverage added

- Single-tool and two-readonly-tool closed loops: independent items, exact
  `call_id` pairing, preserved order, `store:false`, stateless full replay.
- NATIVE_TOOLS probe executes both rounds through the fixed adapter and only
  records VERIFIED after the strict fake accepts the second round and returns
  the final answer.
- Negative cases: embedded `tool_calls`, orphan/mismatched/duplicate outputs,
  missing results, final text before tool history, unsupported item types.
- GA end-to-end re-verified the frozen binding, Java-only capability
  execution (`project.search`), MODEL/TOOL ledgers, replay idempotency and
  the final text answer.

## Regression results (local, 2026-10-24)

- Backend `testOffline`: 440 completed / 0 failed / 0 skipped.
- Backend `testDatabaseIntegration` (clean isolated DB): 994 completed /
  0 failed / 0 errors / 41 skipped (explicit environment gates). The
  previous local retrieval-fixture startup timeout did not recur.
- MS-C1 continuation and cross-language wire suites: passed.
- `testCrossLanguage`: passed.
- Python brain: 344 passed / 0 skipped.
- Contracts static: 239 passed / 0 skipped.
- Frontend: typecheck passed; unit 987 passed / 1 skipped; build passed.

## Boundary statement

No database migration, no protocol-contract change to `ga-model-inference.v2`
or Python, no Responses SSE support, no reasoning-continuation support, and
no Anthropic Messages work. Real Responses-compatible providers remain
`LIVE_NOT_RUN`; unsupported shapes keep failing closed.
