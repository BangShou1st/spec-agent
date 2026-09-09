# Frontend Integration V1 Design

> Baseline: main @ a9f3adfbcec24888baf7049a40e96be00c0e0410
> Branch: frontend-integration-v1 (single long-lived branch for F0-F5)
> Date: 2026-09-09
> Status: design baseline for implementation

## 1. Goal

Close the management contract between the Capability/Skills/MCP backend (already complete on main) and a future Settings-based management UI, without changing Graph-first workspace behavior.

Concretely this version delivers:

- F0: Connection management API closure on product-level connectionId, safe config read, explicit update lifecycle, cache-backed tools read, typed safe errors.
- F1: frontend foundation that can consume the management APIs: robust ApiClient, domain API modules and types, Pinia skills and connections stores, Settings shell routes with testable navigation.
- F2: Product Design gate with exactly three visual directions for Settings/Skills/Connections, ending in a user visual-direction selection before any final page implementation.

F3 (Skills UI), F4 (Connections UI), and F5 (full acceptance, full suites, CI, PR, squash merge) are defined here so implementation stays convergent, but they are not implemented in this pass. Execution stops at the F2 visual decision gate.

## 2. Non-goals

- No change to Capability Runtime, Skill discovery/activation semantics, Agent decision, context building, policy, validator, MCP semantic runtime, or transport policy.
- No semantic routing in the frontend: the UI never maps keywords to Skills, Connections, or MCP servers.
- No new retrieval platform: no UniversalRetriever, no embeddings, no capability.search, no stdio MCP, no Global Assistant.
- No CI workflow refactor in this pass. Files under .github/workflows stay untouched.
- No final Skills pages, final Connections pages, final design QA, full new Playwright suite, PR, or merge in this pass.
- No Graph Workspace change: Routes, Graph, Inspector, Agent State, and Spec keep frozen semantics. Skills and Connections never become Graph panels.

## 3. Architecture

```text
Browser Settings shell (Vue + Pinia + fetch ApiClient)
  |
  v
Backend management APIs (SkillController, ConnectionController)
  |
  +-- Skill domain: registry, import, versions, resources (unchanged semantics)
  +-- Connection domain: lifecycle service, repository, SecretStore (closure only)
  +-- MCP runtime: discovery service, cache repository, providers (read authority)
  |
  v
Agent/Runtime (unchanged authority for activation, visibility, policy)
```

Layering rules:

- Backend Runtime remains authority for Skill activation, Connection agent-visibility, discovery persistence, policy, and provenance.
- Frontend owns management UX and explicit user selections only. It never duplicates authorization, package validation, or trust policy.
- MCP SDK stays contained in McpClientFactory. Frontend management code never depends on MCP SDK types.
- WorkspaceStore never owns Skills or Connections. New Pinia stores own their domains.
- Backend DTOs are authority. Frontend types mirror them and never guess backend state.

## 4. Settings IA

Current /settings is a single-page model form. Target IA in this version:

```text
/settings -> redirect /settings/models
/settings/models (existing OpenCode model settings, extracted from giant page)
/settings/skills (shell + placeholder list entry in F1, full UI in F3)
/settings/skills/:skillId (shell + placeholder detail entry in F1, full UI in F3)
/settings/connections (shell + placeholder list entry in F1, full UI in F4)
/settings/connections/:connectionId (shell + placeholder detail entry in F1, full UI in F4)
```

Decisions:

- Settings becomes a low-noise personal-workspace management shell, not an enterprise admin dashboard.
- Top App navigation stays minimal (Projects, Settings). Skills and Connections do not become permanent top-level nav in this version.
- Settings shell provides consistent heading, section nav, error surface, and router outlet. Models section is the default landing after redirect.
- F1 completes structure and testable navigation only. Final visual design waits for the F2 Product Design direction.

## 5. Skill management flow (backend already complete, frontend consumes in F3)

