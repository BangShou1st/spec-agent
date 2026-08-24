package com.specagent.agent;

import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.common.Json;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import com.specagent.route.RegenerateResult;
import com.specagent.readmodel.requirement.RequirementStateQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Route isolation at the model-facing envelope level, exercised through the
 * async AgentRun surface. The spy delegates to the real deterministic engine
 * and captures every {@link AgentRequestEnvelope}: after forking or archiving,
 * the active route's envelopes must exclude sibling sentinels, superseded ids,
 * and any record outside the frozen context.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScriptedRouteIsolationIntegrationTest {

    private static final String SIBLING_SENTINEL = "SCRIPTED_SIBLING_SENTINEL_7c1f";
    private static final String OLD_ANSWER_SENTINEL = "OLD_ANSWER_SENTINEL_2e8a";
    private static final String REGEN_INSTRUCTION = "Regenerate: make it sharper";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private Json json;
    @Autowired
    private ContextBuilder contextBuilder;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private final List<AgentRequestEnvelope> captured = new ArrayList<>();

    @BeforeEach
    void captureModelRequests() {
        captured.clear();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runStateUpdate(any(AgentRequestEnvelope.class));
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runDecision(any(AgentRequestEnvelope.class));
    }

    /** Flattened envelope projection used for exclusion assertions. */
    private String envelopeText(AgentRequestEnvelope envelope) {
        StringBuilder combined = new StringBuilder();
        combined.append("route:").append(envelope.snapshot().routeId()).append('\n');
        if (envelope.event() != null && envelope.event().freeText() != null) {
            combined.append(envelope.event().freeText()).append('\n');
        }
        for (var entry : envelope.snapshot().lineage()) {
            if (entry.node() != null) {
                combined.append("node:").append(entry.node().id()).append('\n');
                var body = entry.node().body();
                if (body != null && body.text() != null) {
                    combined.append(body.text()).append('\n');
                }
            }
            if (entry.answer() != null) {
                combined.append("answer:").append(entry.answer().id()).append('\n');
                if (entry.answer().freeText() != null) {
                    combined.append(entry.answer().freeText()).append('\n');
                }
            }
            if (entry.patches() != null) {
                for (var patch : entry.patches()) {
                    combined.append("patch:").append(patch.id()).append('\n');
                }
            }
        }
        return combined.toString();
    }

    @Test
    void forkActiveRouteEnvelopesExcludeSiblingSentinelAndSupersededIds() {
        Project project = projectService.createProject("Fork isolation");

        // Route R1: root -> A -> A2. The answer on A carries the sentinel that
        // must never reach the fork route's model input.
        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        var rootRun = answerDriver.submitFreeText(project.id(), "First answer on R1 root");
        Node a = nodeService.getNode(rootRun.producedNodeId()).orElseThrow();
        var siblingRun = answerDriver.submitFreeText(project.id(),
                SIBLING_SENTINEL + " the sibling branch answer");
        Node a2 = nodeService.getNode(siblingRun.producedNodeId()).orElseThrow();
        UUID r1RouteId = rootRun.run().routeId();

        // Fork from the root: a new active route R2 whose context is only the
        // shared root lineage; R1 nodes, answers, and patches are excluded.
        Route fork = routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork at root");
        UUID r2RouteId = fork.id();

        int forkPoint = captured.size();
        var forkRun = answerDriver.submitFreeText(project.id(),
                "Fork branch answer that stays local to R2");
        Node b = nodeService.getNode(forkRun.producedNodeId()).orElseThrow();
        assertThat(b.parentNodeId()).isEqualTo(root.id());

        // Envelope-level isolation: fork envelopes may see the frozen R1 root
        // prefix, but never the sibling-only nodes or sentinel.
        List<AgentRequestEnvelope> forkEnvelopes =
                captured.subList(forkPoint, captured.size());
        assertThat(forkEnvelopes).isNotEmpty();

        for (AgentRequestEnvelope envelope : forkEnvelopes) {
            assertThat(envelope.snapshot().routeId())
                    .as("fork envelopes target the active fork route")
                    .isEqualTo(r2RouteId);
            String text = envelopeText(envelope);
            assertThat(text)
                    .as("fork envelope must exclude sibling content")
                    .doesNotContain(SIBLING_SENTINEL)
                    .doesNotContain("node:" + a.id())
                    .doesNotContain("node:" + a2.id());
            assertThat(envelope.snapshot().lineage())
                    .allSatisfy(entry -> {
                        if (entry.node() != null) {
                            assertThat(entry.node().id())
                                    .isNotEqualTo(a.id())
                                    .isNotEqualTo(a2.id());
                        }
                        if (entry.answer() != null) {
                            assertThat(entry.answer().freeText())
                                    .doesNotContain(SIBLING_SENTINEL);
                        }
                    });
        }

        // The shared root answer stays present in the fork lineage.
        assertThat(forkEnvelopes).anySatisfy(envelope -> {
            boolean found = envelope.snapshot().lineage().stream()
                    .anyMatch(entry -> entry.answer() != null
                            && rootRun.answerId().equals(entry.answer().id()));
            assertThat(found).as("shared root answer stays in fork lineage").isTrue();
        });
    }

    @Test
    void regenerateProjectionExcludesOldAnswerPatchAndChildSubtree() {
        Project project = projectService.createProject("Regenerate isolation");

        // Route: root answered, then A answered (its answer and patch carry the
        // sentinel), which also produced A's child node.
        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        answerDriver.submitFreeText(project.id(), "Root answer stays");
        var targetRun = answerDriver.submitFreeText(project.id(),
                OLD_ANSWER_SENTINEL + " the replaced answer");
        // The answered node is the run's recorded input node (the tip at submit time).
        Node target = nodeService.getNode(targetRun.run().inputNodeId()).orElseThrow();
        Node child = nodeService.getNode(targetRun.producedNodeId()).orElseThrow();

        RegenerateResult committed = routeService.commitReplacementFromNode(
                project.id(), targetRun.run().routeId(), target.id(), null,
                "A sharper replacement question", "A sharper purpose",
                List.of(NodeOption.of("Option label", "Option impact")), true);
        ContextSnapshot context = contextBuilder.buildForRegenerate(
                project.id(), targetRun.run().routeId(), target.id(),
                committed.replacementRoute().id(), committed.replacementNode().id(),
                REGEN_INSTRUCTION);
        RegenerateResult regen = new RegenerateResult(
                committed.oldRoute(), committed.replacementRoute(),
                committed.replacementNode(), context);

        // The frozen regenerate context carries only the shared parent lineage,
        // old question text and the user instruction.
        assertThat(regen.contextSnapshot().includedNodeIds())
                .contains(root.id())
                .doesNotContain(target.id())
                .doesNotContain(child.id());
        assertThat(regen.contextSnapshot().includedAnswerIds())
                .doesNotContain(targetRun.answerId());
        assertThat(regen.contextSnapshot().includedPatchIds())
                .doesNotContain(targetRun.patchId());

        // The regenerated route is open, active, and points at a fresh node.
        Route replacement = routeService.getRoute(
                projectService.getProject(project.id()).orElseThrow().activeRouteId()).orElseThrow();
        assertThat(replacement.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
    }

    @Test
    void archivedSiblingRouteStaysExcludedFromActiveEnvelopes() {
        Project project = projectService.createProject("Archived route exclusion");

        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        var r1Run = answerDriver.submitFreeText(project.id(), "R1 archived content");
        Node a = nodeService.getNode(r1Run.producedNodeId()).orElseThrow();
        UUID r1RouteId = r1Run.run().routeId();

        Route fork = routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork at root");
        routeService.archiveRoute(project.id(), r1RouteId);

        int forkPoint = captured.size();
        var activeRun = answerDriver.submitFreeText(project.id(),
                "Active branch keeps working after archiving sibling");
        Node b = nodeService.getNode(activeRun.producedNodeId()).orElseThrow();
        assertThat(b.parentNodeId()).isEqualTo(root.id());

        for (AgentRequestEnvelope envelope : captured.subList(forkPoint, captured.size())) {
            assertThat(envelope.snapshot().routeId()).isEqualTo(fork.id());
            assertThat(envelope.snapshot().lineage())
                    .allSatisfy(entry -> {
                        if (entry.node() != null) {
                            assertThat(entry.node().id()).isNotEqualTo(a.id());
                        }
                    });
        }
    }
}
