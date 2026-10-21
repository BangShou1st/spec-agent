# Main CI independent validation

This change is based only on main `74b9815d69a562d6782c4efbef1b7daf3f075905`.
It contains none of the 28 existing dev commits or dev's database migrations.

## Dependency boundary

Zen is a compile-time dependency, not merely an auto-configured runtime starter.
Production imports include `OpenCodeSettingsService`, `HttpOpenCodeZenTransport`,
`OpenCodeModelProtocol`, `OpenCodeZenMetadataCatalog`, `OpenCodeZenIds`,
`OpenCodeZenTransport`, `OpenCodeModelCatalog` and `OpenCodeZenModelMetadata`.
The eval-side `ZenUpstreamContractCheck` also imports the library.
Kilo uses a different shared starter and is not a substitute for Zen.

Deleting the Zen dependency or adding an unconditional skip property would break
these source files. Production now resolves the public JitPack v1.4.2 starter and its transitive
autoconfigure module. OPENCODE_ZEN_LOCAL remains an explicit source override
for both modules. No replacement API, stub or fake starter is used.

`backend/independent` is a separate Gradle build referencing reviewed real source
files in place. It does not load the production project or its configurations.
It verifies protocol DTOs, deterministic decision eligibility/validation and
model output contracts without database, Provider, credentials or shared Zen.
See its README for exact scope and commands. Full application startup and actual
Provider behavior remain unqualified.

## CI scope and honest status

Push/PR runs default to full deterministic validation, visible in the run title
and job summary. The following jobs also execute in independent diagnostics:

- `frontend`: typecheck, existing unit tests, production build.
- `brain`: existing Python tests and shared fixture contracts.
- `contracts-static`: deterministic corpus/replay/tools and CI scope tests.
- `backend-independent`: curated real Java compilation/tests, dependency and
  source-scope artifacts, JUnit XML and HTML report.

`backend`, `eval-baseline`, `cross-language`, and `e2e` execute by default. Each
uses a disposable CI PostgreSQL service and a run-specific isolated database;
provider inference uses the explicit fake gateway. Dependency inspection,
production compilation, and bootJar use the published Zen artifacts.

Only manual `workflow_dispatch` input `full_validation=false` selects independent
diagnostics and reports these four jobs as NOT RUN. A skipped job never counts as
passed. Existing test-level external-service skips also remain NOT RUN and must
be reviewed separately from the containing job's conclusion.

No live model experiment is added. Real provider behavior and model quality
remain NOT_MEASURED; independent success alone does not qualify the full backend.

There is no `continue-on-error`. The always-running summary fails when an
independent job fails/cancels/skips unexpectedly, when scope selection fails, or
when a full-mode job does not execute successfully. A production job accidentally
executing in independent mode also fails the summary. Summary unit tests cover
these cases. GitHub's overall independent run may be green; its declared scope
explicitly excludes the skipped integrations, which are never counted as passes.

The summary validates that the mode job emits an explicit `true` or `false`
consistent with `CI_FULL`; missing or invalid scope fails closed. Real failures,
cancellations, unexpected skips and missing results are separately reported as
`FAIL`, `CANCELLED`, `UNEXPECTED_SKIP` and `MISSING` and fail the summary.

## Reviewed historical failure and branch settings

All four failed jobs in [run 171](https://github.com/BangShou1st/spec-agent/actions/runs/39132798714)
were inspected through complete failed-job logs: `compileClasspath` could not find
`com.sharedlibs:opencode-zen-spring-boot-starter:1.2.0`. That is the coordinate that run actually
used; the library has since moved to the public JitPack publication
`com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2`, which is resolved
without credentials. The eval retrieval step and
E2E browser step were downstream skips. Frontend and brain passed in that run;
those historical results do not count as this branch's verification.

Read-only GitHub checks: main protection endpoint returned `404 Branch not
protected`, repository rulesets and active rules for main returned empty arrays.
No branch protection or required checks were changed. Teams adopting required
checks should require full deterministic validation and the summary with its
explicit scope; a skipped
GitHub job must not be interpreted as completed integration verification.

## Historical local verification (initial independent implementation)

These counts describe the initial implementation, not the current full CI run.

- `backend/gradlew.bat -p backend/independent clean test --offline`: 38 tests,
  0 failures, 0 skipped, compiling 54 real production Java files and 4 test classes.
- Independent `dependencyInsight --dependency opencode --configuration testRuntimeClasspath`:
  no matching dependency.
- `python -m pytest tools/tests -q`: 141 passed.
- Workflow YAML parsed; all `needs` references resolve; `git diff --check` passed.

Dev was repaired and evaluated first (`b487993590b8db32f240db368a4bfdccbecdbb28`).
It is technically fast-forwardable but lacks real migration/integration acceptance,
so it has not been merged into main. This CI branch is independently reviewable.
Remote execution evidence and final Git state are recorded after the real run.
