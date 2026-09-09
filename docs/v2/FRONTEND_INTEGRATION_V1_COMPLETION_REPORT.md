# Frontend Integration v1 Completion Report

> Date: 2026-09-09
> Baseline: main @ a9f3adfbcec24888baf7049a40e96be00c0e0410
> Branch: frontend-integration-v1
> Scope: Settings-based Skills and Connections management on the Capability/Skills/MCP backend

## Final scope

This version closes the management contract between the backend and a Settings UI and ships the management UI. It does not change the Graph workspace, the Agent Runtime, or CI workflows.

## F0: Connection Management API Closure

- All public Connection endpoints use product-level connectionId. The internal row UUID is never required from the API.
- Detail and list expose validated non-secret config (CUSTOM_MCP requires serverUrl) plus maskedSuffix and hasCredential. credentialRef is no longer exposed, and plaintext secrets never appear in responses.
- PATCH supports rename, config change, and credential replacement with explicit presence semantics. Config or credential change invalidates discovery, disables the connection, and returns status to CREATED.
- GET tools serves cache-backed tool metadata (name, description, bounded inputSchema, annotations) without triggering live discovery. Tools, resources, and prompts keep separate endpoints and semantics.
- Failures use the typed safe error contract: CONNECTION_NOT_FOUND (404), CONNECTION_COMMAND_REJECTED (400), VALIDATION_ERROR (400).

## F1: Frontend Foundation

- ApiClient handles 204, 205, and empty success bodies; DELETE helper and FormData upload support; typed safe ApiError preserved; fetch kept, no axios.
- Domain modules api/skills.ts, api/skillTypes.ts, api/connections.ts, api/connectionTypes.ts mirror backend DTOs.
- Pinia skillsStore and connectionsStore own their domains. workspaceStore holds no Skills or Connections state.
- Settings shell routes: /settings redirects to /settings/models, plus skills, skills/:skillId, connections, connections/:connectionId.

## F2: Product Design / visual direction

- Product Design ideate produced exactly three directions after a get-context brief playback. No direction was implemented during F2.
- Selected: Direction A, Quiet Sidebar Manager. Connections borrows master-detail organization from B without provider branding; lifecycle semantics borrow from C without a permanent heavy stepper.

## F3: Skills UI

- Installed list with text-plus-dot status, overflow actions, and inline delete confirmation.
- Staged imports kept visually distinct from installed Skills, with a review dialog showing name, description, source, file count, size, SKILL.md preview, and collapsed technical details.
- ZIP and Git HTTPS staging, install, reject, enable, disable, delete with confirmation.
- Detail page with source, version, bounded resource reads (truncation flagged), and simple version history with dates.

## F4: Connections UI

- List renders real names with endpoint lines and lifecycle status text. No hard-coded providers.
- Custom MCP creation with name, server URL, and optional secret. Secret inputs are password-type and transient.
- Detail shows safe config, masked credential, one contextual next action per state (test, connect, enable, refresh), and separate Tools, Resources, and Prompts tabs.
- Edit dialog supports rename, config change, and credential replacement; the UI reloads canonical state after update.
- Enable, disable, and confirmed delete.

## F4.6: Visual polish

- Shared BackLink component with consistent hit area, hover, and focus treatment on both detail pages.
- Capability tabs changed from heavy pills to underline tabs; Settings shell keeps the single pill switch.
- Destructive confirmations use danger styling only at the confirm step; triggers stay quiet.
- Presentation-layer copy mapping for backend enums (BUILTIN, GIT, UPLOAD_ZIP, CUSTOM_MCP) without changing backend semantics.
- Dialog close buttons unified as accessible icon buttons; dialogs close on window Escape regardless of focus.
- List hover states, shared size and date formatting, version dates on Skill detail.

## Architecture boundaries preserved

- Graph remains the source-of-truth product workspace; no graph, workspace, or route files changed.
- Skills and Connections live under Settings; top-level IA unchanged.
- Frontend performs no semantic, provider, or keyword routing. Connections is the product concept; MCP appears only as the Custom MCP connection type label.
- Tools, resources, and prompts keep distinct semantics end to end.
- Backend public identity is connectionId; frontend never handles row UUIDs or credential refs.
- Plaintext secrets stay in SecretStore and transient request bodies only.
- Config or credential change takes effect through backend canonical state, which the UI reloads after every mutation.

## Testing evidence

- Backend full (`gradlew cleanTest test`, test profile, fake gateway): 976 tests, 0 failures, 0 errors, 2 skipped (pre-existing environment-conditional skips).
- Deterministic eval harness (`gradlew evalBFast`): 82 tests, 0 failures.
- Agent brain (`pytest tests/`): 94 passed.
- Frontend unit (`vitest run`): 67 files, 498 assertions passed. One pre-existing local `requestAnimationFrame` teardown issue in the untouched `WorkspaceView.spec` may cause a non-zero local runner exit in one environment; that test passes in isolation, the relevant files are byte-identical to `main`, and the PR frontend CI job is green.
- Typecheck (`vue-tsc --noEmit`) and production build (`vite build`) green.
- Playwright full suite against a real test-profile backend: 59 of 59 pass, covering Projects, Workspace, Graph, clarification, routes, fork, reanswer, regenerate, lifecycle, contextual AI, semantic relations, pending and recovery, settings, Skills, and Connections.
- Desktop and accessibility smoke at 1366x768, 1440x900, and 1920x1080: no horizontal overflow, Settings nav usable, primary CTAs visible and unclipped, BackLink keyboard reachable and operable, dialogs close on Escape, tabs and destructive confirms behave.

## Visual review result

- External human visual review: PASS. Frontend quality is not a blocker; further refinement belongs to a future dedicated phase.
- Local image inspection was unavailable in this environment (undeclared view_image tool through the routed provider), so pixel claims were never made; state and geometry were verified through DOM and browser assertions instead.

## CI status

- PR #11 implementation head `1abfacae0ca45a338dacc7a355ab6abfbadb002c` completed successfully on GitHub Actions.
- `backend`: PASS.
- `frontend`: PASS.
- `e2e`: PASS.
- `eval-baseline`: PASS.
- No workflow files were modified in this version.
- This final documentation-only update must still be revalidated by the same PR checks before merge.

## Deferred work

- Deeper brand and visual refinement in a future dedicated phase.
- Richer iconography only if later justified.
- Semantic or hybrid Skill retrieval only if eval justifies it.
- capability.search deferred.
- Local stdio MCP deferred until a host bridge exists.
- Global Assistant deferred.

## Known non-blocking design debt

- Version history is a simple list; no diff viewer exists because the backend exposes no diff contract.
- MCP prompts are read-only metadata; the management contract exposes no prompt execution.
- The connections discovery result object is intentionally not kept in frontend state; lists reload canonical detail after each mutation.

## Final verdict

PASS. Frontend Integration v1 has passed the implementation, architecture, security, visual, and release-quality gates. PR #11 is ready to merge once the latest documentation-only head has completed the same CI checks successfully.