Backend SkillController already exposes a complete management contract and is not redesigned here. The design records it so F1 types and stores align:

- GET /api/v1/skills: list installed summaries (skillId, name, description, sourceKind, versionId, enabled, createdAt).
- GET /api/v1/skills/:skillId: detail (adds sourceIdentity, updatedAt).
- GET /api/v1/skills/:skillId/versions: immutable version list.
- GET /api/v1/skills/:skillId/resources: inventory (path, kind, size, hash; never raw content).
- GET /api/v1/skills/:skillId/resources/read?path=: bounded safe text read of latest version.
- POST /api/v1/skills/imports/zip (multipart file): stage ZIP without executing scripts.
- POST /api/v1/skills/imports/git (JSON url, ref): stage Git HTTPS without hooks, submodules, or LFS auto.
- GET /api/v1/skills/imports and GET /api/v1/skills/imports/:stagedImportId: review staged imports.
- POST /api/v1/skills/imports/:stagedImportId/install: install immutable version.
- POST /api/v1/skills/imports/:stagedImportId/reject and DELETE /api/v1/skills/imports/:stagedImportId: reject or discard staged import.
- POST /api/v1/skills/:skillId/enable, POST /api/v1/skills/:skillId/disable, DELETE /api/v1/skills/:skillId: lifecycle.

Frontend F1 provides skillTypes and skills API module plus skillsStore shaped to this contract. Final Skills pages wait for F3 after the visual direction is chosen.

## 6. Connection management flow (F0 closes, F4 renders)

Product lifecycle from the user perspective:

```text
create (CREATED, disabled)
  -> test (TESTED on success, FAILED + typed error on failure)
  -> connect (CONNECTED + discovery persisted)
  -> enable (agent-visible when enabled and TESTED/CONNECTED)
  -> inspect tools/resources/prompts (cache-backed reads)
  -> refresh (invalidate + rediscover + stay CONNECTED on success)
  -> disable (planner-invisible, discovery retained)
  -> update rename/config/credential (see section 7.3)
  -> delete (removes row, credential ref, and discovery cache)
```

Connections is the product concept. MCP is protocol and advanced terminology and never becomes the ordinary surface name (no MCP Manager, MCP Tool Router, MCP Runtime Console).

Management UI in F4 supports create, detail with safe config, rename, config change, credential replacement, test, connect, refresh, enable, disable, delete, and tools/resources/prompts inspection. F0 makes every step reachable through product-level connectionId alone.
## 7. API contract closure (F0)

F0 changes management and API contract only. Capability Runtime, Skill discovery, Agent decision, context, policy, validator, and MCP semantic runtime are untouched.

### 7.1 Public Connection identity

Problem: create/list/detail expose product-level connectionId (for example conn_xxxxxxxxxxxx), but test/connect/refresh/enable/disable/delete/resources/prompts/resources-read use the internal UUID row id. A browser holding only public API responses cannot call lifecycle endpoints.

Closure:

- Every public Connection management endpoint uses product-level connectionId as the path identity:

```text
POST /api/v1/connections/:connectionId/test
POST /api/v1/connections/:connectionId/connect
POST /api/v1/connections/:connectionId/refresh
POST /api/v1/connections/:connectionId/enable
POST /api/v1/connections/:connectionId/disable
DELETE /api/v1/connections/:connectionId
GET /api/v1/connections/:connectionId/resources
GET /api/v1/connections/:connectionId/prompts
GET /api/v1/connections/:connectionId/tools
GET /api/v1/connections/:connectionId/resources/read?uri=
PATCH /api/v1/connections/:connectionId
```

- Service and repository translate connectionId to the internal UUID row id. The DB row UUID never becomes the public frontend contract.
- Unknown connectionId returns 404 with code CONNECTION_NOT_FOUND and a stable safe message.
- Integration tests prove the full lifecycle using only the connectionId returned by the create response, never SELECT id FROM connections as a call prerequisite.

