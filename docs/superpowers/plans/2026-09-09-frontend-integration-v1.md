# Frontend Integration V1 Implementation Plan

> Date: 2026-09-09
> Branch: frontend-integration-v1 (only branch for the whole version)
> Design: docs/superpowers/specs/2026-09-09-frontend-integration-v1-design.md
> Baseline: main @ a9f3adfbcec24888baf7049a40e96be00c0e0410

## How to work

- Stay on frontend-integration-v1 for every task. Do not create slice, fix, or audit branches.
- Keep main untouched. Verify with git rev-parse HEAD against main before and after each push.
- Work TDD: failing test first, minimal implementation, test passes, then commit.
- Keep commits small and reviewable. Final PR uses squash merge, so history can stay granular.
- Never modify .github/workflows in this version. Use targeted tests plus typecheck and build during development.
- Stop after F2. Do not implement final Skills pages, final Connections pages, full Playwright, CI changes, PR, or merge.

## Task 0 - Preflight and branch (done)

- Verify git status clean, current branch main, HEAD equals main and origin/main at a9f3adf, and recent log matches the Capability backend merge.
- Create frontend-integration-v1 from main. Confirm branch with git branch --show-current.
- Read AGENT.md, UI_SCOPE_FREEZE, UI_UX_AUDIT, the three UI closure specs, CAPABILITY_RUNTIME, the Skills MCP plan, and the backend completion report. Inspect frontend/src, frontend/e2e, SkillController, ConnectionController, ConnectionLifecycleService, and MCP discovery and runtime read surfaces against current code, not historical notes.
- Acceptance: branch exists, preflight recorded, canonical context read.

## Task 1 - Design spec

- Write docs/superpowers/specs/2026-09-09-frontend-integration-v1-design.md covering Goal, Non-goals, Architecture, Settings IA, Skill flow, Connection flow, API closure, data boundaries, state, errors, security, accessibility, testing, Product Design stage, CI strategy, Acceptance, F0-F5, decisions and risks.
- No TBD or TODO. No vague later implement. F3-F5 are defined but marked deferred.
- Acceptance: spec exists on the branch and every section from the task brief is addressed.

## Task 2 - Implementation plan (this file)

- Write this plan with independently testable tasks on the single branch and TDD discipline.
- Acceptance: plan exists and maps cleanly to F0, F1, and the F2 gate.

## Task 3 - F0 test-first: connectionId-only lifecycle

- Extend ConnectionControllerIntegrationTest with a connectionId-only lifecycle test: create, then use only the returned connectionId for detail, test, connect, enable, tools, resources, prompts, resource read, refresh, disable, delete. Assert no SELECT id FROM connections prerequisite.
- Keep the existing secret-hiding assertions and add coverage for the new tools endpoint and safe config presence.
- Run the new test to prove it fails on current UUID-based routes (404 or 400), then implement.
- Acceptance: failing test reproduces the identity gap before the fix.

## Task 4 - F0 implement: unify public identity on connectionId

- Change ConnectionController lifecycle and read endpoints from UUID connectionRowId path variables to String connectionId.
- Resolve connectionId to the internal row inside the controller or lifecycle service. Return 404 CONNECTION_NOT_FOUND for unknown ids.
- Update ConnectionLifecycleService with connectionId-based methods (or a single resolve step) that delegate to existing UUID logic for status, credential, and discovery handling. No Runtime semantic change.
- Update McpResourceProvider and McpPromptAssetProvider read paths to resolve by connectionId or accept an already-resolved Connection, converting IllegalArgumentException paths to ConnectionCommandException with stable codes.
- Update existing tests that used row UUIDs to use connectionId. Keep one regression assertion that the internal UUID is never required from the API.
- Acceptance: Task 3 lifecycle test passes using only connectionId.

## Task 5 - F0 test-first: safe config and secrets

