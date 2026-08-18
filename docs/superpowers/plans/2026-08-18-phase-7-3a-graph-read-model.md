# Phase 7.3A Graph Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the read-only backend data surface needed by the Phase 7.3 graph workspace without changing Runtime route/context semantics.

**Architecture:** Add a `readmodel.graph` projection that composes existing `ProjectService`, `RouteService`, `NodeService`, and `AnswerService` reads and exposes one canonical project graph endpoint. Extend requirement-state reads with an explicit route-scoped query while preserving the existing active-route endpoint. Controllers stay thin; read models never call ModelGateway, credentials, repositories directly, or `ContextBuilder`.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, JDBC-backed existing Runtime services, JUnit 5, MockMvc, ArchUnit, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-18-phase-7-3-graph-workspace-design.md`

## Global Constraints

- Implementation baseline is the approved design commit `ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f` or a later `origin/main` containing it.
- Runtime owns history. Do not change Route/Node/Answer/Context persistence semantics.
- Active route remains `Project.activeRouteId`; active is not a lifecycle status.
- Regenerate remains deterministic; do not call a model from any new read path.
- Graph and requirement-state additions are read-only UI support.
- API classes must not depend on repositories, model/provider packages, credentials, or `ContextBuilder`.
- `com.specagent.readmodel..` must not depend on `com.specagent.api..`.
- Graph responses may expose safe Question/Option/Answer presentation fields only; never expose patches, context snapshots, prompts, provider payloads, credentials, AgentRun traces, or raw DB metadata.
- A graph read that encounters a missing/foreign/cyclic/root-mismatched lineage fails closed; do not return a partial misleading graph.
- All lifecycle states remain inspectable by read endpoints: OPEN, SUPERSEDED, ARCHIVED, DELETED.
- Existing Phase 6/7 APIs remain backward-compatible.
- Use TDD. Each task must leave tests green before its commit.
- Work on `main` only if the local checkout is clean and synchronized. At completion push to `origin main` and verify `origin/main == HEAD`.

## File Structure

### New backend graph read model

- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryException.java` — closed read-model-neutral graph query failures.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceOptionView.java` — safe option view.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceNodeView.java` — deduplicated safe node view.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceAnswerView.java` — route-specific safe answer view.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceRouteView.java` — route metadata plus authoritative `lineageNodeIds`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceView.java` — aggregate project graph response.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryService.java` — validates and projects all project routes into one graph.
- Create `backend/src/main/java/com/specagent/readmodel/graph/package-info.java` — package boundary description.

### New API graph endpoint

- Create `backend/src/main/java/com/specagent/api/graph/GraphWorkspaceController.java` — `GET /api/v1/projects/{projectId}/graph`.
- Create `backend/src/main/java/com/specagent/api/graph/package-info.java`.
- Modify `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java` — map graph read failures safely.

### Existing answer service read boundary

- Modify `backend/src/main/java/com/specagent/answer/AnswerService.java` — add one read-only batch method delegating to the existing repository query.

### Route-scoped requirement-state read

- Modify `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryException.java` — add `ROUTE_NOT_FOUND`.
- Modify `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryService.java` — add `getForRoute(UUID projectId, UUID routeId)`.
- Modify `backend/src/main/java/com/specagent/api/requirement/RequirementStateController.java` — preserve active endpoint and add route-scoped endpoint.
- Modify `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java` — map `ROUTE_NOT_FOUND`.

### Tests

- Create `backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java`.
- Modify `backend/src/test/java/com/specagent/api/requirement/RequirementStateApiIntegrationTest.java`.
- Modify `backend/src/test/java/com/specagent/architecture/ArchitectureTests.java` only if an explicit graph-specific rule adds protection beyond existing generic rules.

---

### Task 1: Expose route-scoped answers through `AnswerService`

**Files:**
- Modify: `backend/src/main/java/com/specagent/answer/AnswerService.java`
- Test: `backend/src/test/java/com/specagent/answer/AnswerServiceTest.java` if present; otherwise create it.

**Interfaces:**
- Consumes: existing `AnswerRepository.findByRouteAndNodeIds(UUID routeId, List<UUID> nodeIds)`.
- Produces: `public List<Answer> findAnswersForRouteAndNodeIds(UUID routeId, List<UUID> nodeIds)`.

- [ ] **Step 1: Write the failing service test**

Add a test that mocks `AnswerRepository`, calls the new service method, and verifies exact delegation without mutation:

