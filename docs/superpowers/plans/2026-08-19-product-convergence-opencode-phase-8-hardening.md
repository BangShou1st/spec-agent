# Product Convergence, OpenCode Productization, and Phase 8 Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Productize the existing real OpenCode gateway, replace deterministic question replacement with a generic real-model drafting flow, close the Phase 7 Graph Workspace acceptance gaps, and finish Phase 8 CI/architecture hardening without weakening runtime invariants or introducing domain/test overfitting.

**Architecture:** Keep the existing modular-monolith boundaries. Runtime Kernel remains deterministic and model-free; AgentOrchestrator freezes lineage context, asks ModelGateway for structured proposals, validates them, and only then calls runtime services to persist accepted canonical state. OpenCode settings are one global local-product aggregate in PostgreSQL, while Fake/scripted gateways remain test infrastructure only.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, Flyway, Jackson, Vue 3, TypeScript, Pinia, Vue Flow, Vitest, Playwright, Gradle, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-19-product-convergence-opencode-phase-8-hardening-design.md`

## Global Constraints

- `Model proposes. Runtime validates. Runtime persists. Runtime owns history.`
- `Context is lineage, not global chat history.`
- Production product execution uses real OpenCode; there is no silent Fake fallback.
- Fake/scripted gateways are permitted only for deterministic safety/testing paths.
- OpenCode settings are global, not per Project.
- The user enters their own OpenCode API key in the frontend and explicitly selects a currently available `-free` model.
- No `SPEC_AGENT_CREDENTIAL_MASTER_KEY` or second application-level master-key product mechanism.
- Never return, log, trace, prompt, or commit a complete OpenCode API key.
- No automatic retry, JSON repair, parser relaxation, model switching, provider fallback, or Fake fallback.
- A real OpenCode `429 / RATE_LIMITED` during Luna acceptance is a hard stop: report the step/model/state and wait for the user to change network segment.
- Do not add domain-specific runtime branches, provider adapters, generators, or prompts.
- Do not add test-text-specific behavior or prompt wording to make a particular fixture pass.
- `换一个问题` reuses the generic DRAFT_NODE/NodeDraft capability; its difference is frozen replacement context + explicit run-local drafting intent.
- Replacement proposal must be validated before canonical replacement Route/Node creation or source-route lifecycle mutation.
- Active, Focus, and Visibility remain independent concepts.
- Focus never implicitly Activates a route.
- Shared-node ambiguous reading never silently falls back to Active, first, or latest route.
- Fork/Re-answer/换一个问题 still send an explicit `sourceRouteId`; the UI derives it from operated visual-node/read context instead of opening a second route picker.
- OPEN and SUPERSEDED may be explicit exploration sources; ARCHIVED must be restored first; DELETED is never a mutation source.
- Branch creation, activation, Focus, visibility changes, and reveal must not move existing node coordinates. Only explicit `自动布局` may relayout.
- Normal UI is Chinese and friendly-name-first; full UUIDs are technical-detail-only.
- Do not edit already-applied Flyway migrations V1-V4. Add forward migrations only.
- Keep changes on the repository's main-only development policy unless the user changes that policy.

---

## File / Boundary Map Before Implementation

The implementation should keep responsibilities separated as follows.

### Backend model settings

Create a focused package such as `com.specagent.settings.opencode`:

- `OpenCodeSettings` — immutable global settings aggregate (`apiKey`, masked suffix, selected model, timestamps).
- `OpenCodeSettingsRepository` — persistence boundary for the singleton aggregate.
- `JdbcOpenCodeSettingsRepository` — PostgreSQL implementation following existing repository/JDBC patterns.
- `OpenCodeSettingsService` — probe, save, status, and runtime resolve; owns the "two-step validation, one atomic save" behavior.
- `OpenCodeSettingsController` + API DTOs — safe user-facing settings surface.

Provider protocol stays in existing `com.specagent.model.provider` classes. Runtime route/node/context packages must not depend on settings or model packages.

### Backend agent/runtime

- Rename `backend/src/main/java/com/specagent/agent/FakeAgentOrchestrator.java` to production-neutral `AgentOrchestrator.java` and neutralize result type names used by production services.
- `AgentOrchestrator` owns model-driven replacement orchestration.
- `ContextBuilder` owns replacement ContextSnapshot construction from explicit source lineage only.
- `ModelContextProjectionBuilder` owns run-local DRAFT_NODE drafting-intent projection.
- `RouteService` owns deterministic canonical replacement commit and lifecycle/source eligibility.
- `RouteCommandService` remains API command composition and must not duplicate runtime semantics.

### Frontend

- `frontend/src/api/modelSettings.ts` — all OpenCode settings HTTP calls.
- `frontend/src/stores/modelSettingsStore.ts` — global settings UI state.
- `frontend/src/views/SettingsView.vue` or a focused app-level settings surface routed globally.
- Existing graph/workspace files keep graph semantics: `graphUiStore.ts`, `WorkspaceView.vue`, `RouteNavigator.vue`, `WorkspaceInspector.vue`, `GraphCanvas.vue`, `GraphQuestionNode.vue`, graph projection/viewport helpers.
- Replace `RegenerateNodeDialog.vue` with a narrow `ReplaceQuestionDialog.vue` (or equivalently refactor the existing component so it only accepts the natural-language direction). Do not leave the deterministic replacement-authoring form reachable in normal product UI.

### CI / hardening

- Create `.github/workflows/ci.yml`.
- Extend existing architecture tests rather than creating a second architecture-test framework.
- CI never needs a real OpenCode secret; real-provider acceptance remains an explicit local/release gate.

---

### Task 1: Replace operator-only encrypted credential persistence with the product OpenCode settings aggregate

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__opencode_settings.sql`
- Create: `backend/src/main/java/com/specagent/settings/opencode/OpenCodeSettings.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/OpenCodeSettingsRepository.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/JdbcOpenCodeSettingsRepository.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/OpenCodeSettingsService.java`
- Create tests: `backend/src/test/java/com/specagent/settings/opencode/OpenCodeSettingsIntegrationTest.java`
- Remove production use of: `backend/src/main/java/com/specagent/credential/CredentialCrypto.java`, `OpenCodeCredentialService.java`, and old encrypted-credential persistence classes once no caller remains.
- Update/remove their obsolete tests.

