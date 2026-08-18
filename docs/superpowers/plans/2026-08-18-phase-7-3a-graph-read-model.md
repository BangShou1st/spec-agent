# Phase 7.3A Graph Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the read-only backend data surface needed by the Phase 7.3 graph workspace without changing Runtime route/context semantics.

**Architecture:** Add a `readmodel.graph` projection that composes existing `ProjectService`, `RouteService`, `NodeService`, and `AnswerService` reads and exposes one canonical project graph endpoint. Extend requirement-state reads with an explicit route-scoped query while preserving the existing active-route endpoint. Controllers stay thin; read models never call ModelGateway, credentials, repositories directly, or `ContextBuilder`.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, existing JDBC-backed Runtime services, JUnit 5, Mockito, MockMvc, ArchUnit, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-18-phase-7-3-graph-workspace-design.md`

## Global Constraints

- Implementation baseline is an `origin/main` containing approved design commit `ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f`.
- Runtime owns history. Do not change Route/Node/Answer/Context persistence semantics.
- Active route remains `Project.activeRouteId`; active is not a lifecycle status.
- Regenerate remains deterministic; no new read path may call a model.
- Graph and route-scoped requirement-state additions are read-only UI support.
- API classes must not depend on repositories, model/provider packages, credentials, or `ContextBuilder`.
- `com.specagent.readmodel..` must not depend on `com.specagent.api..`.
- Graph responses expose safe Question/Option/Answer presentation fields only; never patches, context snapshots, prompts, provider payloads, credentials, AgentRun traces, or raw DB metadata.
- Missing/foreign/cyclic/root-mismatched lineage data must fail closed; never return a partial misleading graph.
- OPEN, SUPERSEDED, ARCHIVED, and DELETED routes are all inspectable by read endpoints.
- Existing Phase 6/7 APIs stay backward-compatible.
- Use TDD. Every task must have an independently passing test cycle before its commit.
- At completion push to `origin main`, leave the working tree clean, and verify `origin/main == HEAD`.

## File Structure

### Answer read boundary

- Modify `backend/src/main/java/com/specagent/answer/AnswerService.java` — expose the existing route+node batch answer read through the service layer.
- Create `backend/src/test/java/com/specagent/answer/AnswerServiceTest.java`.

### Graph read model

- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryException.java`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceOptionView.java`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceNodeView.java`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceAnswerView.java`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceRouteView.java`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceView.java`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryService.java`.
- Create `backend/src/main/java/com/specagent/readmodel/graph/package-info.java`.
- Create `backend/src/test/java/com/specagent/readmodel/graph/GraphWorkspaceQueryServiceTest.java`.

### Graph HTTP API

- Create `backend/src/main/java/com/specagent/api/graph/GraphWorkspaceController.java`.
- Create `backend/src/main/java/com/specagent/api/graph/package-info.java`.
- Modify `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java`.
- Create `backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java`.

### Route-scoped Requirement State

- Modify `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryException.java`.
- Modify `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryService.java`.
- Create `backend/src/main/java/com/specagent/api/requirement/RouteRequirementStateController.java`.
- Modify `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java`.
- Modify `backend/src/test/java/com/specagent/api/requirement/RequirementStateApiIntegrationTest.java`.

### Architecture verification

- Modify `backend/src/test/java/com/specagent/architecture/ArchitectureTests.java` only if a graph-specific rule adds protection beyond the existing generic API/read-model rules.

---

### Task 1: Expose route-scoped answer reads through `AnswerService`

**Files:**
- Modify: `backend/src/main/java/com/specagent/answer/AnswerService.java`
- Create: `backend/src/test/java/com/specagent/answer/AnswerServiceTest.java`

**Interfaces:**
- Consumes: `AnswerRepository.findByRouteAndNodeIds(UUID routeId, List<UUID> nodeIds)`.
- Produces: `public List<Answer> findAnswersForRouteAndNodeIds(UUID routeId, List<UUID> nodeIds)`.

