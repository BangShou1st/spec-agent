# Phase 5.3 Exit Criteria — Real Full Loop Hardening

## Goals

- Run the core multi-step runtime loop against the real OpenCode Zen free
  model through the normal orchestrator / service path — not direct gateway
  calls.
- Only do minimal hardening where the real full loop actually exposes a
  problem. No redesign of the gateway, prompt framework, or route semantics.
- Prove runtime stability and failure lifecycle, not model output quality.

## Real full loop happy path

Verified by `OpenCodeZenRealFullLoopSmokeTest` (env-gated on
`SPEC_AGENT_OPENCODE_KEY`, `@Transactional`, zero public requests by default):

- [x] Gateway selector resolves `OpenCodeZenModelGateway` under
      `SPEC_AGENT_MODEL_GATEWAY=opencode` through the normal `ModelGateway`
      bean.
- [x] Free model discovery runs against `GET /models`; the explicitly selected
      model (`SPEC_AGENT_OPENCODE_MODEL`, observed `mimo-v2.5-free`) is a
      current free model.
- [x] First real `DRAFT_NODE` creates a persisted root node with non-blank
      question, runtime-owned option ids, and correct route root/tip.
- [x] Real answer flow persists the immutable answer.
- [x] Real `INTERPRET_ANSWER` output parses through the strict structured
      parser.
- [x] Real `DRAFT_ANSWER_PATCH` output parses; the accepted patch persists
      with real answered node + answer ids as provenance for confirmed claims.
- [x] Post-answer real `DRAFT_NODE` creates the next child node; route tip
      advances and the route stays OPEN.
- [x] A second answer cycle runs the same loop again (two full answer cycles
      in one smoke).
- [x] Real `DRAFT_SPEC` creates a persisted spec snapshot: non-empty sections,
      valid unresolved items, non-empty source references.
- [x] `SpecGroundingGate` passes before persistence (runtime-enforced).
- [x] `SpecSourceReferenceGuard` is re-validated over the persisted snapshot;
      every source reference points inside the frozen context (context
      snapshot, route, or included node/answer/patch ids).
- [x] Route remains OPEN with coherent root and tip after the whole loop.
- [x] Trace is diagnosable: `model_called:DRAFT_NODE`,
      `model_called:INTERPRET_ANSWER`, `model_called:DRAFT_ANSWER_PATCH`,
      `model_called:DRAFT_SPEC`, `reflected:NODE`, `reflected:PATCH`,
      `reflected:SPEC_GROUNDING`, `persisted_*`, `completed`.
- [x] No assertions on concrete model wording; low-quality-but-valid output
      does not fail the smoke.

## Failure lifecycle

Covered by existing non-live tests plus the new
`ScriptedModelGatewayFullLoopIntegrationTest` (scripted model-facing
`{action, output}`, zero public network):

- [x] Wrong action from the model -> run FAILED, nothing persisted
      (`FakeAgentOrchestratorFailureIntegrationTest`).
- [x] Invalid structured output -> fail closed before reflection, no artifact
      persisted, answer stays immutable (`FakeFullLoopFailureIntegrationTest`,
      `FakeAnswerRepairIntegrationTest`).
- [x] Reflection rejection (patch) -> no patch persisted, answer immutable,
      route tip unchanged.
- [x] Spec grounding rejection -> no spec snapshot persisted.
- [x] Source reference outside allowed context -> no spec snapshot persisted.
- [x] Provider failure after answer persisted but before patch -> run FAILED,
      answer remains immutable, no patch, route tip unchanged (new).
- [x] Provider failure after patch persisted but before next node -> run
      FAILED, accepted patch not rolled back, answer remains, rejected node not
      persisted, route tip unchanged (new).
- [x] Trace on failure retains the cumulative steps plus the `failed:` step.
- [x] No rejected model-derived artifact is ever persisted; answer immutability
      is never broken for transaction "all-or-nothing" purity.

## Retry / repair / fallback

- [x] No retry loop, no JSON repair, no second model repair call, no fallback
      model, no relaxed parser, no bare-output fallback. Invalid output still
      fails the run closed.
- [x] Repair of an already-persisted answer continues to exist only as the
      explicit `repairAnswerProcessingAndDraftNext` runtime path (unchanged
      from Phase 5.2).

## Prompt changes

- [ ] None required: the real full loop passed end to end with the existing
      Phase 5.2 prompts (`draft-node.v1`, `interpret-answer.v1`,
      `draft-answer-patch.v1`, `draft-spec.v1`). No prompt wording change was
      needed.

## Live smoke results

- [x] `OpenCodeZenRealFullLoopSmokeTest` BUILD SUCCESSFUL, 1 test, 0 failures,
      0 skipped, ~60s.
- [x] Live observations: credential masked suffix only, free model discovery
      count, selected model id. No API key, no full prompt, no full model
      output in any log or report.
- [x] Live smoke is `@Transactional` and rolls back: no credential, project,
      or artifact rows remain in the development database afterwards.

## Full regression

- [x] `gradlew test` BUILD SUCCESSFUL, 0 failures; the only skipped tests are
      the env-gated live smokes (default run makes zero public OpenCode
      requests).

## Scope checklist

- [x] Real full loop uses the normal orchestrator/runtime path
      (`FakeAgentOrchestrator` + services), not direct gateway calls.
- [x] Gateway selector verified: `opencode -> OpenCodeZenModelGateway`.
- [x] Model returns `{action, output}`; structured parser stays strict.
- [x] Reflection gates and spec source guard stay enforced.
- [x] No naked-output fallback, no JSON repair loop, no retry/fallback/model
      switch.
- [x] No provider platform, no frontend/controller, no route/context
      semantic changes, no SpecSnapshot source-of-truth change.
- [x] No secrets committed or logged; no new files contain credentials.
- [x] Local dev database credential residue (left by an earlier manual live
      smoke) was cleaned as environment data; it was not a code defect.

## Not in scope (Phase 5.3)

- No multi-provider fallback, model ranking, automatic model switch, or
  provider router (observe only; report if instability matters).
- No bounded repair design beyond observing; none was needed this phase.
- No product-quality evaluation (no quality scores, rubrics, judge models,
  A/B testing, benchmark suites).
- No frontend/controller/credential UI/model selector UI.
