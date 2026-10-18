# Model Gateway

Status: first-version design freeze  
Date: 2026-08-17

## 1. Purpose

Model Gateway is the only boundary through which Spec Agent talks to external model providers.

The first version should not call model providers directly from controllers, application services, runtime services, context builders, route services, node services, or spec persistence code.

```text
Agent Reasoning Layer
→ ModelGateway
→ ProviderAdapter
→ HTTP client
→ external model provider
```

The gateway exists to keep model integration replaceable, observable, and constrained.

## 2. First-Version Decision

The first version uses a custom HTTP-based ModelGateway.

Do not add Spring AI as the default model integration.

Spring AI may be evaluated later as an optional adapter only after the custom gateway contract is stable. It must not become the source of truth for runtime behavior, prompt contracts, context construction, or provider configuration.

## 3. Why Not Spring AI First

The first-version model boundary needs precise control over:

- Provider base URL.
- Endpoint path.
- Model id.
- Request headers.
- `User-Agent`.
- Authorization format.
- Raw request and response capture.
- Prompt version.
- Request and response hashes.
- JSON parsing and validation.
- Safe failure behavior.

Using a thin custom HTTP adapter first makes these constraints explicit and easier to test.

## 4. Target Provider Requirement

The first known target provider is opencode zen free model access.

The provider is expected to require this request header:

```http
User-Agent: opencode/1.18.21
```

This value must be configurable and must not be hard-coded inside business logic.

The product gateway is selected by `spec.agent.model.gateway` and defaults to
`opencode`. OpenCode API keys and the selected free model are configured in the
product UI under `设置 → 模型设置`; the key is persisted in the
`opencode_settings` aggregate and is never returned to the browser or traces.
For deterministic CI/E2E only, use the explicit `test` profile with
`spec.agent.model.gateway=fake`.

Provider transport configuration shape:

```yaml
spec-agent:
  model:
    provider: opencode-zen
    base-url: https://opencode.ai/inference/v1
    chat-completions-path: /chat/completions
    model: resolved-from-opencode-settings
    api-key: resolved-from-opencode-settings
    user-agent: ${SPEC_AGENT_MODEL_USER_AGENT:opencode/1.18.21}
    settings-timeout-seconds: 45
```

Provider-specific defaults may live in configuration or adapter setup. Runtime Kernel code must not know them.

## 5. Package Boundary

Recommended packages:

```text
com.specagent.model
com.specagent.model.gateway
com.specagent.model.provider
com.specagent.model.provider.opencode
com.specagent.model.contract
```

Allowed dependencies:

```text
agent reasoning → model gateway
model gateway → provider adapter
provider adapter → HTTP client
```

Forbidden dependencies:

```text
runtime kernel → model gateway
context builder → model gateway
route service → model gateway
node service → model gateway
answer service → model gateway
patch persistence → model gateway
spec snapshot persistence → model gateway
```

## 6. Contract Boundary

The gateway should expose stable project contracts, not provider-native objects.

Example contract names:

```text
ModelRequest
ModelMessage
ModelResponse
ModelUsage
ModelProviderConfig
ModelCallTrace
```

Provider-specific JSON should be converted at the adapter boundary.

The rest of the application should not depend on opencode-specific response fields.

## 7. Required Request Metadata

Each model call should record enough metadata for debugging and replay:

- Provider name.
- Base URL identifier or sanitized endpoint label.
- Model id.
- Prompt version.
- ContextSnapshot id.
- AgentRun id.
- Request hash.
- Response hash.
- Start time.
- End time.
- Failure category if failed.

Do not store secrets in traces.

## 8. Required Headers

The provider adapter must support configurable headers.

Minimum required headers:

```http
Content-Type: application/json
Authorization: Bearer <api-key>
User-Agent: opencode/1.18.21
```

The concrete authorization header must be produced by the provider adapter, not by Runtime Kernel code.

## 8.1 Production Completion Wire Shape

Production task completions use the verified streaming shape:

- `stream=true`.
- The production completion body contains exactly `model`, `messages` and `stream`.
- No `response_format` field.
- No `temperature`, sampling, generation-limit, response-format or reasoning override fields.
- Production completion requests have no `HttpRequest` timeout and use an `HttpClient` without a
  Spec Agent connect deadline. Model-list and credential-probe requests use a separate bounded
  settings policy.