```java
@Test
void findAnswersForRouteAndNodeIdsDelegatesReadOnlyQuery() {
    UUID routeId = UUID.randomUUID();
    List<UUID> nodeIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    Answer answer = new Answer(
            UUID.randomUUID(), UUID.randomUUID(), routeId, nodeIds.get(0),
            "option-a", "free text", "user", Instant.parse("2026-08-18T00:00:00Z"));
    when(answerRepository.findByRouteAndNodeIds(routeId, nodeIds)).thenReturn(List.of(answer));

    assertThat(service.findAnswersForRouteAndNodeIds(routeId, nodeIds)).containsExactly(answer);
    verify(answerRepository).findByRouteAndNodeIds(routeId, nodeIds);
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
cd backend
./gradlew test --tests com.specagent.answer.AnswerServiceTest
```

Expected: compilation failure because `findAnswersForRouteAndNodeIds` does not exist.

- [ ] **Step 3: Add the minimal read-only service method**

Implement exactly:

```java
public List<Answer> findAnswersForRouteAndNodeIds(UUID routeId, List<UUID> nodeIds) {
    return answerRepository.findByRouteAndNodeIds(routeId, nodeIds);
}
```

Do not add new persistence logic or lifecycle checks here.

- [ ] **Step 4: Run the focused test**

```bash
./gradlew test --tests com.specagent.answer.AnswerServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/specagent/answer/AnswerService.java backend/src/test/java/com/specagent/answer/AnswerServiceTest.java
git commit -m "feat: expose route answer read boundary"
```

---

### Task 2: Build the canonical graph read model

**Files:**
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryException.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceOptionView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceNodeView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceAnswerView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceRouteView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryService.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/package-info.java`
- Test initially through: `backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java`

**Interfaces:**
- Consumes: `ProjectService.getProject`, `RouteService.listRoutes`, `NodeService.getNode`, `AnswerService.findAnswersForRouteAndNodeIds`.
- Produces: `GraphWorkspaceView getForProject(UUID projectId)`.

Use these record shapes so the frontend contract remains deterministic:

```java
public record GraphWorkspaceOptionView(String id, String label, String impact) {}

public record GraphWorkspaceNodeView(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<GraphWorkspaceOptionView> options,
        boolean allowFreeAnswer,
        Instant createdAt) {}

public record GraphWorkspaceAnswerView(
        UUID id,
        UUID routeId,
        UUID nodeId,
        String selectedOptionId,
        String freeText,
        Instant createdAt) {}

public record GraphWorkspaceRouteView(
        UUID id,
        String label,
        String lifecycleStatus,
        boolean isActive,
        UUID rootNodeId,
        UUID tipNodeId,
        UUID createdFromNodeId,
        UUID supersedesRouteId,
        UUID replacementOfNodeId,
        List<UUID> lineageNodeIds) {}

public record GraphWorkspaceView(
        UUID projectId,
        UUID activeRouteId,
        List<GraphWorkspaceRouteView> routes,
        List<GraphWorkspaceNodeView> nodes,
        List<GraphWorkspaceAnswerView> answers) {}
