package com.specagent.agent;

import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
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
 * Answer repair/resume integration tests through the async ANSWER_CYCLE: a
 * failed answer run leaves the immutable answer persisted, and the
 * RESUME_ANSWER retry resumes from that existing answer without finalizing a
 * second one. The semantic replay guarantee (the resumed envelope is rebuilt
 * from the persisted Answer) is covered by
 * {@code AnswerResumeSemanticReplayIntegrationTest}; this suite covers the
 * durable-artifact invariants and ownership checks.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FakeAnswerRepairIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private AnswerCycleTestDriver answerDriver;

    @Test
    void resumeCompletesCycleWithSingleAnswerAndSinglePatch() {
        Project project = projectService.createProject("Repair project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the most important outcome?", null, List.of(), true);

        // Phase 1: simulate a cycle that persisted the Answer but failed
        // before completing — the answered node is still the active tip.
        Answer persisted = answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), root.id(), null, "clarified", "user");
        UUID originalNodeId = root.id();
        UUID answerId = persisted.id();

        // Phase 2: resume from the persisted answer (the repair path). The
        // patch checkpoint is reused; no second Answer may appear.
        var repaired = answerDriver.resumeAnswer(project.id(), answerId);

        assertThat(repaired.run().status()).isEqualTo(AgentRunStatus.COMPLETED);

        // No second answer was created.
        assertThat(answerRepository.findByRouteAndNodeIds(project.activeRouteId(), List.of(originalNodeId)))
                .hasSize(1);

        // The patch checkpoint stayed single: exactly one patch for the answer.
        List<AnswerPatch> patches = answerPatchService.findByRoute(project.activeRouteId());
        assertThat(patches).hasSize(1);
        AnswerPatch patch = patches.get(0);
        assertThat(patch.sourceNodeId()).isEqualTo(originalNodeId);
        assertThat(patch.sourceAnswerId()).isEqualTo(answerId);
        Claim confirmed = patch.claims().stream().filter(Claim::isConfirmed).findFirst().orElseThrow();
        assertThat(confirmed.sourceNodeId()).isEqualTo(originalNodeId);
        assertThat(confirmed.sourceAnswerId()).isEqualTo(answerId);

        // Repair run completed and recorded all produced ids.
        assertThat(repaired.run().producedAnswerId()).isEqualTo(answerId);
        assertThat(repaired.run().producedPatchId()).isEqualTo(patch.id());
        Route finalRoute = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(finalRoute.tipNodeId()).isEqualTo(repaired.run().producedNodeId());
    }

    @Test
    void resumeRejectsAnswerFromForeignProjectOrInactiveFlow() {
        Project projectA = projectService.createProject("Repair foreign project");
        Node node = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "What is the goal?", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                projectA.id(), projectA.activeRouteId(), node.id(), null, "clarified", "user");

        // Foreign-project resume: the driver targets project B with A's answer.
        Project projectB = projectService.createProject("Repair foreign project B");

        UUID answerId = answer.id();
        try {
            answerDriver.resumeAnswer(projectB.id(), answerId);
            org.junit.jupiter.api.Assertions.fail("foreign-project resume must fail");
        } catch (RuntimeException expected) {
            // fail-closed
        }

        // Inactive-flow resume: fork away so the answer's route is no longer active.
        Route forkRoute = routeService.forkFromNode(
                projectA.id(), projectA.activeRouteId(), node.id(), "sibling route");
        assertThat(projectService.getProject(projectA.id()).orElseThrow().activeRouteId())
                .isEqualTo(forkRoute.id());

        try {
            answerDriver.resumeAnswer(projectA.id(), answerId);
            org.junit.jupiter.api.Assertions.fail("inactive-flow resume must fail");
        } catch (RuntimeException expected) {
            // fail-closed
        }

        // The immutable answer was never duplicated or modified.
        assertThat(answerRepository.findByRouteAndNodeIds(
                projectA.activeRouteId(), List.of(node.id()))).hasSize(1);
    }
}
