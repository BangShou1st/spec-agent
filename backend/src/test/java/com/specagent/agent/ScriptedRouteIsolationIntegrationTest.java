package com.specagent.agent;

import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.gates.SpecSourceReferenceGuard;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RegenerateResult;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.testing.FakeModelAdapter;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Envelope-level route isolation, zero public network.
 *
 * <p>The orchestrator builds one {@code ModelRequest.inputJson} per model call
 * from a frozen context snapshot. These tests capture every request it sends
 * (through a spy that delegates to the real deterministic fake) and prove the
 * isolation at the envelope level: sibling route content, superseded
 * answers/patches, and child subtrees never reach the model input, while the
 * shared parent lineage and the run-local answer do.
 *
 * <p>Rejected-spec behavior (source ref outside the frozen context -> run
 * FAILED, no snapshot persisted, route tip untouched) is already covered by
 * {@code FakeFullLoopFailureIntegrationTest} and
 * {@code SpecSourceReferenceGuardTest}; this class covers the projection layer
 * those tests do not reach.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScriptedRouteIsolationIntegrationTest {

    private static final String SIBLING_SENTINEL = "SIBLING_SENTINEL_DO_NOT_LEAK_7f3a";
    private static final String OLD_ANSWER_SENTINEL = "OLD_ANSWER_DO_NOT_LEAK_2b9c";
    private static final String REGEN_INSTRUCTION = "Make it more specific about operators";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private ModelContextProjectionBuilder projectionBuilder;
    @Autowired
    private SpecSourceReferenceGuard specSourceReferenceGuard;

    @SpyBean
    private FakeModelAdapter fakeModelAdapter;

    private final List<ModelRequest> captured = new ArrayList<>();

    @BeforeEach
    void captureModelRequests() {
        captured.clear();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(fakeModelAdapter).run(any(ModelRequest.class));
    }

    @Test
    void forkActiveRouteRequestsExcludeSiblingSentinelAndSupersededIds() {
        Project project = projectService.createProject("Fork isolation");

        // Route R1: root -> A -> A2. The answer on A carries the sentinel that
        // must never reach the fork route's model input.
        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        FakeAnswerRunResult rootRun = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), "First answer on R1 root");
        Node a = rootRun.producedNode();
        FakeAnswerRunResult siblingRun = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), SIBLING_SENTINEL + " the sibling branch answer");
        Node a2 = siblingRun.producedNode();
        UUID r1RouteId = rootRun.run().routeId();

        // Fork from the root: a new active route R2 whose context is only the
        // shared root lineage; R1 nodes, answers, and patches are excluded.
        Route fork = routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork at root");
        UUID r2RouteId = fork.id();

        int forkPoint = captured.size();
        Node b = orchestrator.answerActiveNodeAndDraftNext(project.id(),
                "Fork branch answer that stays local to R2")
                .producedNode();
        assertThat(b.parentNodeId()).isEqualTo(root.id());

        // Envelope-level isolation: fork requests may see the frozen R1 root
        // prefix, but never the sibling-only nodes or sentinel.
        List<ModelRequest> forkRequests = captured.subList(forkPoint, captured.size());
        assertThat(forkRequests).isNotEmpty();
        for (ModelRequest request : forkRequests) {
            assertThat(request.inputJson())
                    .as("fork request for task %s must exclude sibling content", request.taskType())
                    .doesNotContain(SIBLING_SENTINEL)
                    .doesNotContain("node:" + a.id())
                    .doesNotContain("node:" + a2.id())
                    .doesNotContain("route:" + r1RouteId);
        }
        assertThat(forkRequests).allSatisfy(request -> assertThat(request.inputJson())
                .contains("answer:" + rootRun.answer().id())
                .contains("patch:" + rootRun.patch().id()));

        // Spec on the fork route: every source ref stays inside the frozen
        // context of R2 and never points at R1 records.
        FakeSpecRunResult specResult = orchestrator.generateSpec(project.id());
        SpecSnapshot snapshot = specResult.specSnapshot();
        assertThat(snapshot.routeId()).isEqualTo(r2RouteId);
        Set<UUID> allowed = new HashSet<>();
        allowed.add(specResult.contextSnapshot().id());
        allowed.add(r2RouteId);
        allowed.addAll(specResult.contextSnapshot().includedNodeIds());
        allowed.addAll(specResult.contextSnapshot().includedAnswerIds());
        allowed.addAll(specResult.contextSnapshot().includedPatchIds());
        for (SourceReference ref : snapshot.sourceRefs()) {
            assertThat(allowed)
                    .as("source ref %s:%s must point inside the fork context", ref.kind(), ref.refId())
                    .contains(ref.refId());
        }
        ReflectionResult guard = specSourceReferenceGuard.validate(
                project.id(), r2RouteId, specResult.contextSnapshot(), snapshot.sourceRefs());
        assertThat(guard.accepted()).isTrue();
    }

    @Test
    void regenerateProjectionExcludesOldAnswerPatchAndChildSubtree() {
        Project project = projectService.createProject("Regenerate isolation");

        // Route: root answered, then A answered (its answer and patch carry the
        // sentinel), which also produced A's child node.
        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Root answer stays");
        FakeAnswerRunResult targetRun = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), OLD_ANSWER_SENTINEL + " the replaced answer");
        Node target = nodeService.getNode(targetRun.answer().nodeId()).orElseThrow();
        Node child = targetRun.producedNode();

        RegenerateResult regen = routeService.regenerateFromNode(
                project.id(), targetRun.run().routeId(), target.id(), REGEN_INSTRUCTION,
                "A sharper replacement question", "A sharper purpose",
                List.of(NodeOption.of("Option label", "Option impact")));

        // The frozen regenerate context carries only the shared parent lineage,
        // old question text and the user instruction.
        assertThat(regen.contextSnapshot().includedNodeIds())
                .contains(root.id())
                .doesNotContain(target.id())
                .doesNotContain(child.id());
        assertThat(regen.contextSnapshot().includedAnswerIds())
                .doesNotContain(targetRun.answer().id());
        assertThat(regen.contextSnapshot().includedPatchIds())
                .doesNotContain(targetRun.patch().id());

        String inputJson = projectionBuilder.buildInputJson(
                regen.contextSnapshot(), projectionBuilder.initialNodeTaskInput());
        assertThat(inputJson)
                .contains(target.question())
                .contains(REGEN_INSTRUCTION)
                .doesNotContain(OLD_ANSWER_SENTINEL)
                .doesNotContain("node:" + target.id())
                .doesNotContain("node:" + child.id())
                .doesNotContain(targetRun.answer().id().toString())
                .doesNotContain(targetRun.patch().id().toString());
    }

    @Test
    void archivedSiblingRouteStaysExcludedFromActiveProjection() {
        Project project = projectService.createProject("Archived route exclusion");

        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        FakeAnswerRunResult r1Run = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), "R1 archived content");
        Node a = r1Run.producedNode();
        UUID r1RouteId = r1Run.run().routeId();

        routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork at root");
        routeService.archiveRoute(project.id(), r1RouteId);

        int forkPoint = captured.size();
        Node b = orchestrator.answerActiveNodeAndDraftNext(project.id(),
                "Active branch keeps working after archiving sibling")
                .producedNode();
        assertThat(b.parentNodeId()).isEqualTo(root.id());

        for (ModelRequest request : captured.subList(forkPoint, captured.size())) {
            assertThat(request.inputJson())
                    .as("request for task %s must exclude archived route", request.taskType())
                    .doesNotContain("route:" + r1RouteId)
                    .doesNotContain("node:" + a.id());
        }
    }
}