- [ ] **Step 1: Write the failing service test**

```java
@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {
    @Mock AnswerRepository answerRepository;
    @InjectMocks AnswerService service;

    @Test
    void findAnswersForRouteAndNodeIdsDelegatesReadOnlyQuery() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        List<UUID> nodeIds = List.of(nodeId);
        Answer answer = new Answer(
                UUID.randomUUID(), projectId, routeId, nodeId,
                UUID.randomUUID().toString(), "free text", "user",
                Instant.parse("2026-08-18T00:00:00Z"));
        when(answerRepository.findByRouteAndNodeIds(routeId, nodeIds)).thenReturn(List.of(answer));

        assertThat(service.findAnswersForRouteAndNodeIds(routeId, nodeIds)).containsExactly(answer);
        verify(answerRepository).findByRouteAndNodeIds(routeId, nodeIds);
        verifyNoMoreInteractions(answerRepository);
    }
}
```

- [ ] **Step 2: Run the focused test and verify failure**

```bash
cd backend
./gradlew test --tests com.specagent.answer.AnswerServiceTest
```

Expected: compilation failure because `findAnswersForRouteAndNodeIds` does not exist.

- [ ] **Step 3: Implement the minimal service method**

```java
public List<Answer> findAnswersForRouteAndNodeIds(UUID routeId, List<UUID> nodeIds) {
    return answerRepository.findByRouteAndNodeIds(routeId, nodeIds);
}
```

No lifecycle logic, copying, mutation, or fallback belongs here.

- [ ] **Step 4: Re-run the focused test**

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

### Task 2: Build the canonical graph read model with pure service tests

**Files:**
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryException.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceOptionView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceNodeView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceAnswerView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceRouteView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceView.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryService.java`
- Create: `backend/src/main/java/com/specagent/readmodel/graph/package-info.java`
- Create: `backend/src/test/java/com/specagent/readmodel/graph/GraphWorkspaceQueryServiceTest.java`

**Interfaces:**
- Consumes: `ProjectService.getProject`, `RouteService.listRoutes`, `NodeService.getNode`, `AnswerService.findAnswersForRouteAndNodeIds`.
- Produces: `GraphWorkspaceView getForProject(UUID projectId)`.

Use these backend record shapes and factory signatures exactly. `NodeOption.id()` is a UUID in Java; Jackson will serialize it as the string expected by TypeScript.

```java
public record GraphWorkspaceOptionView(UUID id, String label, String impact) {
    public static GraphWorkspaceOptionView from(NodeOption option) {
        return new GraphWorkspaceOptionView(option.id(), option.label(), option.impact());
    }
}

public record GraphWorkspaceNodeView(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<GraphWorkspaceOptionView> options,
        boolean allowFreeAnswer,
        Instant createdAt) {
    public static GraphWorkspaceNodeView from(Node node) {
        return new GraphWorkspaceNodeView(
                node.id(), node.projectId(), node.parentNodeId(), node.supersedesNodeId(),
                node.question(), node.purpose(),
                node.options().stream().map(GraphWorkspaceOptionView::from).toList(),
                node.allowFreeAnswer(), node.createdAt());
    }
}

public record GraphWorkspaceAnswerView(
        UUID id,
        UUID routeId,
        UUID nodeId,
        String selectedOptionId,
        String freeText,
        Instant createdAt) {
    public static GraphWorkspaceAnswerView from(Answer answer) {
        return new GraphWorkspaceAnswerView(
                answer.id(), answer.routeId(), answer.nodeId(),
                answer.selectedOptionId(), answer.freeText(), answer.createdAt());
    }
}

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
        List<UUID> lineageNodeIds) {
    public static GraphWorkspaceRouteView from(
            Route route, UUID activeRouteId, List<UUID> lineageNodeIds) {
        return new GraphWorkspaceRouteView(
                route.id(), route.label(), route.lifecycleStatus().code(),
                route.isActive(activeRouteId), route.rootNodeId(), route.tipNodeId(),
                route.createdFromNodeId(), route.supersedesRouteId(),
                route.replacementOfNodeId(), List.copyOf(lineageNodeIds));
    }
}