**Interfaces:**
- Produces: `OpenCodeSettingsService.status(): OpenCodeSettingsStatus`
- Produces: `OpenCodeSettingsService.probe(String apiKey): List<String>`
- Produces: `OpenCodeSettingsService.save(String apiKey, String selectedModel): OpenCodeSettingsStatus`
- Produces: `OpenCodeSettingsService.requireRuntimeSettings(): RuntimeOpenCodeSettings`
- `RuntimeOpenCodeSettings` exposes full key only inside backend code: `apiKey()` and `selectedModel()`.

- [ ] **Step 1: Write the migration/integration tests before the migration**

Add tests proving a clean database has no settings; saving stores one global row; a later save replaces key+model together; status exposes only mask/model; no encrypted/master-key column remains part of the active product aggregate.

Representative assertions:

```java
assertThat(service.status().configured()).isFalse();

service.save(validKey, "alpha-free");
OpenCodeSettingsStatus status = service.status();
assertThat(status.configured()).isTrue();
assertThat(status.maskedKey()).isEqualTo("••••" + validKey.substring(validKey.length() - 4));
assertThat(status.selectedModel()).isEqualTo("alpha-free");
assertThat(status.toString()).doesNotContain(validKey);
```

Use a stubbed `OpenCodeModelCatalog` / transport at this service-test layer so the test controls free-model discovery without public network.

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```bash
cd backend
./gradlew test --tests '*OpenCodeSettingsIntegrationTest'
```

Expected: FAIL because V5/settings types do not exist yet.

- [ ] **Step 3: Add forward-only V5 migration**

Use a new product table and intentionally discard the operator-only encrypted credential table rather than trying to decrypt/migrate it:

```sql
DROP TABLE IF EXISTS provider_credentials;

CREATE TABLE opencode_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    api_key TEXT NOT NULL,
    masked_suffix VARCHAR(4) NOT NULL,
    selected_model VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Do not alter V3.

- [ ] **Step 4: Implement the aggregate/repository and safe status/runtime DTO separation**

`OpenCodeSettings` may hold the full key internally. `OpenCodeSettingsStatus` must not.

```java
public record RuntimeOpenCodeSettings(String apiKey, String selectedModel) {}
public record OpenCodeSettingsStatus(boolean configured, String maskedKey, String selectedModel) {}
```

Repository upsert always targets `singleton_id = 1`.

- [ ] **Step 5: Implement probe/save semantics using the existing provider catalog/transport**

Probe algorithm:

```text
listFreeModels(candidateKey)
→ if empty: validation unavailable
→ validateCredential(candidateKey, one discovered free model)
→ return the entire discovered free-model list
→ no repository write
```

Save algorithm:

```text
listFreeModels(candidateKey)
→ require selectedModel is in that returned list and endsWith("-free")
→ validateCredential(candidateKey, selectedModel)
→ repository.upsert(apiKey + suffix + selectedModel)
```

Do not catch and convert RATE_LIMITED into authentication failure.

- [ ] **Step 6: Remove master-key runtime dependency only after new settings tests pass**

Delete the obsolete production crypto/credential path or reduce it to zero production callers. Remove `spec.agent.credential.master-key` from `application.yml` and related tests/docs that describe it as a current product requirement.

- [ ] **Step 7: Run focused and credential/provider regressions**

```bash
./gradlew test --tests '*OpenCodeSettings*' --tests '*OpenCodeModelCatalogTest' --tests '*OpenCodeCredentialValidatorTest'
```

If the old validator is intentionally removed, replace the last selector with the new probe-service tests rather than preserving a dead abstraction.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration backend/src/main/java backend/src/test/java backend/src/main/resources/application.yml
git commit -m "feat: add global opencode product settings"
```