### 7.2 Safe Connection config read

Problem: ConnectionDetailResponse currently exposes credentialRef, maskedSuffix, lastError, and status, but no config. Custom MCP cannot redisplay its server endpoint/config without it.

Closure:

- Detail and list responses include a safe management config object alongside the masked credential suffix.
- Config is defined as non-secret metadata. The secret travels only through the dedicated secret field on create and PATCH credential replacement, never inside config.
- Contract for V1:

```text
CUSTOM_MCP config: { serverUrl: string } (required, non-empty http/https URL)
SYSTEM_SUPPORTED config: { } (reserved extension point, no free-form secrets)
```

- Validation rejects config keys that look like secrets (token, secret, password, passwd, authorization, apikey, api_key, accesskey) with 400 VALIDATION_ERROR, so a caller cannot accidentally persist a secret inside config.
- Unknown config keys for the declared kind are rejected with 400 VALIDATION_ERROR. No silent passthrough of arbitrary sensitive objects.
- Plaintext credentials never appear in any response. The secret stays in SecretStore. Config display never reads SecretStore plaintext. Only maskedSuffix (for example ****-xyz) crosses the API boundary.
- Create stores config as validated metadata and secret via SecretStore.store. Detail returns config plus maskedSuffix, never the secret.

### 7.3 Connection update lifecycle

New endpoint:

```text
PATCH /api/v1/connections/:connectionId
Content-Type: application/json
```

Request uses explicit optional fields. Absent means keep. Present means change to the supplied value. Empty-string and null guessing is forbidden:

```json
{
  "name": "renamed connection",
  "config": { "serverUrl": "https://mcp.example.com/mcp" },
  "secret": "replacement-secret-or-omitted"
}
```

- name: when present, it must be non-blank after trim. Stored trimmed. Max 128 chars.
- config: when present, it must be the full replacement config for the existing kind (not a merge). Validated by the same rules as create.
- secret: when absent, the existing credentialRef is kept. When present and non-blank, it replaces the credential via SecretStore (old ref deleted after new store succeeds). When present but blank or null, the request is rejected with 400 VALIDATION_ERROR. There is no clear-credential-by-blank in V1.
- Kind is immutable in V1. PATCH never changes kind.

Lifecycle rules:

- Rename only (config and secret absent, or config deep-equals stored config and secret absent): preserves status and enabled flag. Still enforces enabled-name uniqueness: when the target connection is enabled, no other enabled connection may already use the requested name, otherwise 400 CONNECTION_COMMAND_REJECTED with a stable uniqueness message.
- Config or credential change (config differs by deep equality, or secret present): old discovery is no longer trusted. The service invalidates the discovery cache, sets enabled to false, transitions status to CREATED, and clears lastError to null. updatedAt advances. The user must Test, Connect, and Enable again before planner visibility returns.
- Update returns the updated ConnectionDetailResponse so the UI can render the new safe state without a second round trip.
- No provider or domain keyword branching is added for update. No GitHub, Slack, or Notion special cases.

### 7.4 Read-only tools surface

New endpoint:

```text
GET /api/v1/connections/:connectionId/tools
```

Response is a list of tool views:

```json
[
  {
    "name": "search_issues",
    "description": "Search issues by query",
    "inputSchema": { "type": "object", "properties": {} },
    "annotations": { "readOnlyHint": true }
  }
]
```

- Source of truth is the existing MCP discovery and cache authority. The endpoint serves the cached discovery when present and never triggers unconditional live network discovery on UI open.
- When no cache exists, the endpoint returns 200 with an empty list so the UI can render a not-yet-discovered state and prompt Test or Connect. It does not fabricate tools and does not create a second MCP registry.
- inputSchema is bounded metadata already normalized by the transport (bounded description chars, bounded maps). The endpoint does not expand unbounded provider metadata.
- Tools, resources, and prompts keep three distinct semantics and three distinct endpoints. They are never flattened into one assets list.
- Visibility rule: tools are readable for management regardless of enabled flag, as long as the connection exists and a cache exists. Planner visibility (enabled plus TESTED/CONNECTED) stays enforced in the capability provider, not in this read endpoint. Resource content read keeps its existing agent-visible gate because it opens a live session.