public record GraphWorkspaceView(
        UUID projectId,
        UUID activeRouteId,
        List<GraphWorkspaceRouteView> routes,
        List<GraphWorkspaceNodeView> nodes,
        List<GraphWorkspaceAnswerView> answers) {}
```

`GraphWorkspaceQueryException.Reason` is closed:

```java
public enum Reason {
    PROJECT_NOT_FOUND,
    INVARIANT_VIOLATION
}
```

- [ ] **Step 1: Write the failing happy-path read-model test**

Use actual domain objects and mocked services. A minimal fixture shape:

```java
UUID projectId = UUID.randomUUID();
UUID routeId = UUID.randomUUID();
UUID rootId = UUID.randomUUID();
UUID childId = UUID.randomUUID();
Instant now = Instant.parse("2026-08-18T00:00:00Z");

Project project = new Project(projectId, "p", routeId, null, now, now);
Node root = new Node(rootId, projectId, null, null, null,
        "Q1", "P1", List.of(NodeOption.of("A", "impact")), true, now);
Node child = new Node(childId, projectId, rootId, null, null,
        "Q2", "P2", List.of(), true, now);
Route route = new Route(routeId, projectId, rootId, childId,
        RouteLifecycleStatus.OPEN, "Initial", null, null, null, null, now, now);
Answer answer = new Answer(UUID.randomUUID(), projectId, routeId, rootId,
        root.options().get(0).id().toString(), "answer", "user", now);
```

Stub services and assert:

```java
GraphWorkspaceView view = service.getForProject(projectId);
assertThat(view.projectId()).isEqualTo(projectId);
assertThat(view.activeRouteId()).isEqualTo(routeId);
assertThat(view.routes()).singleElement()
        .satisfies(r -> assertThat(r.lineageNodeIds()).containsExactly(rootId, childId));
assertThat(view.nodes()).extracting(GraphWorkspaceNodeView::id)
        .containsExactly(rootId, childId);
assertThat(view.answers()).singleElement()
        .satisfies(a -> {
            assertThat(a.routeId()).isEqualTo(routeId);
            assertThat(a.nodeId()).isEqualTo(rootId);
        });
```

- [ ] **Step 2: Run the read-model test and verify failure**

```bash
./gradlew test --tests com.specagent.readmodel.graph.GraphWorkspaceQueryServiceTest
```

Expected: compilation failure because graph read-model classes do not exist.

- [ ] **Step 3: Implement fail-closed lineage resolution**

For each `routeService.listRoutes(projectId)` route:

1. If `tipNodeId == null`, require `rootNodeId == null` and return an empty lineage.
2. Otherwise walk tip -> `parentNodeId` using a `Set<UUID>` and max depth `10_000`.
3. Every node must resolve and belong to `projectId`.
4. Reverse the chain to root->tip.
5. Require `rootNodeId != null` and equality with the first resolved node.
6. Deduplicate global nodes with `LinkedHashMap<UUID, Node>`.
7. Read answers with `answerService.findAnswersForRouteAndNodeIds(route.id(), lineageNodeIds)`.
8. Keep each answer separate; never merge answers by node ID.
9. Never use `supersedesNodeId` to compute lineage.

Core production shape:

```java
Map<UUID, Node> nodesById = new LinkedHashMap<>();
List<GraphWorkspaceRouteView> routeViews = new ArrayList<>();
List<GraphWorkspaceAnswerView> answerViews = new ArrayList<>();

