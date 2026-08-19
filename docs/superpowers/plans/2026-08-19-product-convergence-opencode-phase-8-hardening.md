# Product Convergence, OpenCode Productization, and Phase 8 Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Productize the existing real OpenCode gateway, replace deterministic question replacement with a generic real-model drafting flow, close the Phase 7 Graph Workspace acceptance gaps, and finish Phase 8 CI/architecture hardening without weakening runtime invariants or introducing domain/test overfitting.

**Architecture:** Keep the existing modular-monolith boundaries. Runtime Kernel remains deterministic and model-free; `AgentOrchestrator` freezes lineage context, asks `ModelGateway` for structured proposals, validates them, and only then calls runtime services to persist accepted canonical state. OpenCode settings are one global local-product aggregate in PostgreSQL, while Fake/scripted gateways remain explicit test infrastructure only.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL 17, Flyway, Jackson, Vue 3, TypeScript, Pinia, Vue Flow, Vitest, Playwright, Gradle, GitHub Actions.

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
- `换一个问题` reuses the generic `DRAFT_NODE` / `NodeDraft` capability; its difference is frozen replacement context plus explicit run-local drafting intent.
- Replacement proposal must be parsed/reflected/validated before canonical replacement Route/Node creation or source-route lifecycle mutation.
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

### Backend model settings

Create `com.specagent.settings.opencode` as one focused product-settings boundary:

- `OpenCodeSettings` — persisted global aggregate with full key only inside backend memory/persistence.
- `OpenCodeSettingsStatus` — safe UI/status projection; no full key field.
- `RuntimeOpenCodeSettings` — backend-only key+model projection consumed by the gateway.
- `OpenCodeSettingsRepository` — singleton persistence boundary.
- `JdbcOpenCodeSettingsRepository` — PostgreSQL implementation following current JDBC repository patterns.
- `OpenCodeSettingsService` — probe, save, status, runtime resolve.

The old `com.specagent.credential` encryption/master-key stack is removed after callers migrate. Provider protocol stays in `com.specagent.model.provider`. Runtime route/node/context packages must not depend on settings/model/provider code.

### Backend agent/runtime

- Rename `backend/src/main/java/com/specagent/agent/FakeAgentOrchestrator.java` to `AgentOrchestrator.java` and neutralize production result type names.
- Move deterministic `FakeModelAdapter` out of normal agent semantics into a clearly test-only profile/package such as `com.specagent.testing`, retaining it only so backend/E2E deterministic tests can start a local app without public network.
- `AgentOrchestrator` owns model-driven replacement orchestration.
- `ContextBuilder` owns source-anchored replacement `ContextSnapshot` construction.
- `ModelContextProjectionBuilder` owns run-local redirected drafting intent.
- `RouteService` owns deterministic canonical replacement commit and lifecycle/source eligibility.
- `ContextGuard` validates replacement contexts against explicit source-route lifecycle rules.
- `RouteCommandService` remains API command composition; it must not reimplement runtime state rules.

### Frontend

- `frontend/src/api/modelSettings.ts` — all OpenCode settings HTTP calls.
- `frontend/src/stores/modelSettingsStore.ts` — global settings UI state.
- `frontend/src/views/SettingsView.vue` — global user settings page.
- Existing `graphUiStore.ts`, `WorkspaceView.vue`, `RouteNavigator.vue`, `WorkspaceInspector.vue`, `GraphCanvas.vue`, `GraphQuestionNode.vue`, graph projection/viewport helpers keep Graph semantics.
- Replace/refactor `RegenerateNodeDialog.vue` into a narrow natural-language `换一个问题` dialog; deterministic replacement-authoring controls are removed from normal product UI.

### CI / hardening

- Create `.github/workflows/ci.yml`.
- Extend the existing architecture-test suite rather than creating a second architecture framework.
- CI uses PostgreSQL 17 and deterministic test-only model wiring; CI never requires a real OpenCode secret.
- Real-provider acceptance is a separate local/release gate through the product UI.

---

### Task 1: Replace encrypted operator credential persistence with the global product OpenCode settings aggregate

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__opencode_settings.sql`
- Create: `backend/src/main/java/com/specagent/settings/opencode/OpenCodeSettings.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/OpenCodeSettingsStatus.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/RuntimeOpenCodeSettings.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/OpenCodeSettingsRepository.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/JdbcOpenCodeSettingsRepository.java`
- Create: `backend/src/main/java/com/specagent/settings/opencode/OpenCodeSettingsService.java`
- Create: `backend/src/test/java/com/specagent/settings/opencode/OpenCodeSettingsIntegrationTest.java`
- Delete after migration: production files under `backend/src/main/java/com/specagent/credential/` (`CredentialCrypto`, `CredentialCryptoError`, `CredentialStatus`, `CredentialValidator`, `InvalidProviderCredentialError`, `OpenCodeCredentialService`, `OpenCodeCredentialValidator`, `ProviderCredential`, `ProviderCredentialRepository`, `ProviderValidationUnavailableError`) once `git grep` proves zero remaining production callers.
- Delete/update their old credential tests accordingly.
- Modify: `backend/src/main/resources/application.yml` to remove the credential master-key property.

**Interfaces:**

```java
public record OpenCodeSettingsStatus(
    boolean configured,
    String maskedKey,
    String selectedModel
) {}