---

### Task 2: Add the safe OpenCode settings API and make runtime model selection dynamic

**Files:**
- Create: `backend/src/main/java/com/specagent/api/settings/OpenCodeSettingsController.java`
- Create focused DTOs under `backend/src/main/java/com/specagent/api/settings/`
- Modify: `backend/src/main/java/com/specagent/model/gateway/OpenCodeZenModelGateway.java`
- Modify: `backend/src/main/resources/application.yml`
- Add tests: `backend/src/test/java/com/specagent/api/settings/OpenCodeSettingsApiIntegrationTest.java`
- Modify tests: `OpenCodeZenModelGatewayTest`, explicit wiring tests, provider diagnostics tests.

**Interfaces:**

```http
GET  /api/v1/settings/opencode
POST /api/v1/settings/opencode/probe
PUT  /api/v1/settings/opencode
```

```json
GET response:
{"configured":true,"maskedKey":"••••abcd","selectedModel":"alpha-free"}

POST probe request:
{"apiKey":"..."}
POST probe response:
{"freeModels":["alpha-free","beta-free"]}

PUT save request:
{"apiKey":"...","selectedModel":"alpha-free"}
PUT response:
{"configured":true,"maskedKey":"••••abcd","selectedModel":"alpha-free"}
```

- [ ] **Step 1: Write API tests first**

Assert that status never serializes `apiKey`, probe does not persist, save persists only after selected model validation, and a provider RATE_LIMITED maps through the existing safe API error contract as 429 without changing old settings.

- [ ] **Step 2: Run the API test and confirm RED**

```bash
./gradlew test --tests '*OpenCodeSettingsApiIntegrationTest'
```

- [ ] **Step 3: Implement controller/DTOs with no full-key read endpoint**

The controller may accept a key only in request bodies. Do not add `GET /secret`, raw debug endpoints, or a response field containing the full key.

- [ ] **Step 4: Refactor `OpenCodeZenModelGateway` to resolve settings per request**

Replace constructor-captured `selectedModel` and `OpenCodeCredentialService` with `OpenCodeSettingsService`:

```java
RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
String apiKey = settings.apiKey();
String selectedModel = settings.selectedModel();
```

Trace must record `selectedModel`, never `apiKey`.

- [ ] **Step 5: Make OpenCode the normal product default and constrain Fake to test mode**

Normal `application.yml` must no longer default `spec.agent.model.gateway` to `fake`. Keep any deterministic Fake wiring behind an explicit test profile/property used only by tests and CI. The normal user-facing runtime must return NOT_CONFIGURED until the settings aggregate exists.

- [ ] **Step 6: Run gateway/API tests**

```bash
./gradlew test --tests '*OpenCodeSettingsApiIntegrationTest' --tests '*OpenCodeZenModelGatewayTest' --tests '*ProviderFailureDiagnosticsTest' --tests '*WiringTest'
```

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/specagent/api/settings backend/src/main/java/com/specagent/model/gateway backend/src/main/resources backend/src/test/java
git commit -m "feat: productize dynamic opencode runtime settings"
```

---

### Task 3: Rename production orchestration away from Fake and lock the boundary with architecture tests

**Files:**
- Rename: `backend/src/main/java/com/specagent/agent/FakeAgentOrchestrator.java` → `AgentOrchestrator.java`
- Rename production result records/classes named `Fake*RunResult` to neutral equivalents where they are part of production APIs.
- Modify: `backend/src/main/java/com/specagent/api/agent/AgentCommandService.java`
- Update all production/test references.
- Extend existing ArchUnit/architecture tests under `backend/src/test/java`.

**Interfaces:**
- Production service type: `AgentOrchestrator`
- Existing methods keep behavior/signatures except neutral return type names: draft, answer-and-draft-next, repair, generate spec.

- [ ] **Step 1: Add an architecture test that fails while production orchestration still exposes Fake naming**

Example rule:

```java
classes().that().resideInAPackage("..agent..")
    .and().areNotInterfaces()
    .should().haveSimpleNameNotContaining("Fake");