- No automatic retry, model substitution, provider substitution, or additional reasoning parameters.

Credential probes are separate bounded requests and may retain a small probe-only `max_tokens` value.

If a streamed delta contains `reasoning_content`, the adapter may retain only event/character counts and a SHA-256 hash for safe observation. Reasoning text is never persisted, logged, appended to assistant content, or passed to the runtime parser. The runtime consumes assistant `content` only.

Production failure diagnostics may include request start/elapsed/header/first-event timing,
message and body counts/hashes, request-shape presence flags, SSE counts/hashes, and safe provider
request identifiers. They never include raw prompts, answers, reasoning, request/response bodies,
or credentials. Connect and response timeout reasons remain distinct.

## 9. Structured Output Rule

Model output must never be persisted as trusted state directly.

Model output must flow through:

```text
raw provider response
→ provider adapter parse
→ ModelResponse
→ contract-specific parser
→ schema validation
→ reflection gate
→ application service persistence
```

Invalid or unsupported output must fail closed.

The gateway may support JSON repair later, but repair must not bypass validation.

## 10. Phase Rules

### Phase 1: Backend Foundation

No model gateway implementation.
No Spring AI.
No real model calls.

### Phase 2: Runtime Kernel and Persistence

No model gateway implementation.
Runtime Kernel must remain model-free.

### Phase 3: Route Control Operations

No real model calls.
Route operations must be proven deterministically.

### Phase 4: Agent Contracts with Fake Model

Implement fake model adapter only.
No external HTTP model calls.
No Spring AI.

### Phase 5: Real Model Gateway

Implement custom HTTP ModelGateway.
Add opencode-compatible provider adapter.
Support configurable `User-Agent`.
Record model traces.
Validate structured output before persistence.

## 11. Failure Behavior

Model provider failures must not partially mutate runtime state.

Failures should produce a failed AgentRun or failed model call trace with a clear category:

```text
configuration_error
provider_unavailable
timeout
invalid_response
invalid_json
schema_validation_failed
unsupported_action
rate_limited
unknown_error
```

Application services decide whether a failed operation can be retried.

## 12. Anti-Overfitting Rule

Provider adapters are infrastructure. They must not introduce requirement-domain behavior.

Forbidden examples:

```text
SoftwarePromptClient
MarketingModelAdapter
EcommerceQuestionModel
StartupPitchModelGateway
```

Allowed examples:

```text
HttpModelGateway
OpenAiCompatibleProviderAdapter
OpencodeZenProviderAdapter
StructuredModelOutputParser
ModelCallTraceRecorder
```

Provider-specific code may know about protocol details. It must not know about requirement domains.

## 13. Architecture Tests

Add tests before or during Phase 5 to ensure:

1. Runtime Kernel packages do not depend on `com.specagent.model`.
2. ContextBuilder does not depend on ModelGateway.
3. Route, Node, Answer, and Patch services do not call provider adapters.
4. No Spring AI package appears in production code unless explicitly approved later.
5. Provider adapter supports configurable `User-Agent`.
6. Invalid model output does not persist AnswerPatch, Node, Route, or SpecSnapshot state.

## 14. Summary

First-version rule:

```text
Custom HTTP ModelGateway first.
Spring AI is not the default.
Provider details stay behind adapters.
Runtime stays model-free.
Model output is validated before persistence.
```

## 15. Stage A: Lower-Level Inference Seam and Internal Broker (V2)

Stage A of the V2 migration adds a provider-neutral seam below `ModelGateway` without changing the frozen
OpenCode transport:

```text
Legacy flow:   ModelGateway -> TaskPromptCatalog -> OpenCodeZenTransport
V2 flow:       Python brain -> internal inference broker -> ModelInferenceGateway -> OpenCodeZenTransport
```

- `com.specagent.model.inference.ModelInferenceGateway` — one method,
  `complete(ModelInferenceRequest)`, carrying runtime-approved messages, a
  `runId`, and a sanitized call type; never an `AgentTaskType`.
- `OpenCodeModelInferenceGateway` — resolves credentials and free-model policy
  exactly like before and reuses the same transport. No retry, no fallback.