public record RuntimeOpenCodeSettings(
    String apiKey,
    String selectedModel
) {}
```

```java
OpenCodeSettingsStatus status();
List<String> probe(String apiKey);
OpenCodeSettingsStatus save(String apiKey, String selectedModel);
RuntimeOpenCodeSettings requireRuntimeSettings();
```

- [ ] **Step 1: Write RED integration tests for the singleton aggregate**

Use `@MockBean`/the repository's existing Spring test mechanism to stub `OpenCodeModelCatalog` and `OpenCodeZenTransport`; the test must not call the public provider.

Representative assertions:

```java
assertThat(service.status().configured()).isFalse();

when(catalog.listFreeModels("candidate-key"))
    .thenReturn(List.of("alpha-free", "beta-free"));

doNothing().when(transport).validateCredential("candidate-key", "alpha-free");

assertThat(service.probe("candidate-key"))
    .containsExactly("alpha-free", "beta-free");
assertThat(service.status().configured()).isFalse(); // probe never persists

OpenCodeSettingsStatus saved = service.save("candidate-key", "alpha-free");
assertThat(saved.configured()).isTrue();
assertThat(saved.maskedKey()).isEqualTo("••••-key");
assertThat(saved.selectedModel()).isEqualTo("alpha-free");
assertThat(saved.toString()).doesNotContain("candidate-key");
```

Also test: failed validation leaves an existing row untouched; selected model not returned by the current catalog is rejected; model not ending `-free` is rejected.

- [ ] **Step 2: Run the focused test and confirm RED**

```bash
cd backend
./gradlew test --tests '*OpenCodeSettingsIntegrationTest'
```

Expected: FAIL because V5/settings classes do not exist.

- [ ] **Step 3: Add forward-only V5 migration**

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

Do not edit V3. The old operator-only encrypted row is intentionally not migrated.

- [ ] **Step 4: Implement aggregate/repository/status separation**

Repository upsert always writes `singleton_id = 1`. `status()` returns only mask+model. `requireRuntimeSettings()` is the only normal service method that exposes the full key to a backend caller.

- [ ] **Step 5: Implement probe using existing provider catalog/transport directly**

```text
catalog.listFreeModels(candidateKey)
→ require non-empty list
→ transport.validateCredential(candidateKey, freeModels[0])
→ return immutable freeModels
→ no repository write
```

Do not preserve the old `CredentialValidator` abstraction; it would duplicate model discovery and belongs to the superseded credential stack.

- [ ] **Step 6: Implement save with revalidation before the single-row upsert**

```text
catalog.listFreeModels(candidateKey)
→ require selectedModel appears in returned list
→ require selectedModel endsWith("-free")
→ transport.validateCredential(candidateKey, selectedModel)
→ repository.upsert(key + suffix + model)
```

Provider exceptions, including RATE_LIMITED, propagate as the existing provider-neutral `ModelGatewayException` hierarchy; no old settings row is mutated before validation succeeds.

- [ ] **Step 7: Remove the old credential/master-key production stack**

Run:

```bash
git grep -n "com.specagent.credential\|SPEC_AGENT_CREDENTIAL_MASTER_KEY\|spec.agent.credential.master-key" -- backend/src/main backend/src/test docs || true
```

Update/remove every current-behavior caller/test/doc; historical phase documents may retain historical text only if clearly historical and not used as current instructions.

- [ ] **Step 8: Run focused regression**

```bash
./gradlew test --tests '*OpenCodeSettingsIntegrationTest' --tests '*OpenCodeModelCatalogTest'
```

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration backend/src/main/java backend/src/test/java backend/src/main/resources/application.yml
git commit -m "feat: add global opencode product settings"
```

---

### Task 2: Add the safe settings API and make OpenCode the normal dynamically configured product gateway

