# Phase 8 exit criteria

This document separates deterministic engineering proof from real OpenCode product acceptance.

## Deterministic proof

- Backend runs with Java 21 and PostgreSQL 17 in CI.
- The normal product gateway defaults to OpenCode. The deterministic Fake adapter is available only when the explicit `test` profile and `spec.agent.model.gateway=fake` are selected.
- CI runs backend tests, frontend typecheck, frontend unit tests, the production frontend build, and deterministic Playwright E2E without an OpenCode secret. The E2E job uses PostgreSQL 17, the Spring `test` profile, and the explicit Fake gateway while it starts the backend itself.
- Every OpenCode HTTP path (model catalog, credential probe, and completion) uses the transport-owned `User-Agent: opencode/1.18.16`; deterministic coverage asserts the same header is present on all three paths.
- Settings probe/save never return or log the full key; the API returns only configuration status, masked suffix, and selected model.
- Replacement generation uses the generic `DRAFT_NODE` contract, validates model output before mutation, and commits the sibling route atomically.
- Shared-node reads use explicit Focus; Active remains the runtime working route. Focus, isolate, visibility, lifecycle, reveal, and node positions are browser/runtime concerns with separate ownership.

The deterministic CI gate is not satisfied by frontend unit tests alone: `frontend` E2E must pass against the test-profile backend with no real OpenCode key.

## Real OpenCode acceptance

Acceptance must be performed through the normal product UI, not a Fake/scripted gateway:

1. Open `设置 → 模型设置`.
2. Enter the real OpenCode key, probe, select an explicitly returned `-free` model, and save.
3. Record only the selected model id, free-model count, and masked suffix. Never record the key.
4. Run a real draft → answer → next-question loop, Fork, Re-answer, `换一个问题`, and Spec generation.
5. Verify route isolation, sibling replacement semantics, Active/Focus separation, lifecycle visibility, and no-relayout reveal behavior.

## 429 hard stop

If any real OpenCode request returns HTTP 429 or the stable `MODEL_PROVIDER_RATE_LIMITED` category, stop the acceptance run immediately. Do not retry, rotate keys, switch models, or fall back to Fake. Record the safe error category only and leave the product in its current state.

## Latest local acceptance result

- 2026-08-19: the normal UI probe stopped at the settings step after the provider returned the safe `RATE_LIMITED` / `MODEL_PROVIDER_RATE_LIMITED` category. No retry, model switch, Fake fallback, or key persistence followed.
