# Phase 5.4 Exit Criteria — Real Runtime Stabilization & Operator Readiness

## Goals

- Make the already-running real OpenCode runtime loop repeatable, diagnosable,
  cleanable, and immune to local environment pollution.
- No gateway redesign, no prompt framework change, no retry/repair/fallback,
  no provider platform, no frontend.

## Test isolation

- [x] No-credential tests own their credential state: they clear the OpenCode
      credential in `@BeforeEach` instead of relying on a clean local dev DB
      (`OpenCodeCredentialIntegrationTest`,
      `ExplicitOpenCodeModelGatewayWiringTest`).
- [x] Proven by injection: a residue row in `provider_credentials` was planted,
      the affected tests still passed, and the non-transactional wiring test
      removed the residue itself.
- [x] `OpenCodeCredentialIntegrationTest` stays `@Transactional`; its empty-state
      assertions are isolated per test.
- [x] No seed-only tests are committed; live smokes stay env-gated and roll back.

## Live smoke repeatability

- [x] `OpenCodeZenLiveSmokeTest` and `OpenCodeZenRealFullLoopSmokeTest` remain
      gated on `SPEC_AGENT_OPENCODE_KEY` and `@Transactional` (zero public
      requests by default).
- [x] New test-only `LiveSmokeEnvironment` prints safe diagnostics (gateway
      selector, selected model, masked key suffix) and blocks with explicit
      reasons:
      - missing `SPEC_AGENT_OPENCODE_KEY`
      - `SPEC_AGENT_MODEL_GATEWAY` not `opencode`
      - selected model not ending with `-free`
- [x] Blocked runs skip with the reason; a half-configured run can never be
      mistaken for PASS.
- [x] Live smoke does not leave credential residue in the local test/dev DB
      (`@Transactional` rollback).

## Operator runbook

- [x] `docs/PHASE_5_4_OPERATOR_RUNBOOK.md` contains: local secrets setup
      (without revealing secrets), standard regression commands, isolated task
      live smoke commands, real full-loop smoke commands, secret-commit
      confirmation, dev/test credential cleanup SQL with production warning,
      provider failure category table, AgentRun trace reading guide, common
      failures and handling.
- [x] Runbook commands were verified against the real environment (Git Bash
      smoke runs below).

## Provider failure diagnostics

- [x] Every OpenCode failure maps to a `OpenCodeModelErrorCategory`
      (unchanged): AUTHENTICATION / RATE_LIMITED / SERVER_ERROR /
      PROVIDER_REQUEST_ERROR / TIMEOUT / CONNECTION / INVALID_RESPONSE /
      EMPTY_CONTENT / INVALID_MODEL / NOT_CONFIGURED.
- [x] Transport-level category tests already existed; new
      `OpenCodeProviderFailureDiagnosticsTest` proves the runtime behavior:
      provider failure -> run FAILED, trace contains `model_called:<task>` and
      `failed:provider:<category>`, no node persisted, route tip untouched,
      exception message (and any key-like content in it) never reaches the trace.
- [x] Minimal production change (only where test-only could not fix it):
      `FakeAgentOrchestrator` now persists the model-call step before rethrowing
      an `OpenCodeModelException`, and records `failed:provider:<category>`
      instead of only the exception class name. Category only — never the
      message.

## Trace / observability

- [x] `AgentRunTraceSafetyTest` proves successful run traces contain no user
      answer payload, no `Bearer`, no `sk-` pattern; provider failure traces
      carry the safe category while secret-like exception content stays out.
- [x] Trace keeps the existing steps: `model_called:<task>`, `reflected:<gate>`,
      `persisted_<artifact>`, `completed`, `failed:<...>`; run row carries
      projectId / routeId / contextSnapshotId / produced ids.
- [x] `promptVersion` / `promptHash` / `modelOutputHash` remain gateway trace
      entries (verified by existing gateway tests); raw prompt and raw model
      output are not persisted.

## No forbidden features

- [x] No retry / JSON repair / fallback model / relaxed parser / naked-output
      fallback.
- [x] No provider platform / registry / router / ranking / cost optimizer.
- [x] No frontend / controller / credential UI / model selector UI.
- [x] No route/context/spec source-of-truth semantic changes; no transport or
      prompt framework rewrite.
- [x] No secrets committed; no seed-only test committed.

## Full regression

- [x] `gradlew test` BUILD SUCCESSFUL, 0 failures; the only skipped tests are
      the env-gated live smokes (default run makes zero public OpenCode
      requests).

## Live smoke (final verification)

- [x] `OpenCodeZenLiveSmokeTest` PASS
- [x] `OpenCodeZenRealFullLoopSmokeTest` PASS
- [x] Selected model: `mimo-v2.5-free`; free model count and masked key suffix
      reported; no full key printed anywhere.

## Not in scope (Phase 5.4)

- No retry/repair/fallback design; provider instability is observed and
  documented only.
- No provider platform, model ranking, or automatic model switch.
- No raw prompt / raw model output persistence (design note only if needed).
- No frontend/controller.