**Files:**
- Create: `backend/src/main/java/com/specagent/api/settings/OpenCodeSettingsController.java`
- Create DTOs: `OpenCodeSettingsResponse.java`, `OpenCodeProbeRequest.java`, `OpenCodeProbeResponse.java`, `OpenCodeSaveRequest.java`
- Create: `backend/src/test/java/com/specagent/api/settings/OpenCodeSettingsApiIntegrationTest.java`
- Modify: `backend/src/main/java/com/specagent/model/gateway/OpenCodeZenModelGateway.java`
- Move/refactor: `backend/src/main/java/com/specagent/agent/FakeModelAdapter.java` → `backend/src/main/java/com/specagent/testing/FakeModelAdapter.java`
- Modify: `backend/src/main/resources/application.yml`
- Create/modify: `backend/src/test/resources/application-test.yml`
- Modify gateway/wiring/provider diagnostic tests.

**Interfaces:**

```http
GET  /api/v1/settings/opencode
POST /api/v1/settings/opencode/probe
PUT  /api/v1/settings/opencode
```

```json
GET:
{"configured":true,"maskedKey":"••••abcd","selectedModel":"alpha-free"}

POST /probe request:
{"apiKey":"..."}
POST /probe response:
{"freeModels":["alpha-free","beta-free"]}

PUT request:
{"apiKey":"...","selectedModel":"alpha-free"}
```

- [ ] **Step 1: Write API tests first**

Assert: status JSON has no `apiKey`; probe does not persist; save persists only after selected-model validation; RATE_LIMITED returns HTTP 429 through existing `GatewayErrorAdvice`; existing settings remain unchanged on failed probe/save.

- [ ] **Step 2: Run API test and confirm RED**

```bash
./gradlew test --tests '*OpenCodeSettingsApiIntegrationTest'
```

- [ ] **Step 3: Implement controller/DTOs**

The full key is accepted only in request bodies. Do not add any GET endpoint that returns/reconstructs the key.

- [ ] **Step 4: Refactor `OpenCodeZenModelGateway` to resolve settings on every request**

Replace constructor-captured `selectedModel` and the old credential service with:

```java
RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
String apiKey = settings.apiKey();
String selectedModel = settings.selectedModel();
```

Use `selectedModel` for the request and trace. Never add `apiKey` to trace.

- [ ] **Step 5: Make production default OpenCode and Fake explicitly test-only**

Use exact wiring rules:

```java
// OpenCode gateway
@ConditionalOnProperty(
    name = "spec.agent.model.gateway",
    havingValue = "opencode",
    matchIfMissing = true)

// Fake adapter
@Profile("test")
@ConditionalOnProperty(
    name = "spec.agent.model.gateway",
    havingValue = "fake")
```

Normal `application.yml` default is `opencode` (or property absent with matchIfMissing). `application-test.yml` explicitly selects `fake`. Real live smoke tests that need OpenCode explicitly override `spec.agent.model.gateway=opencode` while keeping the test profile.

- [ ] **Step 6: Update Fake adapter documentation/package**

It must say deterministic test adapter, not "used until a real gateway exists" and not product default.

- [ ] **Step 7: Run gateway/API/wiring tests**

```bash
./gradlew test --tests '*OpenCodeSettingsApiIntegrationTest' --tests '*OpenCodeZenModelGatewayTest' --tests '*ProviderFailureDiagnosticsTest' --tests '*WiringTest'
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java backend/src/main/resources backend/src/test
git commit -m "feat: productize dynamic opencode runtime settings"
```

---

### Task 3: Rename production orchestration away from Fake and lock that semantic boundary

**Files:**
- Rename: `backend/src/main/java/com/specagent/agent/FakeAgentOrchestrator.java` → `AgentOrchestrator.java`
- Rename production result records/classes such as `FakeAgentRunResult` / `FakeAnswerRunResult` to neutral equivalents (`AgentRunResult`, `AnswerRunResult`, etc.) where they are consumed by production services.
- Modify: `backend/src/main/java/com/specagent/api/agent/AgentCommandService.java`
- Update test references.
- Extend existing ArchUnit/architecture tests.

**Interfaces:** existing draft/answer/repair/spec orchestration behavior remains unchanged; only production naming and injection types change.

- [ ] **Step 1: Add RED architecture/naming coverage**

The architecture check must reject `Fake*` names in the normal `com.specagent.agent` production orchestration/production-result surface while allowing the explicit `com.specagent.testing` adapter package.

Use a rule shaped around package/name predicates already supported by the repository's ArchUnit version; do not introduce a new architecture library.

- [ ] **Step 2: Run the focused architecture test and confirm RED**

- [ ] **Step 3: Rename orchestrator/result types without rewriting sequencing**

Do not change the answer/spec persistence lifecycle in this task.

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

### Task 4: Build a source-anchored pre-proposal replacement context with no canonical replacement identity

**Files:**
- Modify: `backend/src/main/java/com/specagent/context/ContextBuilder.java`
- Modify: `backend/src/main/java/com/specagent/agent/ModelContextProjectionBuilder.java`
- Modify: `backend/src/main/java/com/specagent/agent/gates/ContextGuard.java`
- Modify tests: `ModelContextProjectionBuilderTest`, `ScriptedRouteIsolationIntegrationTest`, relevant ContextGuard/isolation tests.