- `InternalModelInferenceController` (`POST /internal/v1/model-inference`) —
  authenticated by the shared internal secret
  (`SPEC_AGENT_BRAIN_INTERNAL_SECRET`, header `X-Spec-Agent-Internal-Token`),
  tied to a `runId`, bounded prompt size, closed call types, no arbitrary
  header forwarding, and sanitized AgentRun events (call type + hashes only).
- Python never receives an API key; provider keys never appear in broker
  responses or logs.

The fake gateway for this seam is test-only
(`spec.agent.model.inference=fake` with the `test` profile), mirroring the
legacy fake adapter rules.

## 16. Global Assistant native tool-call contract (migration preparation)

The LangChain migration uses a separate `ga-model-inference.v1` contract,
specified in `contracts/global-assistant/README.md`. It does not extend
`model-inference.v1`, relax Project Agent role/run validation, or change §8.1.
GA messages support assistant/tool roles and typed tool calls. Credentials and
provider conversion remain in Java. A model binding is frozen per execution.

Native GA protocol conversion is isolated from existing text adapters. Its
presence is not evidence that a provider/model is qualified. Until native
tool-call, streamed arguments and tool-result follow-up pass through an
authenticated, run-scoped Java broker, the LangChain engine must not be enabled
for product runs. Unsupported transports return `UNSUPPORTED_AGENT_MODEL`;
no text simulation, model substitution or retry is permitted.

The new GA contract authorizes a separate native tool-call payload containing
tool schemas, assistant calls and tool results. It does **not** authorize
changing the frozen OpenCode production transport; that requires a separately
recorded provider qualification and transport change. No provider SDK is added
to Python.

### 16.1 Separate GA native SSE transport

The GA transport may reuse the Java OpenCode HTTP client, proxy policy and
identity headers with an independent native payload (`stream=true`,
`stream_options.include_usage=true`). It aggregates indexed tool argument
fragments and validates the completed response before returning any executable
call. A missing terminal finish, usage or `[DONE]`, malformed arguments,
multiple parallel calls, cancellation or deadline terminates the request.
The initial adapter permits one call per model response. Zen retains its
verified reserved `bash`/`read` declarations in the provider envelope, separate
from the authorized GA catalog. Neither has a host execution route. A response
calling either reserved name is rejected before tool dispatch. Catalog names
colliding with those transport declarations are rejected. This preserves the
existing free-tier request requirements without granting shell/file access.
The existing Project completion payload and parser remain unchanged. Adding
this transport alone does not qualify a model or enable product execution.

## 17. Kilo Gateway provider (third provider)

Kilo Gateway is the third named provider preset (`ModelProvider.KILO`), alongside
OpenCode Zen and OpenRouter. It is deliberately OpenAI-compatible, so it does not
reuse the frozen OpenCode transport:

- Transport is the published shared library `kilo-gateway-spring-boot-starter`
  (`KiloGatewayClient`), resolved from JitPack
  (`com.github.BangShou1st.kilo-gateway:...:v0.2.1`). Provider-specific HTTP
  details therefore stay inside the library, behind the provider adapter.
- Fixed base URL `https://ai-gateway.kilo.ai/api/v1`, fixed Chat Completions
  protocol, and a live model catalog. Free models are anonymously usable;
  paid models require a key. The key is stored per organization in the
  `kilo_settings` aggregate (encrypted at rest) and is never returned to the
  browser or written to traces.
- The preset shape matches OpenCode Zen: not user-named, no format selector, no
  editable base URL, key optional, built-in catalog.
- The key is never sent to the free tier: free-model probes and calls are always
  anonymous (`null` credential). No fallback models are configured, so there is
  no implicit provider fallback; every failure is fail-closed.
- Routing happens only in `RoutingModelInferenceGateway`; the Runtime Kernel does
  not know this provider. `kilo.gateway.*` only supplies library defaults (model
  id, base URL, proxy, timeouts, `free-only=false`).
- Global Assistant coverage: resolved. The OpenCode-Zen-only limitation this
  bullet used to describe is removed by §18 — GA now resolves and freezes a
  provider-neutral model target and routes native tool calling per provider.
  Kilo models must be individually qualified (§18.1) before GA will use them.
- Free-model output quality varies: request handling is verified, but only some
  free models satisfy the strict DECISION contract. Live evidence:
  `poolside/laguna-s-2.1:free` produced non-conforming output (run failed
  validation); `nvidia/nemotron-3-super-120b-a12b:free` completed a real run.