```

`GraphWorkspaceQueryException.Reason` must be exactly:

```java
public enum Reason {
    PROJECT_NOT_FOUND,
    INVARIANT_VIOLATION
}
```

- [ ] **Step 1: Add an integration test for one route and one answer**

Create `GraphWorkspaceApiIntegrationTest` with a first test that constructs a project/route/node/answer using existing test support and expects:

```java
mockMvc.perform(get("/api/v1/projects/{projectId}/graph", projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(projectId.toString()))
        .andExpect(jsonPath("$.activeRouteId").value(routeId.toString()))
        .andExpect(jsonPath("$.routes[0].lineageNodeIds[0]").value(nodeId.toString()))
        .andExpect(jsonPath("$.nodes[0].id").value(nodeId.toString()))
        .andExpect(jsonPath("$.answers[0].routeId").value(routeId.toString()))
        .andExpect(jsonPath("$.answers[0].nodeId").value(nodeId.toString()))
        .andExpect(jsonPath("$.answers[0].selectedOptionId").value("option-a"))
        .andExpect(jsonPath("$.answers[0].freeText").value("answer text"));
```

- [ ] **Step 2: Run the focused test and verify it fails**

```bash
./gradlew test --tests com.specagent.api.graph.GraphWorkspaceApiIntegrationTest
```

Expected: 404 or compilation failure because graph read classes/controller do not exist.

- [ ] **Step 3: Implement fail-closed lineage resolution in `GraphWorkspaceQueryService`**

For each route returned by `routeService.listRoutes(projectId)`:

1. If `tipNodeId == null`, use `List.of()` as lineage and require `rootNodeId == null`.
2. Otherwise walk `tipNodeId -> parentNodeId` with a `Set<UUID> visited` and max depth `10_000`.
3. Every node must resolve and have `node.projectId().equals(projectId)`.
4. Reverse to root→tip.
5. Require `rootNodeId != null` and `rootNodeId.equals(rootToTip.get(0).id())`.
6. Deduplicate nodes globally by UUID using insertion-preserving `LinkedHashMap<UUID, Node>`.
7. For each route call `answerService.findAnswersForRouteAndNodeIds(route.id(), lineageNodeIds)` and append answers without merging by node ID.
8. Preserve deterministic route ordering from `RouteService.listRoutes`; preserve lineage order root→tip.

The key algorithm shape must be equivalent to:

```java
Map<UUID, Node> nodesById = new LinkedHashMap<>();
List<GraphWorkspaceRouteView> routeViews = new ArrayList<>();
List<GraphWorkspaceAnswerView> answerViews = new ArrayList<>();

for (Route route : routeService.listRoutes(projectId)) {
    List<Node> lineage = resolveLineage(projectId, route);
    List<UUID> lineageNodeIds = lineage.stream().map(Node::id).toList();
    lineage.forEach(node -> nodesById.putIfAbsent(node.id(), node));
    answerService.findAnswersForRouteAndNodeIds(route.id(), lineageNodeIds)
            .stream().map(GraphWorkspaceAnswerView::from).forEach(answerViews::add);
    routeViews.add(GraphWorkspaceRouteView.from(route, project.activeRouteId(), lineageNodeIds));
}
```

Do not inspect `supersedesNodeId` while computing lineage; replacement is extra metadata, not parentage.

- [ ] **Step 4: Add graph projection tests for shared nodes and route-specific answers**

Add tests covering:

```text
Route A: A -> B -> C, answer(A,B) on Route A
Route B: A -> B -> D, answer(A,B) on Route B
```

Assert:

```java
.andExpect(jsonPath("$.nodes.length()").value(4))
.andExpect(jsonPath("$.routes[0].lineageNodeIds.length()").value(3))
.andExpect(jsonPath("$.routes[1].lineageNodeIds.length()").value(3))
.andExpect(jsonPath("$.answers.length()").value(2));
```

Then assert the two answers keep different `routeId` values even though `nodeId` is the same.

- [ ] **Step 5: Add lifecycle and fork/regenerate semantics tests**

Cover all of these in integration setup:

- OPEN, SUPERSEDED, ARCHIVED, DELETED routes all appear.
- `isActive` derives only from `project.activeRouteId`.
- A fork route reuses node IDs and has no copied answer for its own `routeId`.
- A replacement route lineage contains parent lineage + replacement node and not the superseded target subtree.
- `replacementOfNodeId` and node `supersedesNodeId` are exposed as metadata only.

- [ ] **Step 6: Add fail-closed corruption tests**

Following the patterns already used by `RouteLineageApiIntegrationTest`, add separate tests for:

```text
missing lineage node       -> 500 INTERNAL_INVARIANT_VIOLATION
foreign-project node       -> 500 INTERNAL_INVARIANT_VIOLATION
cycle                      -> 500 INTERNAL_INVARIANT_VIOLATION
root mismatch              -> 500 INTERNAL_INVARIANT_VIOLATION
```

No test should accept partial `nodes` or `routes` in these cases.

- [ ] **Step 7: Run graph tests**

```bash
./gradlew test --tests com.specagent.api.graph.GraphWorkspaceApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 8: Commit the read model before adding the HTTP mapper**

```bash
git add backend/src/main/java/com/specagent/readmodel/graph backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java
git commit -m "feat: add canonical graph workspace read model"
```

If the integration test cannot compile without the controller, keep controller work in Task 3 and commit Tasks 2+3 together; do not add a temporary production endpoint.

---

### Task 3: Expose `GET /projects/{projectId}/graph` and safe errors

**Files:**
- Create: `backend/src/main/java/com/specagent/api/graph/GraphWorkspaceController.java`
- Create: `backend/src/main/java/com/specagent/api/graph/package-info.java`
- Modify: `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java`

**Interfaces:**
- Consumes: `GraphWorkspaceQueryService.getForProject(UUID)`.
- Produces: `GET /api/v1/projects/{projectId}/graph -> GraphWorkspaceView`.

- [ ] **Step 1: Add endpoint/error assertions**

Add:

```java
mockMvc.perform(get("/api/v1/projects/{projectId}/graph", missingProjectId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
```

Corruption assertions use:

```java
.andExpect(status().isInternalServerError())
.andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
```

- [ ] **Step 2: Implement the thin controller**

```java
@RestController
@RequestMapping("/api/v1/projects/{projectId}/graph")
public class GraphWorkspaceController {
    private final GraphWorkspaceQueryService queryService;

    public GraphWorkspaceController(GraphWorkspaceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public GraphWorkspaceView getGraph(@PathVariable UUID projectId) {
        return queryService.getForProject(projectId);
    }
}
```

- [ ] **Step 3: Map graph exceptions only at the API boundary**

Add to `ApiExceptionHandler`:

```java
@ExceptionHandler(GraphWorkspaceQueryException.class)
public ResponseEntity<ApiErrorResponse> handleGraphWorkspaceQuery(GraphWorkspaceQueryException ex) {
    return switch (ex.reason()) {
        case PROJECT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("PROJECT_NOT_FOUND", "Project not found"));
        case INVARIANT_VIOLATION -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_INVARIANT_VIOLATION",
                        "The project graph failed an internal invariant check"));
    };
}
```

- [ ] **Step 4: Run graph tests**

```bash
./gradlew test --tests com.specagent.api.graph.GraphWorkspaceApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/specagent/api/graph backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java
git commit -m "feat: expose graph workspace API"
```

---

### Task 4: Add route-scoped Requirement State without changing active-route behavior

**Files:**
- Modify: `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryException.java`
- Modify: `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryService.java`
- Modify: `backend/src/main/java/com/specagent/api/requirement/RequirementStateController.java`
- Modify: `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java`
- Modify: `backend/src/test/java/com/specagent/api/requirement/RequirementStateApiIntegrationTest.java`

**Interfaces:**
- Preserves: `RequirementStateView getForProject(UUID projectId)` and `GET /api/v1/projects/{projectId}/requirement-state`.
- Produces: `RequirementStateView getForRoute(UUID projectId, UUID routeId)` and `GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state`.

- [ ] **Step 1: Add failing route-scoped integration tests**

Cover:

1. Active=A, read B explicitly -> response `routeId=B` and claims from B.
2. B is ARCHIVED -> still readable.
3. B is SUPERSEDED -> still readable.
4. B is DELETED -> still readable.
5. foreign route -> 404 `ROUTE_NOT_FOUND`.
6. missing route -> 404 `ROUTE_NOT_FOUND`.
7. existing active endpoint still returns A.

Example assertion:

```java
mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/requirement-state", projectId, routeBId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(projectId.toString()))
        .andExpect(jsonPath("$.routeId").value(routeBId.toString()));
```

- [ ] **Step 2: Verify tests fail**

```bash
./gradlew test --tests com.specagent.api.requirement.RequirementStateApiIntegrationTest
```

Expected: route-scoped endpoint 404 and/or missing enum reason.

- [ ] **Step 3: Add `ROUTE_NOT_FOUND` to the read-model-neutral exception**

```java
public enum Reason {
    PROJECT_NOT_FOUND,
    ROUTE_NOT_FOUND,
    INVARIANT_VIOLATION
}
```

- [ ] **Step 4: Implement `getForRoute`**

The implementation must validate ownership before deriving:

```java
public RequirementStateView getForRoute(UUID projectId, UUID routeId) {
    Project project = projectService.getProject(projectId)
            .orElseThrow(() -> RequirementStateQueryException.of(
                    RequirementStateQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));

    Route route = routeService.getRoute(routeId)
            .orElseThrow(() -> RequirementStateQueryException.of(
                    RequirementStateQueryException.Reason.ROUTE_NOT_FOUND, "Route not found"));

    if (!route.projectId().equals(project.id())) {
        throw RequirementStateQueryException.of(
                RequirementStateQueryException.Reason.ROUTE_NOT_FOUND, "Route not found");
    }

    RequirementState state = requirementStateBuilder.buildForRoute(project.id(), route.id());
    return RequirementStateView.from(project.id(), route.id(), state);
}
```

Do not gate by route lifecycle.

- [ ] **Step 5: Add a second controller method without replacing the old mapping**

Keep the existing class mapping if convenient and add a separate controller class, or refactor mappings cleanly. The final public routes must be exactly:

```text
GET /api/v1/projects/{projectId}/requirement-state
GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state
```

A valid implementation is to keep `RequirementStateController` with method-level absolute project paths only if Spring mapping remains unambiguous; prefer a second `RouteRequirementStateController` if that keeps responsibilities clearer.

- [ ] **Step 6: Map `ROUTE_NOT_FOUND`**

Extend the existing `handleRequirementStateQuery` switch:

```java
case ROUTE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorResponse.of("ROUTE_NOT_FOUND", "Route not found"));
```

- [ ] **Step 7: Run requirement-state tests**

```bash
./gradlew test --tests com.specagent.api.requirement.RequirementStateApiIntegrationTest
```

Expected: PASS, including legacy active endpoint tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/specagent/readmodel/requirement backend/src/main/java/com/specagent/api/requirement backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java backend/src/test/java/com/specagent/api/requirement/RequirementStateApiIntegrationTest.java
git commit -m "feat: add route scoped requirement state read"
```

---

### Task 5: Lock architecture boundaries and run full backend verification

**Files:**
- Inspect/modify if needed: `backend/src/test/java/com/specagent/architecture/ArchitectureTests.java`
- No production changes unless a failing test identifies a real issue.

**Interfaces:**
- Confirms the 7.3A backend can be safely consumed by 7.3B.

- [ ] **Step 1: Check whether existing ArchUnit rules already cover the new packages**

Existing generic rules must continue to pass for `com.specagent.api..` and `com.specagent.readmodel..`. If additional coverage is needed, add this explicit rule:

```java
@Test
void graphReadModelMustNotDependOnModelProviderCredentialOrApi() {
    ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.readmodel.graph..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.specagent.model..",
                    "com.specagent.credential..",
                    "com.specagent.api..")
            .because("GraphWorkspace is a read projection, not a second runtime or provider boundary");
    rule.check(CLASSES);
}
```

Do not add duplicate rules if existing generic rules already prove the same boundary and the new graph query has no extra risky dependency.

- [ ] **Step 2: Run architecture tests**

```bash
./gradlew test --tests com.specagent.architecture.ArchitectureTests
```

Expected: PASS.

- [ ] **Step 3: Run focused API suites together**

```bash
./gradlew test --tests com.specagent.api.graph.GraphWorkspaceApiIntegrationTest --tests com.specagent.api.requirement.RequirementStateApiIntegrationTest --tests com.specagent.api.route.RouteLineageApiIntegrationTest --tests com.specagent.api.route.RouteForkApiIntegrationTest --tests com.specagent.api.route.RegenerateApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 4: Run the full backend suite**