```

Scope the rule to production orchestration/contracts only so test doubles under test source are not prohibited.

- [ ] **Step 2: Run the architecture test and confirm RED**

- [ ] **Step 3: Rename the orchestrator/result types without changing sequencing**

Do not rewrite the answer/spec lifecycle in this task. This is naming/wiring cleanup only.

- [ ] **Step 4: Run all agent tests**

```bash
./gradlew test --tests 'com.specagent.agent.*'
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/specagent/agent backend/src/main/java/com/specagent/api/agent backend/src/test/java
git commit -m "refactor: make agent orchestration production neutral"
```

---

### Task 4: Build pre-proposal replacement context without canonical replacement identity

**Files:**
- Modify: `backend/src/main/java/com/specagent/context/ContextBuilder.java`
- Modify: `backend/src/main/java/com/specagent/agent/ModelContextProjectionBuilder.java`
- Modify tests: `ModelContextProjectionBuilderTest`, `ScriptedRouteIsolationIntegrationTest`, route/context isolation tests.

**Interfaces:**

Create/replace the old context method with a source-anchored form such as:

```java
ContextSnapshot buildForReplacement(
    UUID projectId,
    UUID sourceRouteId,
    UUID targetNodeId
)
```

The resulting snapshot uses `sourceRouteId` as route identity and target parent as tip/lineage anchor. It does not require `replacementRouteId` or `replacementNodeId`.

Add a generic DRAFT_NODE run-local input builder:

```java
Map<String,Object> redirectedNodeTaskInput(
    String oldQuestion,
    String oldPurpose,
    String userDirection
)
```

Use a generic mode such as `redirected` / `redirect`, not a domain term such as `mvp`.

- [ ] **Step 1: Write isolation tests first**

Create sentinel old-answer/patch/child/sibling data and assert the replacement `inputJson` contains:

```text
parent lineage content
oldQuestion
oldPurpose
userDirection
```

and does not contain:

```text
old target answer sentinel
old target patch sentinel
old child sentinel/id
sibling sentinel/id
old spec content
replacement route id
replacement node id
```

- [ ] **Step 2: Run the isolation tests and confirm RED against the old regenerate snapshot shape**

- [ ] **Step 3: Implement source-anchored replacement snapshot**

Do not create routes/nodes in `ContextBuilder`. Keep it deterministic and model-free.

- [ ] **Step 4: Add redirected DRAFT_NODE taskInput while preserving the same DRAFT_NODE structured contract**

Do not add a `REPLACE_MVP_NODE` task type or a second question schema.

- [ ] **Step 5: Run focused context/projection tests**

```bash
./gradlew test --tests '*ModelContextProjectionBuilderTest' --tests '*ScriptedRouteIsolationIntegrationTest' --tests '*Context*Isolation*'
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/specagent/context backend/src/main/java/com/specagent/agent backend/src/test/java
git commit -m "refactor: freeze replacement context before canonical mutation"
```

---

### Task 5: Implement real-model `换一个问题` orchestration and deterministic canonical replacement commit

**Files:**
- Modify: `backend/src/main/java/com/specagent/agent/AgentOrchestrator.java`
- Modify: `backend/src/main/java/com/specagent/route/RouteService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandController.java`
- Replace/refactor: `RegenerateNodeRequest.java`, `RegenerateResponse.java` as necessary while preserving safe API naming compatibility where useful.
- Add tests: a scripted model replacement integration test and API integration tests.

**Interfaces:**

Normal product request becomes minimal:

```json
{
  "sourceRouteId":"...",
  "instruction":"我现在更关心第一版 MVP 的范围"
}
```

No `replacementQuestion`, `replacementPurpose`, or replacement options from the client.

Agent method shape:

```java
ReplacementRunResult replaceQuestion(
    UUID projectId,
    UUID sourceRouteId,
    UUID targetNodeId,
    String userDirection
)
```

Runtime commit remains model-free, for example:

```java
RegenerateResult commitReplacementFromNode(
    UUID projectId,
    UUID sourceRouteId,
    UUID targetNodeId,
    String label,
    String question,
    String purpose,
    List<NodeOption> options,
    boolean allowFreeAnswer
)
```

- [ ] **Step 1: Write a scripted success test before implementation**

The scripted gateway returns one valid generic `ask_next_question` envelope. Assert:

```text
old route unchanged while model call is in flight
replacement is sibling of target (same parent)
old OPEN route becomes SUPERSEDED only after accepted proposal
new route is OPEN + Project.activeRouteId
new node supersedes target node
no old target answer/patch/child is inherited
```

- [ ] **Step 2: Write failure tests before implementation**

For provider failure, invalid JSON, wrong action, and node-reflection rejection, assert:

```text
old lifecycle unchanged
Project.activeRouteId unchanged
route count unchanged
node count unchanged
no accepted replacement artifact persisted
AgentRun FAILED with safe failure category
```

- [ ] **Step 3: Run focused tests and confirm RED**

- [ ] **Step 4: Implement orchestrator order exactly as the spec requires**

```text
validate source/target
→ buildForReplacement
→ create AgentRun
→ DRAFT_NODE through ModelGateway
→ strict parser
→ NodeReflectionGate
→ reject textual identity with old question after conservative normalization
→ RouteService.commitReplacementFromNode(...validated content...)
→ mark produced ids / complete AgentRun
```

Do not persist a canonical replacement before reflection passes.

- [ ] **Step 5: Refactor `RouteService.regenerateFromNode` into the deterministic commit path**

Remove model/context responsibilities from `RouteService`. It must not depend on ModelGateway or prompt code. It receives accepted content only.

- [ ] **Step 6: Update API command composition to call AgentOrchestrator, not to author replacement NodeOptions**

Controller/service must no longer map user-authored replacement options into runtime objects.

- [ ] **Step 7: Run replacement + route + agent regression**

```bash
./gradlew test --tests '*Replacement*' --tests '*Regenerate*' --tests '*RouteControlIntegrationTest' --tests 'com.specagent.agent.*'
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/specagent/agent backend/src/main/java/com/specagent/route backend/src/main/java/com/specagent/api/route backend/src/test/java
git commit -m "feat: generate replacement questions through real model path"
```

---

### Task 6: Harden route source eligibility and lifecycle transition matrix

**Files:**
- Modify: `backend/src/main/java/com/specagent/route/RouteService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandService.java` only for safe error translation, not duplicate state rules.
- Modify tests: `RouteLifecycleIntegrationTest`, `RouteControlIntegrationTest`, API route command integration tests.

**Interfaces / allowed transitions:**

```text
OPEN
  archive -> ARCHIVED
  delete  -> DELETED

