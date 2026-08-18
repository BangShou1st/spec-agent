# Phase 5 Final Exit Criteria — Real Runtime Closure

## Goal

Close Phase 5: real runtime loop (draft / answer / patch / next node / spec),
route operations (fork / regenerate / sibling isolation), operator readiness,
and the provider boundary — all verified against the real OpenCode Zen free
model plus scripted non-live coverage. No new product features.

## Phase status

- [x] Phase 5.1 — OpenCode Zen Gateway Integration: PASS
      (gateway=fake / gateway=opencode selector, encrypted credential storage,
      dynamic free model discovery, User-Agent opencode/1.18.16).
- [x] Phase 5.2 — Prompt + Structured Output Contract: PASS
      (production prompts draft-node.v1 / interpret-answer.v1 /
      draft-answer-patch.v1 / draft-spec.v1, strict structured parser,
      runtime-owned ids and provenance).
- [x] Phase 5.3 — Real Full Loop Hardening: PASS
      (real full loop through the normal orchestrator path, spec grounding and
      source reference guards fail closed, scripts/regressions green).
- [x] Phase 5.4 — Runtime Stabilization + Operator Readiness: PASS
      (test isolation, live smoke environment preconditions, operator runbook
      `docs/PHASE_5_4_OPERATOR_RUNBOOK.md`, provider failure categories,
      trace safety).
- [x] Phase 5.4 follow-up — provider failures behind gateway contract: PASS
      (`ModelGatewayException` / `ModelGatewayErrorCategory` in
      `com.specagent.model.gateway`; agent layer no longer imports
      `com.specagent.model.provider.*`, locked by ArchUnit; trace still records
      `failed:provider:<CATEGORY>`).

## Route / regenerate / branch isolation

### Real-model live smoke (env-gated, `@Transactional`, zero public requests by default)

`OpenCodeZenRouteIsolationSmokeTest` runs the real fork isolation loop through
the normal orchestrator path while a spy captures each real `ModelRequest`:

- [x] Gateway resolved through normal `ModelGateway` wiring; selected free
      model is a current `/models` free model.
- [x] Fork from an answered node creates a second active route; the fork route
      continues with a real answer loop and real next-node draft.
- [x] Every fork-route model input excludes the sibling sentinel
      (`SIBLING_SENTINEL_DO_NOT_LEAK_7f3a`) and the superseded route's record
      ids (nodes, answers, patches, routeId); the shared root lineage is still
      present.
- [x] Spec generated on the fork route: routeId matches the fork route, every
      source ref points inside the frozen fork context
      (`context.allowedSourceRefs`), `SpecGroundingGate` and
      `SpecSourceReferenceGuard` pass; nothing points at sibling records.
- [x] Fork-route run traces stay free of the excluded sentinel; no raw prompt /
      full model input / full key is printed or persisted.

### Scripted non-live projection tests

`ScriptedRouteIsolationIntegrationTest` (deterministic fake, zero public
network) covers the envelope layer with captured `ModelRequest.inputJson`:

- [x] Fork active-route requests exclude sibling sentinel and superseded ids.
- [x] Fork-route spec source refs all resolve inside the frozen fork context
      (same allowed-set assertion as the live smoke).
- [x] Regenerate projection: the frozen regenerate context carries only the
      shared parent lineage, old question text and the user instruction;
      old answer, old patch, and the target node's child subtree are excluded;
      the projected envelope contains none of their ids or sentinel texts.
- [x] Archived sibling route content stays excluded from the active route's
      projected requests.

### Rejected-spec fail-closed (existing coverage, referenced not duplicated)

- [x] Spec source ref outside the frozen context -> run FAILED, no snapshot
      persisted, route tip/root unchanged
      (`FakeFullLoopFailureIntegrationTest`, `SpecSourceReferenceGuardTest`).
- [x] Same-project sibling route reference rejected by the source guard
      (`SpecSourceReferenceGuardTest.rejectsSameProjectSiblingRouteReference`).

### Regenerate runtime path note

`RouteService.regenerateFromNode` is deterministic (no model call): the
replacement question/purpose/options are supplied by the caller and the runtime
persists the replacement node and supercedes the old route. There is no
model-drafted regenerate orchestrator path yet, so "real-model regenerate" is
not claimed; regenerate context/projection isolation is verified non-live at
the envelope level (above).

## Full regression

- [x] `gradlew test` BUILD SUCCESSFUL, 0 failures; the only skipped tests are
      the env-gated live smokes; default run makes zero public OpenCode
      requests.

## Live smoke results

- [x] `OpenCodeZenLiveSmokeTest` PASS
- [x] `OpenCodeZenRealFullLoopSmokeTest` PASS
- [x] `OpenCodeZenRouteIsolationSmokeTest` PASS
- [x] Selected model: `mimo-v2.5-free`; free model count and masked key suffix
      reported; no full key, full prompt, or full model output in any log.

## No forbidden features

- [x] No retry/repair/fallback, no JSON repair, no relaxed parser, no
      naked-output fallback.
- [x] No provider platform / registry / router / model ranking.
- [x] No frontend / controller / credential UI / model selector UI.
- [x] No prompt framework rewrite (prompts unchanged since Phase 5.2).
- [x] No transport rewrite, no route/context semantic change, no
      SpecSnapshot source-of-truth change.
- [x] No secrets committed; no seed-only test committed.

## Known limitations / deferred items

- Regenerate is deterministic (caller-supplied replacement); a model-drafted
  regenerate orchestrator path is a future phase.
- Provider instability is observed and diagnosed only; retry/fallback policies
  are explicitly out of scope.
- No product-quality evaluation, benchmark suites, or multi-provider support.
- Raw prompt / raw model output are intentionally not persisted; debugging
  payloads would need a separate design note.

## Scope checklist

- no frontend/controller: yes
- no provider platform/registry/router: yes
- no retry/repair/fallback: yes
- no parser relaxation: yes
- no prompt framework rewrite: yes
- no transport rewrite: yes
- no route/context semantic change: yes
- no SpecSnapshot source-of-truth change: yes
- no secrets committed: yes
- standard tests zero public OpenCode requests: yes