---
status: Historical
authority: Historical Reference Only
---

# MS-C2 Custom Responses GA Native Tools Delivery Evidence

Date: 2026-10-24. Local deterministic runs only; no paid provider calls
(`LIVE_NOT_RUN`). Fake Responses provider coverage does not claim validation
of every real Responses-compatible vendor.

## Closed-loop path verified

`GaResponsesNativeToolEndToEndTest` (isolated PostgreSQL + Fake Responses
provider on loopback):

1. Python-shaped `ga-model-inference.v2` request parsed by the Java broker.
2. Frozen Custom RESPONSES binding resolved; first HTTP request used
   `input` + `tools` + `tool_choice=required` + `max_output_tokens` with
   `store:false`, no `previous_response_id`, no `messages` array.
3. Provider returned `function_call` with `call_id`; Java parsed arguments,
   validated catalog identity, persisted the MODEL ledger row, and returned
   only the provider-neutral ToolCall DTO to the caller.
4. The read-only `project.search` capability executed exclusively through
   `GaCapabilityBroker` (frozen catalog + argumentsHash + durable TOOL ledger);
   status SUCCEEDED.
5. The second request replayed the assistant `tool_calls` (`call_id`) and a
   `function_call_output` item with the same `call_id`; the model answered
   final text. Two provider requests total, no retry.
6. Ledger: 2 MODEL calls + 1 TOOL call, all SUCCEEDED; repeated callId
   replayed from the ledger without any additional provider traffic.

## Fail-closed coverage

- NATIVE_TOOLS without capability proof rejects tool requests before HTTP.
- Revision/credential drift rejects with `GA_MODEL_BINDING_MISMATCH`.
- Anthropic Messages routing rejects with `UNSUPPORTED_AGENT_MODEL`.
- Non-completed status, refusal item, malformed JSON, duplicate `call_id`,
  unauthorized tool name, and wrong tool choice all reject before execution.
- Chat Completions transport is never used as a Responses fallback.

## Test results (local, 2026-10-24)

- Backend `testOffline`: 437 completed / 0 failed / 0 skipped.
- Backend `testDatabaseIntegration`: 994 completed / 1 failed / 41 skipped.
  The single failure was `RetrievalV1HardeningIntegrationTest` "Matching
  Python fixture did not become ready" — a local Python subprocess startup
  timeout in an unrelated retrieval fixture (the fixture log shows uvicorn
  eventually serving requests); MS-C2 touched no retrieval code. A targeted
  rerun of that class passed 16/17 with only the same startup-timeout flake.
- MS-C2 targeted database suites (`GaResponsesNativeToolEndToEndTest`,
  `GaModelContinuationEndToEndTest`, cross-language wire replay): all passed.
- Cross-language exit gate (`testCrossLanguage`): passed.
- Python brain: 344 passed / 0 skipped.
- Contracts static suite: 239 passed / 0 skipped.
- Frontend: typecheck passed; unit 987 passed / 1 skipped; production build
  passed.

## Environment note

The local database-integration guard requires the server-reported Docker
bridge identity (`172.17.0.3/32:5432`) rather than the published host port.
The 41 database-integration skips are the suite's explicit environment gates,
not silent omissions.