for (Route route : routeService.listRoutes(projectId)) {
    List<Node> lineage = resolveLineage(project.id(), route);
    List<UUID> lineageNodeIds = lineage.stream().map(Node::id).toList();
    lineage.forEach(node -> nodesById.putIfAbsent(node.id(), node));
    answerService.findAnswersForRouteAndNodeIds(route.id(), lineageNodeIds)
            .stream().map(GraphWorkspaceAnswerView::from).forEach(answerViews::add);
    routeViews.add(GraphWorkspaceRouteView.from(route, project.activeRouteId(), lineageNodeIds));
}

return new GraphWorkspaceView(
        project.id(), project.activeRouteId(), List.copyOf(routeViews),
        nodesById.values().stream().map(GraphWorkspaceNodeView::from).toList(),
        List.copyOf(answerViews));
```

- [ ] **Step 4: Add shared-node and route-specific-answer tests**

Build:

```text
Route A: A -> B -> C
Route B: A -> B -> D
```

with a distinct answer for node B on each route. Assert:

```text
nodes = A,B,C,D exactly once each
Route A lineageNodeIds = A,B,C
Route B lineageNodeIds = A,B,D
answers contains two B answers with different routeId values
```

- [ ] **Step 5: Add lifecycle and replacement tests**

Use routes in OPEN/SUPERSEDED/ARCHIVED/DELETED and assert all are returned. Assert `isActive` only follows `Project.activeRouteId`.

For replacement data, assert the replacement route lineage is parent lineage + replacement node, while `replacementOfNodeId`/`supersedesNodeId` are metadata only and do not inject the old target into the replacement lineage.

- [ ] **Step 6: Add fail-closed service tests**

Separate tests must assert `GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION` for:

```text
route tip references a missing node
resolved node belongs to another project
parent chain contains a cycle
route root does not match resolved root
route has null tip but non-null root
lineage exceeds defensive max depth where practical to exercise without huge fixture; otherwise keep the max-depth guard in code and cover cycle/root/missing/foreign exhaustively
```

- [ ] **Step 7: Run the full read-model test class**

```bash
./gradlew test --tests com.specagent.readmodel.graph.GraphWorkspaceQueryServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/specagent/readmodel/graph backend/src/test/java/com/specagent/readmodel/graph/GraphWorkspaceQueryServiceTest.java
git commit -m "feat: add canonical graph workspace read model"
```

---

### Task 3: Expose the graph endpoint and map failures at the HTTP edge

**Files:**
- Create: `backend/src/main/java/com/specagent/api/graph/GraphWorkspaceController.java`
- Create: `backend/src/main/java/com/specagent/api/graph/package-info.java`
- Modify: `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java`
- Create: `backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java`

**Interfaces:**
- Consumes: `GraphWorkspaceQueryService.getForProject(UUID)`.
- Produces: `GET /api/v1/projects/{projectId}/graph -> GraphWorkspaceView`.

- [ ] **Step 1: Write the failing HTTP integration tests**

At minimum cover:

```java
mockMvc.perform(get("/api/v1/projects/{projectId}/graph", projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(projectId.toString()))
        .andExpect(jsonPath("$.routes[0].lineageNodeIds[0]").value(rootNodeId.toString()))
        .andExpect(jsonPath("$.nodes[0].id").value(rootNodeId.toString()));
```

Also cover one route-specific answer payload:

```java
.andExpect(jsonPath("$.answers[0].routeId").value(routeId.toString()))
.andExpect(jsonPath("$.answers[0].nodeId").value(rootNodeId.toString()))
.andExpect(jsonPath("$.answers[0].selectedOptionId").value(optionId.toString()))
.andExpect(jsonPath("$.answers[0].freeText").value("answer text"));
```

And missing project:

```java
mockMvc.perform(get("/api/v1/projects/{projectId}/graph", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
```

Reuse the corruption setup patterns from `RouteLineageApiIntegrationTest` to verify graph corruption returns 500 `INTERNAL_INVARIANT_VIOLATION` rather than a partial graph.

- [ ] **Step 2: Run and verify endpoint failure**

```bash
./gradlew test --tests com.specagent.api.graph.GraphWorkspaceApiIntegrationTest
```

Expected: 404/compilation failure because the endpoint does not exist.

- [ ] **Step 3: Implement the thin controller**

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

- [ ] **Step 4: Map graph exceptions in `ApiExceptionHandler`**

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

- [ ] **Step 5: Run graph API integration tests**

```bash
./gradlew test --tests com.specagent.api.graph.GraphWorkspaceApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/specagent/api/graph backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java backend/src/test/java/com/specagent/api/graph/GraphWorkspaceApiIntegrationTest.java
git commit -m "feat: expose graph workspace API"
```

---

### Task 4: Add route-scoped Requirement State while preserving the active endpoint

**Files:**
- Modify: `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryException.java`
- Modify: `backend/src/main/java/com/specagent/readmodel/requirement/RequirementStateQueryService.java`
- Create: `backend/src/main/java/com/specagent/api/requirement/RouteRequirementStateController.java`
- Modify: `backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java`
- Modify: `backend/src/test/java/com/specagent/api/requirement/RequirementStateApiIntegrationTest.java`

**Interfaces:**
- Preserves: `RequirementStateView getForProject(UUID projectId)` and `GET /api/v1/projects/{projectId}/requirement-state`.
- Produces: `RequirementStateView getForRoute(UUID projectId, UUID routeId)` and `GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state`.

- [ ] **Step 1: Add failing route-scoped integration tests**

Cover exactly:

```text
Active=A, explicitly read B -> response routeId=B and B claims
B archived -> readable
B superseded -> readable
B deleted -> readable
missing route -> 404 ROUTE_NOT_FOUND
foreign route -> 404 ROUTE_NOT_FOUND
legacy active endpoint still returns A
```

Example:

```java
mockMvc.perform(get(
        "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
        projectId, routeBId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(projectId.toString()))
        .andExpect(jsonPath("$.routeId").value(routeBId.toString()));
```

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew test --tests com.specagent.api.requirement.RequirementStateApiIntegrationTest
```

Expected: route-scoped requests are not yet served.

- [ ] **Step 3: Add `ROUTE_NOT_FOUND` to the neutral read-model exception**

```java
public enum Reason {
    PROJECT_NOT_FOUND,
    ROUTE_NOT_FOUND,
    INVARIANT_VIOLATION
}
```

- [ ] **Step 4: Implement `getForRoute`**

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

Do not reject non-OPEN lifecycle states for this read.

- [ ] **Step 5: Add a dedicated route-scoped controller**

```java
@RestController
@RequestMapping("/api/v1/projects/{projectId}/routes/{routeId}/requirement-state")
public class RouteRequirementStateController {
    private final RequirementStateQueryService queryService;

    public RouteRequirementStateController(RequirementStateQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public RequirementStateView getRequirementState(
            @PathVariable UUID projectId,
            @PathVariable UUID routeId) {
        return queryService.getForRoute(projectId, routeId);
    }
}
```

Leave the existing `RequirementStateController` active-route mapping unchanged.

- [ ] **Step 6: Map `ROUTE_NOT_FOUND`**

Extend `handleRequirementStateQuery`:

```java
case ROUTE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorResponse.of("ROUTE_NOT_FOUND", "Route not found"));
```

- [ ] **Step 7: Re-run requirement-state integration tests**

```bash
./gradlew test --tests com.specagent.api.requirement.RequirementStateApiIntegrationTest
```

Expected: PASS, including old endpoint cases.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/specagent/readmodel/requirement backend/src/main/java/com/specagent/api/requirement backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java backend/src/test/java/com/specagent/api/requirement/RequirementStateApiIntegrationTest.java
git commit -m "feat: add route scoped requirement state read"
```

---

### Task 5: Lock architecture boundaries and verify the full backend

**Files:**
- Inspect: `backend/src/test/java/com/specagent/architecture/ArchitectureTests.java`
- Modify that file only if the graph package needs an explicit extra rule.

**Interfaces:**
- Produces a verified backend contract ready for Phase 7.3B.

- [ ] **Step 1: Run existing ArchUnit rules before adding anything**

```bash
./gradlew test --tests com.specagent.architecture.ArchitectureTests
```

Expected: PASS. Existing rules already forbid API->Repository, API->model/context/credential, Runtime->API, and readmodel->API dependencies.

- [ ] **Step 2: Add one graph-specific rule only if it proves an additional boundary**

If the implementation accidentally introduces graph read-model dependency on provider/credential packages not already covered, add:

```java
@Test
void graphReadModelMustNotDependOnModelProviderOrCredential() {
    ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.readmodel.graph..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.credential..")
            .because("GraphWorkspace is a read projection, not a model/provider boundary");
    rule.check(CLASSES);
}
```

Do not duplicate an existing rule without added protection.

- [ ] **Step 3: Run all 7.3A focused tests together**

```bash
./gradlew test \
  --tests com.specagent.answer.AnswerServiceTest \
  --tests com.specagent.readmodel.graph.GraphWorkspaceQueryServiceTest \
  --tests com.specagent.api.graph.GraphWorkspaceApiIntegrationTest \
  --tests com.specagent.api.requirement.RequirementStateApiIntegrationTest \
  --tests com.specagent.api.route.RouteLineageApiIntegrationTest \
  --tests com.specagent.api.route.RouteForkApiIntegrationTest \
  --tests com.specagent.api.route.RegenerateApiIntegrationTest \
  --tests com.specagent.architecture.ArchitectureTests
```

Expected: PASS.

- [ ] **Step 4: Run the full backend suite**

```bash
./gradlew test
```

Expected: exit 0 with zero failures. Established live-provider skips remain acceptable under fake-gateway verification.

- [ ] **Step 5: Inspect scope against the approved design baseline**

```bash
cd ..
git diff --stat ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f..HEAD
git diff ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f..HEAD -- backend/src/main/java
```

Confirm there are no changes to:

```text
RouteService fork/regenerate semantics
ContextBuilder/context snapshot semantics
Node persistence semantics
Answer finalization semantics
model/provider/credential behavior
SpecSnapshot source-of-truth rules
```

- [ ] **Step 6: Commit an architecture-test-only change if one exists**

```bash
git add backend/src/test/java/com/specagent/architecture/ArchitectureTests.java
git commit -m "test: lock graph read model boundaries"
```

Skip this commit when `ArchitectureTests.java` is unchanged; never create an empty commit.

- [ ] **Step 7: Push and verify remote equality**

```bash
git status --short
git push origin main
git fetch origin
git rev-parse HEAD
git rev-parse origin/main
```

`git status --short` must be empty, and the two printed SHAs must be identical.

## Phase 7.3A Completion Report

Report actual command results in this exact semantic form; do not invent counts:

```text
PHASE 7.3A

HEAD: output of git rev-parse HEAD
origin/main: output of git rev-parse origin/main
remote_equal: true only when the two outputs are identical
working_tree_clean: true only when git status --short is empty

Graph API:
GET /api/v1/projects/{projectId}/graph

Requirement State APIs:
GET /api/v1/projects/{projectId}/requirement-state
GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state

Tests:
- AnswerServiceTest: PASS
- GraphWorkspaceQueryServiceTest: PASS
- GraphWorkspaceApiIntegrationTest: PASS
- RequirementStateApiIntegrationTest: PASS
- ArchitectureTests: PASS
- full ./gradlew test: PASS with actual test/skip counts

Invariants:
- shared nodes deduplicated: PASS
- route-specific answers remain separate: PASS
- fork answer-copy behavior unchanged: PASS
- regenerate lineage/replacement semantics unchanged: PASS
- all lifecycle states inspectable: PASS
- no ContextSnapshot/model/provider/credential exposure: PASS
- no Runtime semantic changes: PASS
```