SUPERSEDED
  restore -> OPEN (+active)
  archive -> ARCHIVED
  delete  -> DELETED

ARCHIVED
  restore -> OPEN (+active)
  delete  -> DELETED

DELETED
  restore -> OPEN (+active)

activate: OPEN only
exploration source (Fork/Re-answer/换题): OPEN or SUPERSEDED
ARCHIVED: restore first
DELETED: never a mutation source
```

- [ ] **Step 1: Add parameterized lifecycle tests for every allowed and rejected edge**

Illegal/repeated operations must throw a stable runtime conflict condition that the API maps to 409; do not silently make repeated lifecycle commands idempotent in this phase.

- [ ] **Step 2: Add source-eligibility tests**

Prove SUPERSEDED works as an explicit source for Fork/Re-answer/replacement, while ARCHIVED and DELETED fail without implicit restore.

- [ ] **Step 3: Run tests and confirm RED**

- [ ] **Step 4: Replace unrestricted lifecycle updates with one guarded transition helper**

Example internal shape:

```java
private Route requireStatus(UUID projectId, UUID routeId, Set<RouteLifecycleStatus> allowed) { ... }
```

Use the helper for lifecycle operations and a separate `requireExplorableSourceRoute` for OPEN/SUPERSEDED.

- [ ] **Step 5: Run all route tests**

```bash
./gradlew test --tests 'com.specagent.route.*' --tests 'com.specagent.api.route.*'
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/specagent/route backend/src/main/java/com/specagent/api/route backend/src/test/java
git commit -m "fix: enforce route lifecycle and exploration source rules"
```

---

### Task 7: Add the global frontend OpenCode settings experience

**Files:**
- Create: `frontend/src/api/modelSettings.ts`
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/stores/modelSettingsStore.ts`
- Create: `frontend/src/views/SettingsView.vue`
- Modify: `frontend/src/router/*`
- Modify: `frontend/src/App.vue` and/or existing app header for a global `设置` entry.
- Add tests under `frontend/src/api/__tests__`, `stores/__tests__`, `views/__tests__`.

**Interfaces:**

```ts
export interface OpenCodeSettingsStatus {
  configured: boolean
  maskedKey: string | null
  selectedModel: string | null
}

export interface OpenCodeProbeResponse { freeModels: string[] }
```

Store actions:

```ts
loadStatus(): Promise<void>
probe(apiKey: string): Promise<string[]>
save(apiKey: string, selectedModel: string): Promise<void>
```

- [ ] **Step 1: Write UI/store/API tests first**

Assert:

```text
settings is global (route has no projectId)
model select disabled before successful probe
probe result renders only returned -free models
save requires explicit model choice
masked status shown after save
full saved key is never rendered after reload
429 renders the existing safe API error and leaves prior visible status unchanged
```

- [ ] **Step 2: Run frontend focused tests and confirm RED**

```bash
cd frontend
npm run test:unit -- --run src/api/__tests__ src/stores/__tests__ src/views/__tests__
```

- [ ] **Step 3: Implement typed API/store**

All `fetch()` stays in `src/api/*`; components/views do not call fetch directly.

- [ ] **Step 4: Implement SettingsView with two-step interaction**

Chinese copy:

```text
模型设置
OpenCode API Key
验证并获取模型
可用模型
保存
```

Do not expose master-key controls or per-project settings.

- [ ] **Step 5: Add global settings navigation and NOT_CONFIGURED shortcut**