- Add assertions that detail and list expose serverUrl config for CUSTOM_MCP, expose maskedSuffix, and never contain the plaintext secret in any body.
- Add validation tests: config with secret-like keys is rejected with VALIDATION_ERROR; unknown kind keys are rejected; SYSTEM_SUPPORTED accepts empty config.
- Run to prove config is currently absent and secret-like keys are not rejected.
- Acceptance: failing tests pin the config and secret boundary.

## Task 6 - F0 implement: safe config contract

- Add validated safe config to create and detail responses. CUSTOM_MCP requires non-empty serverUrl. SYSTEM_SUPPORTED accepts empty config and reserves extension without free-form secrets.
- Reject secret-like config keys and unknown keys with validation errors. Never dump arbitrary config objects without validation.
- Keep SecretStore as the only plaintext holder. Detail returns config plus maskedSuffix, never the secret.
- Acceptance: Task 5 tests pass and no response contains plaintext secrets.

## Task 7 - F0 test-first: PATCH update lifecycle

- Add tests for rename only (preserves TESTED or CONNECTED and enabled, enforces enabled-name uniqueness), config change (invalidates cache, sets CREATED and disabled, clears lastError), credential replacement (same invalidation), blank secret rejection, kind immutability, and unknown connectionId 404.
- Add a test that after config or credential change the connection disappears from planner-visible descriptors until Test, Connect, and Enable succeed again.
- Run to prove PATCH is currently 404 or 405.
- Acceptance: failing tests define the full update contract.
## Task 8 - F0 implement: PATCH endpoint and invalidation

- Add PATCH /api/v1/connections/:connectionId with explicit optional fields name, config, secret. Absent means keep. Present means change. Blank secret is rejected. Kind cannot change.
- Implement rename-only fast path with enabled-name uniqueness check and no discovery invalidation when config deep-equals stored config and secret is absent.
- Implement config or credential path: replace config and/or credential (delete old credentialRef after new store succeeds), invalidate discovery cache, set enabled false, status CREATED, lastError null, updatedAt now.
- Return the updated detail response. Map validation failures to VALIDATION_ERROR and lifecycle conflicts to CONNECTION_COMMAND_REJECTED.
- Acceptance: Task 7 tests pass, including revalidation flow and uniqueness behavior.

## Task 9 - F0 test-first: cache-backed tools read

- Add tests that GET tools returns name, description, bounded inputSchema, and annotations for a connected fake MCP server, returns empty list when no cache exists, never triggers live discovery on read, and never leaks endpoints, secrets, or SDK internals.
- Add a test that tools, resources, and prompts keep distinct shapes and are not flattened.
- Run to prove the endpoint is currently missing.
- Acceptance: failing tests pin the tools contract.

## Task 10 - F0 implement: tools endpoint plus error hardening

- Add GET /api/v1/connections/:connectionId/tools served from the discovery cache only. Return empty list when no cache exists. Never call live discovery from this path.
- Add a cache-only read to McpDiscoveryService or read the cache repository directly with JSON parsing and bounding already applied by the transport.
- Convert remaining IllegalArgumentException management paths to ConnectionCommandException so unknown ids yield CONNECTION_NOT_FOUND and not-visible or bad-uri reads yield CONNECTION_COMMAND_REJECTED with safe messages.
- Verify bad-server test still yields CONNECTION_COMMAND_REJECTED without SDK or provider body leakage.
- Acceptance: Task 9 tests pass. Full Task 3 lifecycle test still passes.

## Task 11 - F0 commit and targeted verification

- Run targeted backend tests for connection, MCP, and API boundary suites. Keep the run scoped; do not run the full gate yet.
- Run git diff --check and confirm no workflow files changed and no Runtime semantic files changed beyond the management closure.
- Commit on frontend-integration-v1, for example feat(connection-api): close frontend management contract.
- Acceptance: targeted backend tests green, diff clean, commit recorded.

## Task 12 - F1 test-first: ApiClient robustness

- Extend frontend/src/api/__tests__/client.spec.ts with 204 POST success, 204 DELETE success, 205 empty success, normal JSON response, FormData does not force JSON Content-Type, network error, invalid response, contract error, and non-contract body cases.
- Run to prove 204, 205, empty-body, DELETE helper, and FormData currently fail or are missing.
- Acceptance: failing tests define the client fix.