## 18. Provider-aware GA execution and per-model native-tool qualification

The Global Assistant is no longer bound to OpenCode Zen. GA resolves one
provider-neutral model target and freezes it for the whole run:

```text
model settings -> GaExecutionModelTargetResolver -> ga_executions binding
               -> GaNativeInferenceGateway (provider routing) -> provider native transport
```

- `GaModelTarget(provider, model, settingsRevision, credential)` — one frozen,
  provider-agnostic target. The credential never enters the execution table,
  the wire contract or diagnostics.
- `GaExecutionModelTargetResolver` resolves the target at preparation time from
  the **active** provider and re-resolves it at inference time from the
  **frozen** binding. Switching provider or model mid-run cannot retarget a run;
  a settings-revision drift fails closed with `GA_MODEL_BINDING_MISMATCH`.
- `GaNativeInferenceGateway` routes by the frozen provider. OpenCode Zen keeps
  its verified native SSE transport; Kilo Gateway uses
  `KiloGaNativeTransport`; providers without a registered native transport
  (OpenRouter, Custom) fail closed with `UNSUPPORTED_AGENT_MODEL`.
- "Not configured" and "not qualified for native GA tool calling" are distinct:
  the former is `GA_MODEL_NOT_CONFIGURED`, the latter
  `GA_MODEL_PROTOCOL_UNSUPPORTED`. The old code used the first to hide the second.
- Qualification is per model and explicitly registered in
  `spec.global-assistant.ga-native.kilo-qualified-models`. It is never inferred
  from a `:free` suffix or from "it is a chat model".

### 18.1 Live qualification evidence (recorded 2026-10-18T09:34:48Z)

Command: `backend> gradlew.bat qualifyGaNativeKilo` (live, no retry, no model
substitution, anonymous free-tier credential).
Per model: (1) plain non-stream chat, (2) non-stream native tool call,
(3) streaming tool-call argument aggregation, (4) tool-result follow-up,
(5) tools-free SUMMARY request. Artifact:
`docs/v2/evidence/KILO_GA_NATIVE_QUALIFICATION.json`.

| Model | 1 plain | 2 tool call | 3 stream tool | 4 follow-up | 5 summary | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| `poolside/laguna-s-2.1:free` | PASS | PASS | PASS | PASS | PASS | QUALIFIED |
| `cohere/north-mini-code:free` | PASS | PASS | PASS | PASS | PASS | QUALIFIED |
| `nvidia/nemotron-3-super-120b-a12b:free` | PASS | PASS | PASS | PASS | FAIL | not qualified |
| `nvidia/nemotron-3-ultra-550b-a55b:free` | PASS | FAIL | — | — | — | not qualified |
| `xingchen-agi/xing4.0-29b-a4b:free` | PASS | FAIL | — | — | — | not qualified |
| `dots-studio/dots-3-note-preview:free` | PASS | FAIL | — | — | — | not qualified |
| `thinkingmachines/inkling-small:free` | FAIL | — | — | — | — | not qualified |
| `nvidia/nemotron-3.5-lightning:free` | FAIL | — | — | — | — | not qualified |
| `kilo-auto/free` | PASS | FAIL | — | — | — | not qualified |

Failure classification (no provider body, credentials or prompts are recorded):

- `nvidia/nemotron-3-super-120b-a12b:free` — the tools-free SUMMARY call
  streamed many empty-content frames and then a terminal frame with no content
  and no tool call; the audited decoder failed closed
  (`GA_NATIVE_STREAM_COMPLETED_RESPONSE`) rather than accepting an empty
  response. **Model behaviour**, not a code defect.
- `...-ultra-550b-a55b:free`, `xing4.0-29b-a4b:free`,
  `dots-3-note-preview:free` — the returned native tool call did not satisfy the
  strict response contract (tool identity/argument object). **Model behaviour**.
- `inkling-small:free`, `nemotron-3.5-lightning:free`, `kilo-auto/free` —
  provider-side error on the request (typed `ModelProviderException`).
  **Provider/model availability**, not host code.

Streaming aggregation is enforced, not inferred: check 3 recorded
`streamCandidateTextFragments = 0`, i.e. a tool-carrying response released no
executable text before the complete tool call was validated.