Model-required action errors should offer a clear `前往模型设置` UI action while history/graph reading remains available.

- [ ] **Step 6: Run typecheck/unit/build**

```bash
npm run typecheck
npm run test:unit -- --run
npm run build
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api frontend/src/stores frontend/src/views frontend/src/router frontend/src/App.vue frontend/src/components
git commit -m "feat: add global opencode settings UI"
```

---

### Task 8: Remove branch-operation source pickers and make Focus the explicit shared-node reading context

**Files:**
- Modify: `frontend/src/stores/graphUiStore.ts`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: `frontend/src/components/ForkRouteDialog.vue`
- Modify: `frontend/src/components/ReanswerRouteDialog.vue`
- Replace/refactor: `frontend/src/components/RegenerateNodeDialog.vue` → natural-language replacement dialog.
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/components/workspace/NodeInspector.vue` if it still owns node-level branch actions.
- Modify: graph projection/type helpers as required.
- Update component/store tests.

**Interfaces / semantics:**

- `focusRouteId: string | null` remains the only browser reading-route state.
- Remove any `readingRouteId = focusRouteId ?? activeRouteId` fallback for ambiguous shared nodes.
- A shared node with no Focus renders neutral and branch controls require `当前查看` selection.
- Dialog submit payload contains explicit `sourceRouteId` derived before opening/submitting, but no source-route picker field.

- [ ] **Step 1: Write graph UI store tests first**

Assert:

```ts
expect(store.readingRouteIdFor(sharedNode)).toBeNull()
store.setFocusRoute('route-b')
expect(store.readingRouteIdFor(sharedNode)).toBe('route-b')
expect(store.activeRouteId).toBe('route-a') // Focus did not Activate
```

Use the current store API names where possible; introduce `readingRouteIdFor(node)` only if a node-aware selector is needed.

- [ ] **Step 2: Write dialog/inspector tests first**

Assert Fork/Re-answer/换题 forms no longer render source `<select>` controls. Shared ambiguous node shows `当前查看：未选择` and blocks branch action until Focus is selected.

- [ ] **Step 3: Run focused frontend tests and confirm RED**

- [ ] **Step 4: Implement node-aware reading selection from Focus only**

Do not introduce a second persisted `nodeRouteContextId`.

- [ ] **Step 5: Simplify branch dialogs**

Fork: optional friendly label only.

Re-answer: optional friendly label only.

换题: one natural-language direction field only:

```text
你接下来更想澄清哪个方面？也可以直接说说你目前最关心的需求。
[取消] [生成新问题]
```

- [ ] **Step 6: Run store/component/view unit tests**

- [ ] **Step 7: Commit**

```bash
git add frontend/src/stores frontend/src/views frontend/src/components frontend/src/graph
git commit -m "fix: derive branch source from explicit graph reading context"
```

---

### Task 9: Correct Graph visibility, reveal, node sizing, provenance, Inspector, labels, and Spec presentation

**Files:**
- Modify: `frontend/src/stores/graphUiStore.ts`
- Modify: `frontend/src/components/workspace/RouteNavigator.vue`
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/components/graph/GraphCanvas.vue`
- Modify: `frontend/src/components/graph/GraphQuestionNode.vue`
- Modify: `frontend/src/components/graph/AdaptiveGraphEdge.vue`
- Modify: `frontend/src/graph/graphProjection.ts`, `graphViewport.ts`, `graphEdgeRouting.ts` where necessary.
- Modify: `frontend/src/components/SpecSnapshotPanel.vue`, `SpecSnapshotList.vue`.
- Modify: `frontend/src/style.css`.
- Modify backend route-label creation in `ProjectService` / `RouteService` if friendly labels are persisted canonically rather than presentation-only.
- Add/update unit and Playwright tests.

**Required behavior:**

```text
default visibility: OPEN + SUPERSEDED + ARCHIVED, DELETED off
Focus: highlight/read only
独览此路线: separate visibility control
显示全部: clears isolate/manual dim/hide without relayout
branch success: Active + Focus + reveal new visual node
reveal: camera only, never coordinate mutation
pending node: enough height; no whole-node scrollbar clipping submit
historical answer: compact 3–4 lines
replacement provenance: no permanent prominent yellow dashed cross-canvas edge
route names: 主路线 / 分支路线 N / 重新回答路线 N / 换题路线 N
Inspector: 当前查看路线 prominent; focused answer first
Spec: friendly route name primary; identical source refs presentation-deduped
```

- [ ] **Step 1: Extend unit tests for each acceptance finding before CSS/logic changes**

Tests must explicitly cover:

- default archived visibility
- Focus does not hide routes
- isolate is a separate control
- show-all does not rewrite saved positions
- branch reveal requests viewport pan/zoom but leaves position storage untouched
- current answerable node renders submit controls without node-body overflow class
- replacement edge not present in normal graph projection
- `Initial route` / UUID prefix are not normal route labels
- Inspector visibly names Focus route
- duplicate source ref count is deduped