## Task 13 - F1 implement: ApiClient fix

- Fix ApiClient request handling: 204, 205, and empty bodies resolve without INVALID_RESPONSE. Add delete helper. Add FormData-aware request support that omits the JSON Content-Type header.
- Keep fetch, typed safe ApiError, and no raw body leakage. Do not introduce axios.
- Acceptance: extended client tests pass without changing existing passing cases.

## Task 14 - F1 implement: domain contracts and API modules

- Add frontend/src/api/skillTypes.ts, skills.ts, connectionTypes.ts, and connections.ts mirroring backend DTOs. Include Skill summaries, details, versions, resources, staged imports, install results, Connection list and detail with safe config, create and PATCH update payloads, discovery summaries, tool views, resource and prompt views.
- Cover URL shapes and methods with API module tests using mocked fetch. Assert no provider keyword routing exists in these modules.
- Acceptance: domain modules tested, giant types file not further bloated, backend DTOs remain authority.

## Task 15 - F1 implement: Pinia stores

- Add frontend/src/stores/skillsStore.ts owning installed list and detail, staged imports, install and reject, enable, disable, delete, versions, resources inventory and reads.
- Add frontend/src/stores/connectionsStore.ts owning list and detail, create and update, test, connect, refresh, enable, disable, delete, tools, resources, and prompts reads.
- Expose explicit loading and safe error fields per operation family. Never store raw bodies or secrets. Never import workspace stores.
- Add store tests with mocked API modules covering success, typed error mapping, and loading transitions.
- Add an architecture assertion that workspaceStore does not own Skills or Connections state.
- Acceptance: store tests pass and management state is isolated.

## Task 16 - F1 implement: Settings shell routes

- Extract the existing model form from SettingsView into the Models section. Add a Settings shell with section nav and router outlet.
- Add routes /settings redirect to /settings/models, /settings/models, /settings/skills, /settings/skills/:skillId, /settings/connections, /settings/connections/:connectionId. Skills and Connections routes render minimal shell placeholders with data-test hooks in F1, not final visual design.
- Preserve top nav simplicity. Do not touch Graph Workspace routes or behavior.
- Add router tests and settings navigation tests proving redirect, section links, and placeholder rendering.
- Run npm run typecheck, npm test -- --run, and npm run build (or the real equivalents) to green.
- Acceptance: navigation tests pass, typecheck green, unit green, build green, workspace behavior unchanged.

## Task 17 - F1 commit and verification

- Run git diff --check, confirm no workflow changes, confirm workspace behavior files unchanged except for the shell extraction.
- Commit F1 on frontend-integration-v1 in small reviewable commits.
- Acceptance: frontend unit green, typecheck green, build green, commits recorded.

## Task 18 - F2 Product Design gate (stop here)

- Confirm F0 targeted backend tests green plus F1 unit, typecheck, and build green before invoking design.
- Invoke Product Design with get-context then ideate on the real Settings shell, management contracts, UI_SCOPE_FREEZE, and the three UI closure slices.
- Collect exactly three visual directions. Do not implement any of them.
- Report option A, option B, option C, and the recommendation with screenshots or descriptions. Wait for user visual-direction selection.
- Verify git diff --check, git status, git log, branch equals frontend-integration-v1, main untouched at a9f3adf, working tree clean except for explicitly managed visual artifacts, Graph behavior unchanged.
- Acceptance: final report carries Verdict PASS WITH CONDITIONS with the condition being selection of one Product Design direction before F3 and F4.

## Deferred (defined, not executed)

- F3 Skills UI: chosen-direction list, detail, staged import review, versions, resources, lifecycle actions, Playwright coverage.
- F4 Connections UI: chosen-direction list, detail, create, update, lifecycle actions, tools and resources and prompts inspection, Playwright coverage.
- F5 Full Acceptance: backend full, brain full, frontend full, Playwright full, CI full, Product Design design-qa, PR review, squash merge.