**Interfaces:**

```java
ContextSnapshot buildForReplacement(
    UUID projectId,
    UUID sourceRouteId,
    UUID targetNodeId
)
```

Snapshot semantics:

```text
routeId = sourceRouteId
tipNodeId = target.parentNodeId
operationType = REGENERATE (reuse current generic replacement operation type; no new model task)
included lineage = root..target.parent
specialInputs = { oldQuestion, oldPurpose }
no replacementRouteId
no replacementNodeId
```

Run-local projection:

```java
Map<String,Object> redirectedNodeTaskInput(String userDirection)
```

producing generic data such as:

```json
{"mode":"redirected","userDirection":"..."}
```

- [ ] **Step 1: Write RED isolation tests**

Create sentinel old-answer/old-patch/old-child/sibling content and assert final `inputJson` contains parent lineage, `oldQuestion`, `oldPurpose`, and `userDirection`, but excludes target answer/patch, descendants, sibling ids/text, spec-derived content, replacement route id, and replacement node id.

- [ ] **Step 2: Write RED ContextGuard tests for replacement source lifecycle**

For `operationType=REGENERATE`, explicit source route OPEN or SUPERSEDED is valid; ARCHIVED and DELETED are rejected. Regenerate/replacement context is exempt from active-route equality; normal contexts still require active OPEN route.

- [ ] **Step 3: Run focused tests and confirm RED**

- [ ] **Step 4: Implement `buildForReplacement`**

`ContextBuilder` only reads deterministic source history and persists the snapshot. It must not create Route/Node objects and must not call a model.

- [ ] **Step 5: Implement redirected DRAFT_NODE task input**

Reuse `AgentTaskType.DRAFT_NODE` and the existing `ask_next_question` output contract. Do not add `REPLACE_NODE`, `MVP_NODE`, or another feature/domain-specific model task.

- [ ] **Step 6: Update ContextGuard only for the explicit REGENERATE source rule**

Do not weaken normal active-route guarding.

- [ ] **Step 7: Run focused context/projection tests**

```bash
./gradlew test --tests '*ModelContextProjectionBuilderTest' --tests '*ScriptedRouteIsolationIntegrationTest' --tests '*ContextGuard*' --tests '*Context*Isolation*'
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/specagent/context backend/src/main/java/com/specagent/agent backend/src/test/java
git commit -m "refactor: freeze replacement context before canonical mutation"
```

---

### Task 5: Implement model-powered `换一个问题` and deterministic canonical replacement commit

**Files:**
- Modify: `backend/src/main/java/com/specagent/agent/AgentOrchestrator.java`
- Modify: `backend/src/main/java/com/specagent/route/RouteService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandController.java`
- Refactor DTO: `RegenerateNodeRequest.java`; retain endpoint path for API continuity unless a narrow alias is required, but normal request no longer contains authored replacement content.
- Modify: `RegenerateResponse.java` if needed for AgentRun/result metadata.
- Create tests: `ModelPoweredReplacementIntegrationTest.java` (scripted gateway), API integration coverage.

**Normal request:**

```json
{
  "sourceRouteId":"...",
  "instruction":"我现在更关心第一版的功能范围"
}
```

No client `replacementQuestion`, `replacementPurpose`, or `replacementOptions`.

**Agent interface:**

```java
ReplacementRunResult replaceQuestion(
    UUID projectId,
    UUID sourceRouteId,
    UUID targetNodeId,
    String userDirection
)
```

**Runtime-only commit:**

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

`RouteService` receives already accepted content; it never imports `NodeDraft` or model/prompt packages.

- [ ] **Step 1: Write scripted success test first**

Assert:

```text
source route/node unchanged before accepted model proposal
new node parent == old target parent
new node supersedesNodeId == old target id
OPEN source becomes SUPERSEDED only at canonical commit
SUPERSEDED source stays SUPERSEDED
new route OPEN + active
parent prefix inherited; target old answer/patch/descendants excluded
```

- [ ] **Step 2: Write failure tests first**

For provider failure, invalid JSON, wrong action, structured-contract failure, and node-reflection rejection assert route count/node count/lifecycle/active pointer are unchanged and AgentRun is FAILED with safe trace category.

- [ ] **Step 3: Run tests and confirm RED**

- [ ] **Step 4: Implement orchestration in this exact order**

```text
validate explicit source + target membership/eligibility
→ buildForReplacement
→ ContextGuard
→ create AgentRun linked to source route/snapshot
→ ModelGateway DRAFT_NODE using redirected taskInput
→ expected action validation
→ strict StructuredModelOutputParser
→ map NodeDraft
→ NodeReflectionGate
→ conservative normalized-text check: new question != rejected old question
→ RouteService.commitReplacementFromNode(validated fields)
→ record produced node/route ids
→ complete AgentRun
```