- [ ] **Step 2: Add/adjust Playwright scenarios for browser-visible semantics**

Use selectors tied to product labels/data-test ids, not CSS geometry snapshots. Verify position preservation by recording node transform/position before branch and comparing after canonical refresh.

- [ ] **Step 3: Run focused tests and confirm failures**

- [ ] **Step 4: Implement visibility/Focus/isolate semantics in store + navigator**

Keep lifecycle filters independent from browser manual hide/dim/isolate state.

- [ ] **Step 5: Implement branch-success reveal through `graphViewport.ts` / GraphCanvas exposed API**

Never call auto-layout from canonical refresh or branch-success handlers.

- [ ] **Step 6: Fix node sizing/content overflow**

Historical text clamps to 3–4 lines. Current answerable node grows within a bounded max height; textarea may scroll, node shell must keep submit reachable.

- [ ] **Step 7: Remove permanent replacement provenance edge from the default projection**

Keep `supersedesNodeId` available to Inspector. If selected-node inspection needs an optional weak edge, gate it by selection only.

- [ ] **Step 8: Implement friendly route labels and Spec/Inspector presentation**

If label is persisted by backend, centralize default naming in runtime route creation rather than duplicating counters in several Vue components.

- [ ] **Step 9: Run frontend full verification**

```bash
npm run typecheck
npm run test:unit -- --run
npm run build
npm run test:e2e
```

For deterministic E2E, use only the explicit test-only Fake gateway profile; this run is not the real-product acceptance gate.

- [ ] **Step 10: Commit**

```bash
git add frontend backend/src/main/java backend/src/test/java
git commit -m "fix: close graph workspace manual acceptance gaps"
```

---

### Task 10: Add Phase 8 CI and anti-overfitting architecture hardening

**Files:**
- Create: `.github/workflows/ci.yml`
- Extend existing architecture tests under `backend/src/test/java`.
- Add a lightweight production-source anti-overfitting scan test if no existing equivalent can express the checks.
- Update documentation: `docs/PHASE_8_EXIT_CRITERIA.md`, operator/development docs as current behavior requires.

**CI responsibilities:**

Backend job:

```text
checkout
Java 21
PostgreSQL service
Gradle cache
./gradlew cleanTest test
```

Frontend job:

```text
checkout
Node version from existing package/tooling constraints
npm ci
npm run typecheck
npm run test:unit -- --run
npm run build
```

E2E job may be included if current runner setup is stable with PostgreSQL + explicit test-only gateway profile. It must never require a real OpenCode API key.

- [ ] **Step 1: Write/extend architecture tests before CI**

Lock at least these rules:

```text
runtime route/node/answer/context packages do not depend on model/settings API
ContextBuilder does not depend on ModelGateway
provider adapter classes do not depend on route/node/requirement-domain UI concepts
production orchestrator does not contain Fake naming
normal product configuration does not default to Fake
no production source contains concrete domain branches/classes introduced for software/marketing/ecommerce/MVP fixtures
no test sentinel/string is referenced from production source
```

The anti-overfitting scan must target behavior/code identifiers, not ban ordinary Chinese/English words in comments blindly. Prefer package/dependency/class-name rules plus a small explicit forbidden production identifier set.

- [ ] **Step 2: Run architecture tests and fix any real violations before writing CI**

- [ ] **Step 3: Create CI workflow with deterministic tests only**

Do not inject a real API key into GitHub Actions for this phase.

- [ ] **Step 4: Add Phase 8 exit criteria document**

The document must distinguish:

```text
CI deterministic proof
vs
real OpenCode product acceptance proof
```

and list the 429 stop rule.

- [ ] **Step 5: Run the same commands locally that CI will run**

```bash
cd backend && ./gradlew cleanTest test
cd ../frontend && npm ci && npm run typecheck && npm run test:unit -- --run && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add .github backend/src/test docs
git commit -m "ci: add phase 8 hardening gates"
```

---

### Task 11: Update Playwright coverage for the corrected product flows

**Files:**
- Modify/add specs under `frontend/e2e/`.
- Modify shared E2E helpers only to express reusable product operations, never to bypass UI state.

**Required deterministic browser flows:**

1. Create project → draft → answer → next question.
2. Fork from a focused visual-node route without source picker; old coordinates preserved; new route Active+Focus; all routes remain visible.
3. Re-answer from focused source without picker; same canonical question appears unanswered on new route; viewport reveals it.
4. `换一个问题` dialog contains only user direction; scripted gateway proposal creates sibling replacement; old route remains visible SUPERSEDED.
5. Shared node with no Focus is neutral and requires `当前查看`; selecting it changes Focus but not Active.
6. Focus vs 独览 vs 显示全部 semantics.
7. Lifecycle archive/restore/delete guards.
8. Spec friendly route/source presentation and duplicate ref display dedupe.
9. Settings page probe/select/save using backend configured in deterministic test mode with provider calls stubbed at the provider boundary if necessary.