```bash
./gradlew test
```

Expected: exit code 0. Existing live-provider tests may remain skipped under the established fake-gateway test policy; there must be zero failures.

- [ ] **Step 5: Inspect the diff for forbidden scope**

Run:

```bash
git diff --stat ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f..HEAD
git diff ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f..HEAD -- backend/src/main/java
```

Confirm no changes to:

```text
model/provider/credential behavior
ContextBuilder semantics
RouteService fork/regenerate semantics
Node persistence semantics
Answer finalization semantics
SpecSnapshot source-of-truth rules
```

- [ ] **Step 6: Commit any architecture-test-only adjustment**

If Task 5 added a rule:

```bash
git add backend/src/test/java/com/specagent/architecture/ArchitectureTests.java
git commit -m "test: lock graph read model boundaries"
```

If no file changed, do not create an empty commit.

- [ ] **Step 7: Push and verify remote equality**

```bash
git status --short
git push origin main
git fetch origin
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"
```

On PowerShell, use:

```powershell
if ((git rev-parse HEAD) -ne (git rev-parse origin/main)) { throw "origin/main != HEAD" }
```

Expected:

```text
working tree clean
origin/main == HEAD
```

## Phase 7.3A Completion Report

The implementing agent must report all of the following, with actual command output summaries rather than unsupported claims:

```text
PHASE 7.3A

HEAD: <sha>
origin/main: <sha>
remote_equal: true

Graph API:
GET /api/v1/projects/{projectId}/graph

Requirement State APIs:
GET /api/v1/projects/{projectId}/requirement-state
GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state

Tests:
- GraphWorkspaceApiIntegrationTest: PASS
- RequirementStateApiIntegrationTest: PASS
- ArchitectureTests: PASS
- full backend ./gradlew test: PASS

Invariants confirmed:
- shared nodes deduplicated
- route-specific answers remain separate
- fork does not copy answers
- regenerate history semantics unchanged
- all lifecycle states inspectable
- no ContextSnapshot/model/provider/credential exposure
- no Runtime semantic changes
```