Do not add semantic NLP similarity checks in Runtime.

- [ ] **Step 5: Refactor old deterministic regenerate method into the pure commit path**

Remove old behavior that supersedes source route, creates replacement route/node, and only then builds regenerate context.

- [ ] **Step 6: Update API command composition**

`RouteCommandService.regenerate/replace` calls `AgentOrchestrator.replaceQuestion`; it no longer constructs `NodeOption` from browser-authored replacement options.

- [ ] **Step 7: Run replacement/route/agent regression**

```bash
./gradlew test --tests '*Replacement*' --tests '*Regenerate*' --tests '*RouteControlIntegrationTest' --tests 'com.specagent.agent.*'
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/specagent/agent backend/src/main/java/com/specagent/route backend/src/main/java/com/specagent/api/route backend/src/test/java
git commit -m "feat: generate replacement questions through model path"
```

---

### Task 6: Harden lifecycle/source rules and canonical friendly route labels

**Files:**
- Modify: `backend/src/main/java/com/specagent/route/RouteService.java`
- Modify: `backend/src/main/java/com/specagent/project/ProjectService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandService.java` only for safe conflict translation.
- Modify tests: `RouteLifecycleIntegrationTest`, `RouteControlIntegrationTest`, project creation/API route tests.

**Allowed transitions:**

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
exploration source: OPEN or SUPERSEDED
ARCHIVED: restore first
DELETED: never mutation source
```

**Default labels:**

```text
initial: 主路线
fork: 分支路线 N
re-answer: 重新回答路线 N
replacement: 换题路线 N
```

User-supplied nonblank route labels take precedence.

- [ ] **Step 1: Add parameterized RED lifecycle tests for every allowed/rejected transition**

Illegal/repeated commands return a runtime failure translated to API 409; do not silently make them idempotent.

- [ ] **Step 2: Add RED source-eligibility tests**

SUPERSEDED is valid for Fork/Re-answer/replacement. ARCHIVED/DELETED fail with no implicit restore.

- [ ] **Step 3: Add RED route-label tests**

New project route is `主路线`; blank Fork/Re-answer/replacement labels get localized sequential defaults; explicit user labels are preserved.

- [ ] **Step 4: Run tests and confirm RED**

- [ ] **Step 5: Implement one guarded lifecycle/source helper in RouteService**

Do not duplicate the transition matrix in controller/UI code.

- [ ] **Step 6: Implement default-label generation centrally in backend route creation**

Use `routeRepository.findByProject(projectId)` to count routes of the relevant `RouteBranchType` and produce the next display number. This is acceptable for the single-user local first version; do not add a separate sequence/locking platform.

Change `ProjectService` initial label from `Initial route` to `主路线`.

- [ ] **Step 7: Run all route/project tests**

```bash
./gradlew test --tests 'com.specagent.route.*' --tests 'com.specagent.api.route.*' --tests 'com.specagent.project.*' --tests 'com.specagent.api.project.*'
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/specagent/route backend/src/main/java/com/specagent/project backend/src/main/java/com/specagent/api/route backend/src/test/java
git commit -m "fix: enforce route lifecycle and friendly labels"
```

---

### Task 7: Add the global frontend OpenCode settings experience

**Files:**
- Create: `frontend/src/api/modelSettings.ts`
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/stores/modelSettingsStore.ts`
- Create: `frontend/src/views/SettingsView.vue`
- Modify: `frontend/src/router/index.ts` (or the current router entry file)
- Modify: `frontend/src/App.vue` and current app header/navigation for global `设置` entry.
- Add tests under `frontend/src/api/__tests__`, `stores/__tests__`, `views/__tests__`.

**Types:**

```ts
export interface OpenCodeSettingsStatus {
  configured: boolean
  maskedKey: string | null
  selectedModel: string | null
}

export interface OpenCodeProbeResponse {
  freeModels: string[]
}
```

**Store API:**

```ts
loadStatus(): Promise<void>
probe(apiKey: string): Promise<string[]>
save(apiKey: string, selectedModel: string): Promise<void>
```

- [ ] **Step 1: Write API/store/view tests first**