- [ ] **Step 1: Convert old regenerate/source-picker E2E expectations to the new product contract**

Delete tests that require users to author replacement question/purpose/options.

- [ ] **Step 2: Add explicit no-relayout assertions**

Record the position of a pre-existing visual node, run Fork/Re-answer/换题, refresh graph, and assert the position is unchanged.

- [ ] **Step 3: Run Playwright and fix product/test selectors, not runtime semantics**

```bash
npm run test:e2e
```

Never add production behavior solely to satisfy an E2E fixture.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e frontend/src
git commit -m "test: cover corrected graph product flows"
```

---

### Task 12: Full deterministic verification and code-quality review

**Files:**
- No new feature files unless verification exposes a real spec violation.
- Update docs only for verified command/results.

- [ ] **Step 1: Run backend full regression**

```bash
cd backend
./gradlew cleanTest test
```

Expected: BUILD SUCCESSFUL; real-provider tests may remain opt-in, but no unexpected skipped deterministic tests.

- [ ] **Step 2: Run frontend full regression**

```bash
cd frontend
npm run typecheck
npm run test:unit -- --run
npm run build
npm run test:e2e
```

- [ ] **Step 3: Run repository hygiene checks**

```bash
git diff --check
git status --short
```

Search production source for stale product-Fake/master-key concepts and old UI wording:

```bash
git grep -n "SPEC_AGENT_CREDENTIAL_MASTER_KEY\|Initial route\|Regenerated from" -- backend/src/main frontend/src || true
git grep -n "replacementQuestion\|replacementPurpose\|replacementOptions" -- frontend/src || true
```

Any remaining occurrence must be justified as migration/history/test compatibility, not a reachable normal product path.

- [ ] **Step 4: Review anti-overfitting explicitly**

Inspect every new condition and prompt change. Reject changes whose condition depends on a concrete test phrase/domain rather than lifecycle, context, structured contract, or generic requirement semantics.

- [ ] **Step 5: Commit only verification/doc fixes if needed**

Do not make opportunistic unrelated refactors during closure.

---

### Task 13: Real OpenCode product acceptance through the UI

**Files:**
- No seeded secret file.
- Update `docs/PHASE_8_EXIT_CRITERIA.md` / acceptance record with safe observations only after the run.

**This is the product proof. Passing Fake/scripted tests is not sufficient.**

- [ ] **Step 1: Start the normal product runtime, not the Fake/test profile**

Use local PostgreSQL and the normal backend/frontend configuration. Confirm there is no OpenCode setting row if testing first-run configuration.

- [ ] **Step 2: Configure OpenCode through the product UI itself**

User/Luna enters the real API key in `设置 → 模型设置`, clicks `验证并获取模型`, observes the current dynamically discovered `-free` list, explicitly selects one model, and saves.

Record only:

```text
selected model id
masked key suffix
free model count
```

Never record the full key.

- [ ] **Step 3: Run a real clarification loop**

Through UI:

```text
create/open project
→ draft first question
→ answer option and/or free text
→ observe requirement state
→ continue at least two answer cycles
```

Judge semantic reasonableness manually, but do not change automated assertions to require exact model wording.

- [ ] **Step 4: Run real Fork and Re-answer flows**

Verify source context follows explicit Focus/read route, sibling conclusions do not leak, old routes stay visible, and new nodes are revealed without coordinate changes.

- [ ] **Step 5: Run real `换一个问题`**

Give a natural-language direction different from prior test wording. Verify the generated question follows the direction reasonably, is structurally valid, is a sibling replacement, and old answer/child information does not leak into the replacement context/output.

- [ ] **Step 6: Generate a real SpecSnapshot**

Verify route-scoped source refs, unresolved items, friendly route presentation, and derived/non-authoritative labeling.

- [ ] **Step 7: Apply the mandatory 429 stop rule**

If any real provider call returns RATE_LIMITED:

```text
STOP immediately.
Do not retry.
Do not switch model.
Do not switch to Fake.
Do not modify prompt/parser/runtime to bypass the failure.
Report: operation, selected model, completed acceptance steps, RATE_LIMITED.
Wait for the user to change network segment, then resume from the explicitly identified step.
```

- [ ] **Step 8: Record safe acceptance result and final SHA**

Document which real flows passed and the selected model, without model-secret material or raw full prompts/outputs.

- [ ] **Step 9: Final repository verification**

```bash
git status --short
git log -1 --oneline
git diff --check HEAD~1..HEAD
```

The closeout is complete only when deterministic regression, Phase 8 hardening gates, and real OpenCode UI acceptance have all passed (or when the only blocker is a reported 429 awaiting the user's network change).