### 7.5 Connection errors

- All management failures keep the typed safe API error contract {code, message, timestamp, errors}.
- Codes used in V1: CONNECTION_NOT_FOUND (404), CONNECTION_COMMAND_REJECTED (400), VALIDATION_ERROR (400), SKILL_* unchanged, plus generic UNKNOWN_ERROR and INTERNAL_ERROR fallbacks.
- Plaintext credentials, raw SDK errors, stack traces, and provider bodies never reach the frontend. Unexpected exceptions log only the exception class name server-side and return generic INTERNAL_ERROR.
- IllegalArgumentException paths in resource and prompt providers are converted to typed ConnectionCommandException paths during F0 so management reads return 400 or 404 with stable codes instead of 500.
## 8. Frontend data boundaries

- Backend DTOs are authority. Frontend connection and skill types mirror backend field names and never infer lifecycle from local heuristics.
- Frontend never performs semantic routing. No keyword-to-Skill, keyword-to-Connection, or keyword-to-MCP-server branching in stores, components, or API modules.
- No provider or domain keyword hardcode in frontend management code. Words like github, slack, notion, database, or pdf must not appear as routing conditions. They may appear only as user-supplied data rendered verbatim.
- Workspace data and management data are isolated. Graph, routes, answers, specs, and Agent runs stay in workspace and graph stores. Skills and Connections stay in their own stores and API modules.
- Config displayed in the UI is the safe management config from the backend. The UI never reconstructs secrets from masked suffixes and never persists secrets in localStorage.

## 9. State management

- New domain API modules (only if the project convention allows new files; otherwise the same split inside the existing api folder):

```text
frontend/src/api/skillTypes.ts (Skill DTOs mirroring backend)
frontend/src/api/skills.ts (fetch wrappers for SkillController)
frontend/src/api/connectionTypes.ts (Connection DTOs mirroring backend)
frontend/src/api/connections.ts (fetch wrappers for ConnectionController)
```

- The existing giant api/types.ts is not extended with unbounded new DTOs. Domain types live in their domain files and re-export through a narrow index only when needed.
- New Pinia stores:

```text
frontend/src/stores/skillsStore.ts
frontend/src/stores/connectionsStore.ts
```

- skillsStore owns installed list and detail, staged imports, install and reject, enable, disable, delete, versions, and resource inventory and reads. connectionsStore owns list and detail, create and update, test, connect, refresh, enable, disable, delete, and tools, resources, and prompts reads.
- Stores expose explicit loading and error fields per operation family (for example listLoading, detailLoading, actionLoading) and a single safe error object derived from ApiError. They never store raw Response bodies.
- workspaceStore (currently about 75KB) is not extended with Skills or Connections state. Cross-store imports are forbidden in F1 except for shared ApiError handling.
- Form drafts (new connection name, server URL, secret input) live in components, not in stores, so navigation away discards unsent secrets by default.

## 10. Error handling

- ApiClient keeps the typed safe ApiError {code, status, message, errors}. message is always safe to render: either the backend sanitized message or the generic frontend fallback.
- F1 fixes the 2xx handling bug: 204 No Content, 205 Reset Content, and empty successful bodies resolve to undefined or void instead of INVALID_RESPONSE. Only malformed non-empty 2xx bodies become INVALID_RESPONSE.
- New client capabilities: delete(path) helper returning void on 204 and parsed JSON otherwise; requestWithFormData for multipart uploads (Skills ZIP import) that never forces application/json Content-Type so the browser can set the multipart boundary.
- Network failures become NETWORK_ERROR with the generic message. Non-contract JSON error bodies become UNKNOWN_ERROR with the generic message. Raw bodies, HTML pages, and provider payloads never surface.
- Components render productErrorMessage(code) copy, never err.message verbatim when the code is unknown. Retry actions retry the last store action; authentication or not-configured codes route back to credential setup instead of blind retry.

