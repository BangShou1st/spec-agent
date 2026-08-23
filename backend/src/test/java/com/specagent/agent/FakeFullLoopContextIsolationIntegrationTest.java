package com.specagent.agent;

import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RegenerateResult;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the fake full loop never leaks context from sibling or
 * superseded routes: context is lineage, not global chat history.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FakeFullLoopContextIsolationIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private ContextBuilder contextBuilder;

    @Test
    void fakeAnswerLoopContextExcludesSiblingRoutePatch() {
        Project project = projectService.createProject("Sibling isolation project");
        UUID originalRouteId = project.activeRouteId();
        AgentRun firstDraft = draftDriver.draftQuestion(project.id());
        UUID node1 = firstDraft.producedNodeId();
        var firstAnswer = answerDriver.submitFreeText(project.id(), "main route answer");
        assertThat(firstAnswer.run().status()).isEqualTo(AgentRunStatus.COMPLETED);

        // Fork a sibling route from node1; the fork becomes active.
        Route forkRoute = routeService.forkFromNode(project.id(), originalRouteId, node1, "sibling route");

        // Create answer + patch on the sibling route only.
        Node siblingNode = nodeService.createChildNode(project.id(), forkRoute.id(), node1,
                "Sibling question?", "sibling purpose", List.of(), true);
        Answer siblingAnswer = answerService.finalizeAnswer(
                project.id(), forkRoute.id(), siblingNode.id(), null, "sibling answer", "user");
        AnswerPatch siblingPatch = answerPatchService.save(
                project.id(), forkRoute.id(), siblingNode.id(), siblingAnswer.id(),
                List.of(Claim.of(ClaimKind.OTHER, "sibling claim", ClaimStatus.CONFIRMED,
                        siblingNode.id(), siblingAnswer.id())),
                null);

        // Switch back to the original route and run the answer loop there.
        routeService.setActiveRoute(project.id(), originalRouteId);
        var result = answerDriver.submitFreeText(project.id(), "main route answer");
        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);

        ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                project.id(), result.run().id(), ContextOperationType.NORMAL);
        assertThat(context.includedAnswerIds()).doesNotContain(siblingAnswer.id());
        assertThat(context.includedPatchIds()).doesNotContain(siblingPatch.id());
    }

    @Test
    void fakeSpecContextExcludesSupersededRouteContent() {
        Project project = projectService.createProject("Superseded isolation project");
        AgentRun first = draftDriver.draftQuestion(project.id());
        AgentRun second = draftDriver.draftQuestion(project.id());
        UUID node1 = first.producedNodeId();
        UUID node2 = second.producedNodeId();

        // Answer node2, patch it, and extend with a next node.
        var answered = answerDriver.submitFreeText(project.id(), "clarified");
        UUID node3 = answered.producedNodeId();
        UUID answeredAnswerId = answered.answerId();

        // Regenerate node2: old route SUPERSEDED, replacement route active.
        RegenerateResult regenerated = routeService.regenerateFromNode(
                project.id(), first.routeId(), node2, "make it clearer",
                "What is the clarified outcome?", "clarifies", List.of());
        UUID replacementRouteId = regenerated.replacementRoute().id();
        UUID replacementNodeId = regenerated.replacementNode().id();

        assertThat(regenerated.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(replacementRouteId);

        // Spec run context must only cover the active OPEN route's lineage.
        FakeSpecRunResult specResult = fakeAgentOrchestrator.generateSpec(project.id());

        assertThat(specResult.contextSnapshot().routeId()).isEqualTo(replacementRouteId);
        assertThat(specResult.contextSnapshot().tipNodeId()).isEqualTo(replacementNodeId);
        assertThat(specResult.contextSnapshot().includedNodeIds()).contains(node1, replacementNodeId);
        assertThat(specResult.contextSnapshot().includedNodeIds()).doesNotContain(node2, node3);
        assertThat(specResult.contextSnapshot().includedAnswerIds()).doesNotContain(answeredAnswerId);
        assertThat(specResult.specSnapshot().routeId()).isEqualTo(replacementRouteId);
    }
}
