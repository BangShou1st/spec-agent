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
User-Agent: opencode/1.18.16
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
    base-url: https://opencode.ai/zen/v1
    chat-completions-path: /chat/completions
    model: resolved-from-opencode-settings
    api-key: resolved-from-opencode-settings
    user-agent: ${SPEC_AGENT_MODEL_USER_AGENT:opencode/1.18.16}
    timeout-ms: 60000
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
User-Agent: opencode/1.18.16
```

The concrete authorization header must be produced by the provider adapter, not by Runtime Kernel code.

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