## 11. Security boundaries

- Credential plaintext lives only in SecretStore (AES-GCM, env master key) and in transient request bodies on the way in. It never appears in GET responses, lists, logs, traces, snapshots, descriptors, capability results, or error payloads.
- Frontend never logs secrets. Password inputs use type password and autocomplete off. Secrets are cleared from component state after successful save.
- Outbound network policy is unchanged. Custom MCP server URLs and Git import URLs pass the same shared SSRF and private-network gate. Localhost HTTP remains test-only opt-in via properties.
- Skill ZIP import keeps traversal, absolute-path, symlink, device, and bomb bounds. Git import keeps no-hooks, no-submodules, and no-LFS-auto behavior. Skill scripts are never executed.
- Unknown MCP side effects continue to default to EXTERNAL_REVERSIBLE, never NONE. The tools read endpoint does not change trust classification.

## 12. Accessibility

- Settings shell uses landmark regions (header, nav with aria-label, main) and a visible focus ring consistent with the workspace system.
- Section nav is keyboard reachable with aria-current on the active section. Detail pages provide a back link to their list.
- Status text never relies on color alone. Enabled, connected, tested, failed, and disabled states pair color with text labels.
- Form fields have associated labels and hints. Server URL uses inputmode url. Secret uses password with a show-and-hide control that does not persist the value.
- Destructive actions (delete connection, delete skill, reject import) require explicit confirmation and announce the outcome through the shared error and status surface.
- Reduced-motion preferences are respected. No auto-playing or motion-only status cues are introduced.

## 13. Testing

Backend (F0, targeted, no CI change):

- Full lifecycle integration test using only the create-response connectionId: create, detail, test, connect, enable, tools, resources, prompts, resource read, refresh, disable, delete. No SELECT id FROM connections as a call prerequisite.
- Bad server yields typed CONNECTION_COMMAND_REJECTED without secret or SDK leakage.
- Config visible and secret never visible: detail and list bodies contain serverUrl and maskedSuffix but never the plaintext secret.
- Credential or config update invalidates old discovery and removes agent visibility until revalidated: after PATCH with new secret or serverUrl, tools reads as empty, capability descriptors exclude the connection, and enable is rejected until Test and Connect succeed again.
- Rename uniqueness: second enabled connection cannot take an enabled name; rename-only preserves TESTED or CONNECTED status.
- Config update lifecycle: PATCH with new serverUrl sets status CREATED, enabled false, clears lastError, and drops the discovery cache.
- GET tools does not leak unexpected data: response contains only name, description, bounded inputSchema, and annotations; no endpoints, secrets, or SDK internals.
- Architecture guard: public Connection API never depends on row UUID as identity; frontend management never depends on MCP SDK; WorkspaceStore never owns Skills or Connections; no provider keyword routing in management code.

Frontend (F1):

- ApiClient unit tests: 204 POST success, 204 DELETE success, 205 empty success, normal JSON response, FormData does not force JSON Content-Type, network error, invalid response handling, contract and non-contract error mapping.
- Domain API module tests for skills and connections URL shapes and method usage with mocked fetch.
- Store tests for list, detail, create, update, lifecycle transitions, and error mapping without real network.
- Router tests for /settings redirect to /settings/models and reachable skills and connections shells.
- Settings navigation tests proving Models, Skills, and Connections sections link correctly and render their shell placeholders.
- Commands: npm run typecheck, npm test -- --run (or the real equivalent in package.json), npm run build. All green before commit.

## 14. Product Design stage (F2)

