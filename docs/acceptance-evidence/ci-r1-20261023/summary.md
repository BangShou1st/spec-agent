---
status: Historical
authority: Historical Reference Only
date: 2026-10-23
---

# CI-R1 — Remote review fixes and CI closure

Round: `CI-R1`. Baseline HEAD `f74592e9e42f65dc21b8307b2896962cc0a355bd` on `dev`.
Failing CI run: https://github.com/BangShou1st/spec-agent/actions/runs/39951411922
(failed jobs: `backend`, `e2e`, `validation-summary`).

This file records conclusions and machine-checkable counts only. Full reports,
XML dumps and traces stay local/CI-artifacts.

## 1. Root causes

| # | Symptom in CI | Real root cause | Fix |
|---|---|---|---|
| A | `e2e/provider-screenshots.spec.ts:101` waits forever for `provider-tab-openrouter` | The spec still asserted the removed built-in OpenRouter card; the whole Custom-provider flow it exercised was also replaced by the "＋" dialog. | Spec migrated to the current UI (Zen + Kilo built-ins, arbitrary Custom connections via dialog). |
| B | `GaExecutionCoordinatorIntegrationTest`, `GaTakeoverEndToEndTest` | Fixture rebound to `"OPENROUTER"`, which `ModelProvider` no longer contains. `GaExecutionStore.initialize` validates the provider **before** the state fence, so the test threw `IllegalArgumentException` and never reached the immutability assertion it exists to prove. | Fixture rebinds to `KILO` — legal, and genuinely different from the frozen `OPENCODE_ZEN`. No production change. |
| C | 20 contexts failed at `ExecutionModeAdmission:194`; 171 more failed on the poisoned context cache | `C16CrossProcessWorkerAcceptanceTest` switches the **database-wide** `runtime_execution_mode` row to `PER_TASK`, then its `@AfterEach` threw on `DELETE FROM retrieval_index_generations` (FK `retrieval_scope_grants_index_generation_fkey`, no ON DELETE action). The mode rollback sat after that statement, so the row stayed `PER_TASK` and every later single-executor context was refused. The shared `TOUCHED` list then re-threw in each subsequent test. | Delete `retrieval_scope_grants` before the generation; snapshot+clear `TOUCHED` first; move the mode rollback into `finally` so it cannot depend on cleanup succeeding. Production guard untouched. |
| D | `C16CrossProcessWorkerAcceptanceTest` ×3 `DataIntegrityViolationException` | Same missing grants delete as C (that FK is the violation). Exposed by the default retrieval engine moving to `python-rag.v1`, which writes grants the old engine never did. | Grants deleted first; same ordering already proven in `C16RetrievalAwaitGenerationEndToEndTest`. Applied to the two other teardowns that shared the omission. |
| E | `IsolatedMigrationAcceptanceTest` scenario D | The synthetic failure migration was named `V104`, colliding with the real `V104`, so Flyway refused with "more than one migration with version 104" and the test proved nothing about rollback. | Synthetic migration moved to `V110`; the scenario now applies the whole shipped chain and asserts history is unchanged **relative to its own baseline**. |
| F | Legacy OpenRouter import can be activated with zero capability proof | `LegacyModelConnectionMigrator` copied the old singleton's `validated_revision`/`validated_at`. V108 scrubs such rows once, at upgrade; the migrator is an `ApplicationRunner` that imports **after** Flyway on every start, so rows imported later were never scrubbed. Activation gate only read `validated()`. | Import never inherits validated state; new `V109` scrubs already-imported rows lacking a current-revision `STRUCTURED=VERIFIED` proof; activation gate additionally requires that proof. |

## 2. Test results (local, clean isolated database)

Isolated database: purpose-built `spec_agent_p3b_live_cir1_authoritative` on a
disposable pgvector/pg17 container. Never the development or shared test database.
Python interpreter: `agent-brain/.venv` (carries the brain package).

| Selection | Suites | Tests | Failures | Skipped |
|---|---|---|---|---|
| `test` | 398 | 2585 | 0 | 41 |
| `testDatabaseIntegration` | 151 | 980 | 0 | 41 |

Frontend: `vitest run` 987 passed / 1 skipped (of 988); `vue-tsc --noEmit` clean;
production build succeeds.

Playwright E2E (real backend, fake model gateway): **92 passed, 4 skipped, 0 failed**.

Every skip is an explicit opt-in, not a defect:

- Java — `Assumptions.assumeTrue(...)` on live/browser/paid gates
  (`SPEC_AGENT_GA_LIVE_QUALIFICATION`, `SPEC_AGENT_GA_BROWSER_TEST`,
  `SPEC_AGENT_RAG_LIVE_TEST`, `SPEC_AGENT_RUNTIME_QUALIFICATION`, …).
- Frontend unit — `it.runIf(SPEC_AGENT_GA_LIVE_REPLAY === 'true')`.
- E2E — `test.skip(!process.env.<gate>, '<reason>')` for the four
  isolated-fixture/startup specs.

No test was deleted, muted, or moved out of CI.

## 3. Environment gaps observed and how they were handled

- The first full local run used an interpreter without the brain package, so 29
  tests failed at `SharedPythonRetrievalFixture.java:57`
  ("Matching Python fixture did not become ready"). Re-run with
  `agent-brain/.venv`: all pass. Environment gap, not a product defect; CI
  installs the brain into the interpreter it exports.
- One long-lived local database accumulated state across repeated runs and
  produced 10 failures in classes that pass on a clean database. Verified by
  re-running the same three classes against a fresh database: all pass. The
  authoritative numbers above come from a single clean-database run.

## 4. Invariants preserved

- `ExecutionModeAdmission` production guard unchanged: mixed schedulers still
  fail closed. Only the test teardown was made rollback-safe.
- C16 concurrency and exactly-once side-effect assertions unchanged; only
  teardown ordering was corrected.
- No foreign key dropped, no transaction privilege widened, no case skipped.
- Frozen GA binding rules unchanged; only the fixture's provider code was
  corrected to a legal one.
- D3/D4 acceptance conclusions untouched; no live provider was called.