Assert: settings URL has no project id; model select disabled until successful probe; only returned free models are selectable; save requires explicit model choice; masked status shown after save; full key never appears after reload; 429 leaves previous saved status intact.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd frontend
npm run test:unit -- --run src/api/__tests__ src/stores/__tests__ src/views/__tests__
```

- [ ] **Step 3: Implement typed API/store**

All `fetch()` remains in `src/api/*`.

- [ ] **Step 4: Implement SettingsView two-step flow**

Chinese UI:

```text
模型设置
OpenCode API Key
验证并获取模型
可用模型
保存
```

No master-key control and no per-project provider control.

- [ ] **Step 5: Add global navigation and NOT_CONFIGURED shortcut**

When a model-required workspace command returns `MODEL_PROVIDER_NOT_CONFIGURED`, show a clear `前往模型设置` action; keep graph/history reading available.

- [ ] **Step 6: Run typecheck/unit/build**

```bash
npm run typecheck
npm run test:unit -- --run
npm run build
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat: add global opencode settings UI"
```

---

### Task 8: Remove branch source pickers and make Focus the only explicit shared-node reading context

**Files:**
- Modify: `frontend/src/stores/graphUiStore.ts`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: `frontend/src/components/ForkRouteDialog.vue`
- Modify: `frontend/src/components/ReanswerRouteDialog.vue`
- Refactor/rename: `frontend/src/components/RegenerateNodeDialog.vue` → `ReplaceQuestionDialog.vue` (or same file name with only the new one-field contract; tests/copy must call it `换一个问题`).
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/components/workspace/NodeInspector.vue` if it owns node branch actions.
- Modify graph types/projection only where needed for explicit route membership.
- Update unit tests.

**Semantics:**

- `focusRouteId` remains the one browser reading-route state.
- Remove current `focusRouteId ?? activeRouteId` reading fallback for ambiguous shared nodes.
- Shared node + no Focus = neutral.
- Selecting `当前查看` sets global Focus only; it never calls Activate.
- Branch dialog payload retains explicit `sourceRouteId` resolved before submit; dialogs contain no source selector.

- [ ] **Step 1: Write RED graph store tests**

Representative semantic assertion:

```ts
expect(readingRouteFor(sharedNode, null, 'active-a')).toBeNull()
store.setFocusRoute('route-b')
expect(readingRouteFor(sharedNode, store.focusRouteId, 'active-a')).toBe('route-b')
expect(workspace.activeRouteId).toBe('active-a')
```

Use existing selector/function names where they already exist; do not create duplicate state APIs.

- [ ] **Step 2: Write RED dialog/Inspector tests**

Fork/Re-answer/换题 no longer render source route `<select>` fields. Ambiguous shared node renders `当前查看：未选择` and branch controls direct the user to choose a reading route.

- [ ] **Step 3: Run focused tests and confirm RED**

- [ ] **Step 4: Implement Focus-only reading semantics**

Do not add `nodeRouteContextId`, hidden local selection, Active fallback, first-route fallback, or latest-route fallback.

- [ ] **Step 5: Simplify dialogs**

Fork: optional friendly label.

Re-answer: optional friendly label.

换题: only:

```text
你接下来更想澄清哪个方面？也可以直接说说你目前最关心的需求。
[自由输入]
[取消] [生成新问题]
```

- [ ] **Step 6: Run store/component/view tests**

- [ ] **Step 7: Commit**

```bash
git add frontend/src/stores frontend/src/views frontend/src/components frontend/src/graph
git commit -m "fix: derive branch source from explicit graph focus context"
```

---

### Task 9: Correct Graph visibility, reveal, node sizing, provenance, Inspector, and Spec presentation

**Files:**
- Modify: `frontend/src/stores/graphUiStore.ts`
- Modify: `frontend/src/components/workspace/RouteNavigator.vue`
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/components/graph/GraphCanvas.vue`
- Modify: `frontend/src/components/graph/GraphQuestionNode.vue`
- Modify: `frontend/src/components/graph/AdaptiveGraphEdge.vue`
- Modify: `frontend/src/graph/graphProjection.ts`
- Modify: `frontend/src/graph/graphViewport.ts`
- Modify: `frontend/src/graph/graphEdgeRouting.ts` only if replacement-edge classification lives there.
- Modify: `frontend/src/components/SpecSnapshotPanel.vue`, `SpecSnapshotList.vue`
- Modify: `frontend/src/style.css`
- Update unit tests and later Playwright coverage.

**Required behavior:**

```text
default visible: OPEN + SUPERSEDED + ARCHIVED; DELETED off
Focus: highlight/read only; does not hide
独览此路线: separate visibility control
显示全部: clears isolate/manual dim/hide without relayout
Fork/Re-answer/换题 success: Active + Focus + reveal new visual node
reveal: camera pan/zoom only; no position mutation
pending answerable node: enough height; submit always reachable; no whole-node internal scrollbar
historical answers: compact ~3–4 lines
replacement provenance: no permanent prominent yellow dashed cross-canvas edge
Inspector: 当前查看路线 prominent; focused route answer first
Spec: friendly route label primary; technical ids subdued; identical source refs presentation-deduped
```

- [ ] **Step 1: Add RED unit tests for every manual-acceptance finding**

Explicitly cover default archived visibility, Focus not hiding, isolate separate from Focus, show-all preserving saved positions, viewport reveal without storage mutation, current node submit reachability class/structure, no normal replacement edge, Inspector Focus route label, and source-ref presentation dedupe.

- [ ] **Step 2: Run focused unit tests and confirm RED**

- [ ] **Step 3: Implement visibility/Focus/isolate semantics in store+navigator**

Keep lifecycle filter state separate from manual dim/hide/isolate state. `显示全部` does not enable DELETED by default.

- [ ] **Step 4: Implement branch-success reveal through `graphViewport.ts` / GraphCanvas API**

Call viewport pan/zoom/reveal only. Never call `graphLayout` or rewrite position storage from branch-success/canonical-refresh handlers.

- [ ] **Step 5: Fix question-node sizing**

Historical text clamps to roughly 3–4 lines. Current answerable node grows within a bounded layout; free-text textarea may scroll, but node shell keeps submit reachable. Only titlebar remains drag handle.

- [ ] **Step 6: Remove normal permanent replacement edge**

Keep `supersedesNodeId` in graph data/Inspector. Optional weak provenance relation may render only during explicit selected-node inspection; default topology remains lineage-only.

- [ ] **Step 7: Improve Inspector and Spec presentation**

Inspector visibly shows `当前查看路线：<friendly label>` and places that route's answer first. Spec source display dedupes identical `(kind, refId)` items for display/count while preserving section-level source associations internally.

- [ ] **Step 8: Run frontend typecheck/unit/build**

```bash
npm run typecheck
npm run test:unit -- --run
npm run build
```

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "fix: close graph workspace manual acceptance gaps"
```

---

### Task 10: Add Phase 8 CI and anti-overfitting architecture hardening

**Files:**
- Create: `.github/workflows/ci.yml`
- Extend existing architecture tests under `backend/src/test/java`.
- Add production-source scan test only where ArchUnit cannot express the required check.
- Create: `docs/PHASE_8_EXIT_CRITERIA.md`
- Update current operator/development docs where configuration instructions changed.

**CI versions / services:**

```text
Java: 21
Node: 24
PostgreSQL service: postgres:17-alpine
```

**Backend CI:** checkout → setup Java 21 → PostgreSQL 17 → Gradle cache → `./gradlew cleanTest test`.

**Frontend CI:** checkout → setup Node 24 → `npm ci` → typecheck → unit → build.

E2E may run in CI only with the explicit Spring `test` profile + Fake adapter; it must not require an OpenCode key.

- [ ] **Step 1: Add/extend RED architecture hardening tests**

Lock:

```text
route/node/answer/context runtime packages do not depend on model/settings/api provider code
ContextBuilder does not depend on ModelGateway
provider adapters do not depend on route/node/product-workspace concepts
production agent orchestration has no Fake naming
default product config is not Fake
Fake adapter requires test profile
no production code references known test sentinel strings
no new concrete requirement-domain generator/analyzer classes or runtime branches
```

For anti-overfitting, prefer package dependency/class-name rules and an explicit small forbidden identifier/sentinel set. Do not ban ordinary words globally because legitimate user content/docs may contain them.

- [ ] **Step 2: Run architecture tests and fix real violations**

- [ ] **Step 3: Create `.github/workflows/ci.yml`**

Use PostgreSQL connection settings matching the project's test configuration. Do not add repository secrets for OpenCode.

- [ ] **Step 4: Create Phase 8 exit criteria**

Clearly distinguish deterministic CI proof from real OpenCode product acceptance and state the 429 hard-stop policy.

- [ ] **Step 5: Run local equivalents of CI**

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

### Task 11: Update deterministic Playwright coverage for corrected product flows

**Files:**
- Modify/add specs under `frontend/e2e/`.
- Modify shared E2E helpers only to express reusable product interactions; helpers must not bypass Focus/route-selection UI semantics.

**Required browser flows:**

1. create project → draft → answer → next question;
2. Fork from a focused visual-node source without route picker; old coordinates preserved; new route Active+Focus; all non-deleted routes remain visible;
3. Re-answer without route picker; same canonical question appears unanswered on new route; new visual instance revealed;
4. `换一个问题` dialog has only direction; scripted proposal creates sibling replacement; old route visible SUPERSEDED;
5. shared node with no Focus neutral; `当前查看` selection changes Focus but not Active;
6. Focus vs 独览 vs 显示全部;
7. archive/restore/delete lifecycle behavior;
8. Spec friendly route/source display + duplicate source display dedupe;
9. Settings probe/select/save using deterministic test profile/provider boundary.

- [ ] **Step 1: Replace old regenerate/source-picker expectations**

Delete E2E expectations that require users to author replacement question/purpose/options or reselect a source route in the operation dialog.

- [ ] **Step 2: Add no-relayout assertion**

Record a pre-existing visual node's persisted/DOM-projected position, perform Fork/Re-answer/换题, refresh canonical graph, assert position is unchanged.

- [ ] **Step 3: Run Playwright**

```bash
npm run test:e2e
```

The backend for this deterministic run must be started with `SPRING_PROFILES_ACTIVE=test` and explicit Fake gateway setting. Never change production behavior merely to satisfy an E2E fixture.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e frontend/src
git commit -m "test: cover corrected graph product flows"
```

---

### Task 12: Full deterministic verification and explicit anti-overfitting review

**Files:** no new feature files unless verification exposes an actual spec violation.

- [ ] **Step 1: Backend full regression**

```bash
cd backend
./gradlew cleanTest test
```

Expected: BUILD SUCCESSFUL; real-provider smoke tests may remain opt-in but no deterministic test is unexpectedly skipped.

- [ ] **Step 2: Frontend full regression**

```bash
cd frontend
npm run typecheck
npm run test:unit -- --run
npm run build
npm run test:e2e
```

- [ ] **Step 3: Repository hygiene checks**

```bash
git diff --check
git status --short
git grep -n "SPEC_AGENT_CREDENTIAL_MASTER_KEY\|Initial route\|Regenerated from" -- backend/src/main frontend/src || true
git grep -n "replacementQuestion\|replacementPurpose\|replacementOptions" -- frontend/src || true
```

Any remaining old term must be unreachable compatibility/history/test text, not normal product behavior.

- [ ] **Step 4: Review every new production condition and prompt change for overfitting**

Accept conditions only when keyed to generic facts such as lifecycle, lineage membership, structured contract fields, route provenance, Focus/visibility state, or generic drafting intent. Reject conditions keyed to example phrases (`MVP`, software, marketing, fixture names, sentinels) or hard-coded expected model wording.

For prompts, do not add examples/instructions solely because one real test case produced an inconvenient answer. A prompt change must solve a demonstrated generic contract/behavior problem across requirement domains.

- [ ] **Step 5: Verification-only fix commit if required**

Do not add unrelated refactors during closure.

---

### Task 13: Real OpenCode product acceptance through the user UI

**Files:** no seeded secret file; update `docs/PHASE_8_EXIT_CRITERIA.md` with safe observations only after completion.

**This is the product proof. Passing Fake/scripted tests is not sufficient.**

- [ ] **Step 1: Start normal product runtime, not the test/Fake profile**

Use local PostgreSQL and normal backend/frontend configuration. Confirm the product opens even if OpenCode is not yet configured and model-required actions point to Settings.

- [ ] **Step 2: Configure the user's real OpenCode key through the UI**

`设置 → 模型设置` → enter key → `验证并获取模型` → observe dynamically returned current `-free` list → explicitly select one model → save.

Record only selected model id, free-model count, and masked suffix. Never record the full key.

- [ ] **Step 3: Run a real clarification loop**

```text
create/open project
→ real first question draft
→ answer option and/or free text
→ real interpretation/patch
→ real next question
→ at least two answer cycles
```

Judge semantic reasonableness manually; automated tests must not assert exact model wording.

- [ ] **Step 4: Run real Fork and Re-answer**

Verify explicit Focus/read source, sibling isolation, all routes visible, Active/Focus separation, and coordinate preservation/reveal behavior.

- [ ] **Step 5: Run real `换一个问题` with fresh wording**

Use a natural-language direction not copied from deterministic fixtures. Verify the new question reasonably follows the direction, passes the generic node contract, is a sibling replacement, and old target answer/patch/children/sibling conclusions are absent from the replacement model context.

- [ ] **Step 6: Generate a real SpecSnapshot**

Verify route-scoped sources, unresolved items, friendly route presentation, source display dedupe, and derived/non-authoritative labeling.

- [ ] **Step 7: Mandatory RATE_LIMITED stop rule**

If any real call returns RATE_LIMITED:

```text
STOP immediately.
Do not retry.
Do not switch model.
Do not switch to Fake.
Do not modify prompt/parser/runtime to bypass it.
Report: exact product operation, selected model, completed acceptance steps, RATE_LIMITED.
Wait for the user to change network segment.
Resume only from the explicitly identified step after the user says to continue.
```

- [ ] **Step 8: Record safe acceptance result and final SHA**

Document passed real flows/model id/masked diagnostics only; never raw key/full prompt/full output.

- [ ] **Step 9: Final repository verification**

```bash
git status --short
git log -1 --oneline
git diff --check
```

Closure requires: deterministic regression green + Phase 8 architecture/CI gates green + real OpenCode product acceptance green. A reported 429 is an explicit external blocker, not permission to substitute Fake acceptance.