- F2 starts only after F0 targeted backend tests and F1 typecheck, unit, and build are green.
- Invoke Product Design on the real code and the new management contracts, with UI_SCOPE_FREEZE and the three UI closure slices as constraints.
- Sequence get-context first, then ideate, because this is a new management surface needing an explicit visual target.
- Product Design produces exactly three visual directions for Settings, Skills, and Connections. No direction is implemented during F2.
- The three options plus the recommendation are reported to the user with screenshots or descriptions. Execution stops at this gate. The single open question is the visual direction selection before F3 and F4.

## 15. CI strategy

- Do not modify .github/workflows in this version unless a mechanical breakage caused by a directory rename forces it, in which case report the cause first.
- During development use targeted tests, local regression, typecheck, build, and necessary Playwright only.
- The full gate (backend full, brain full, frontend full, Playwright full, CI full, Product Design design-qa, PR review, squash merge) runs once at F5 completion, not per slice.
- Knowingly breaking existing tests while stacking features is forbidden. Each commit keeps targeted suites green.

## 16. Acceptance

F0 acceptance:

- All public Connection endpoints accept connectionId. No frontend-reachable endpoint requires a row UUID.
- Detail exposes safe config and maskedSuffix without plaintext secrets.
- PATCH implements rename, config change, and credential replacement with the lifecycle above, covered by integration tests.
- GET tools serves cache-backed tool metadata with bounded schemas and no leakage.
- Targeted backend tests green. No CI files changed.

F1 acceptance:

- ApiClient handles 204, 205, and empty bodies; DELETE helper and FormData support covered by unit tests.
- Domain types and API modules exist with clear ownership; giant types file is not further bloated.
- skillsStore and connectionsStore own their domains; workspaceStore is untouched for management state.
- Settings shell routes exist with redirect and testable navigation; Graph Workspace behavior is unchanged.
- Frontend unit green, typecheck green, build green.

F2 acceptance:

- Product Design invoked after F0 and F1 green. Exactly three visual directions reported. No final page implemented. Execution stops pending user visual-direction selection.

F3, F4, and F5 are defined for convergence but explicitly deferred: no final Skills pages, no final Connections pages, no final design QA, no full new Playwright suite, no CI change, no PR, no merge in this pass.

## 17. Phase breakdown

- F0 Backend Management API Closure: identity unification, safe config, PATCH lifecycle, tools read, typed errors, integration tests, commit.
- F1 Frontend Foundation: ApiClient fix, domain contracts, Pinia stores, Settings shell routes, foundation tests, typecheck and build, commits.
- F2 Product Design: get-context then ideate, exactly three directions, recommendation, stop at visual decision gate.
- F3 Skills UI (deferred): implement chosen direction for Skills list, detail, staged import review, versions, resources, lifecycle actions, plus Playwright coverage.
- F4 Connections UI (deferred): implement chosen direction for Connections list, detail, create, update, lifecycle actions, tools and resources and prompts inspection, plus Playwright coverage.
- F5 Full Acceptance (deferred): backend full, brain full, frontend full, Playwright full, CI full, design-qa, PR review, squash merge.

## 18. Decisions and risks

- Single long-lived branch frontend-integration-v1 is chosen over per-slice branches to keep F0 through F5 convergent and reviewable as one squash merge. Main stays stable and untouched during development.
- Tools read is cache-only by design to avoid live network discovery on every detail open. The trade-off is an empty-tools state before first discovery, which the UI renders explicitly with a Test or Connect affordance.
- PATCH uses full-replacement config semantics instead of deep merge to keep validation simple and auditable. The UI always sends the full intended config.
- Enabled-name uniqueness stays a 400 CONNECTION_COMMAND_REJECTED in V1 to avoid changing the existing error contract. A future 409 mapping can be adopted without changing frontend logic because the code stays stable.
- Largest risk is drifting into final page implementation before the visual direction is chosen. The guard is structural: F1 placeholders carry data-test hooks but no final visual design, and execution stops at F2 by instruction.

