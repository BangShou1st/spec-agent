package com.specagent.api.graph;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared-state / Answer-identity invariants under the final product model:
 * one canonical Question Node carries exactly ONE immutable Answer identity
 * project-wide. Branch routes reference the same Answer id through inherited
 * refs; a second distinct Answer for the same canonical node is a
 * SHARED_STATE_DIVERGENCE invariant violation, never a UI mode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SharedStateInvariantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private RouteService routeService;
    @Autowired
    private RouteHistoryResolver routeHistoryResolver;

    private Project newProject(String title) {
        return projectService.createProject(title);
    }

    @Test
    void sameQuestionNodeCannotGainASecondDistinctAnswerOnAnotherRoute() {
        Project project = newProject("Shared state project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "共享问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "source answer", "user");

        // Fork shares the canonical node; the fork route carries an inherited
        // reference to the SAME answer id, so it must not finalize a new one.
        Route fork = routeService.forkFromNode(project.id(), routeId, root.id(), "共享 fork");

        assertThatThrownBy(() -> answerService.finalizeAnswer(
                project.id(), fork.id(), root.id(), null, "divergent answer", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHARED_STATE_DIVERGENCE");
    }

    @Test
    void inheritedRouteReferencesTheSameAnswerIdentity() {
        Project project = newProject("Shared identity project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "共享问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        Answer source = answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "source answer", "user");

        Route fork = routeService.forkFromNode(project.id(), routeId, root.id(), "共享 fork");
        List<Answer> effective = routeHistoryResolver.resolveEffectiveAnswers(
                fork.id(), List.of(root.id()));
        assertThat(effective).hasSize(1);
        assertThat(effective.get(0).id()).isEqualTo(source.id());
        assertThat(effective.get(0).nodeId()).isEqualTo(root.id());
    }

    @Test
    void readModelFailsClosedWhenTwoDistinctAnswerIdsResolveToSameNode() throws Exception {
        Project project = newProject("Read model divergence project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "共享问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "first answer", "user");

        // A fork route shares the canonical node through an inherited ref.
        // Deliberately corrupt: insert a second, distinct Answer on the fork
        // route for the SAME canonical node (bypassing the write-time
        // invariant on purpose) to prove the read model detects the
        // corruption instead of presenting it as a normal UI mode.
        Route fork = routeService.forkFromNode(project.id(), routeId, root.id(), "corrupt fork");
        Answer second = new Answer(UUID.randomUUID(), project.id(), fork.id(), root.id(),
                null, "second answer", "user", java.time.Instant.now());
        answerRepository.save(second);

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }

    @Test
    void reanswerKeepsReadModelSingleAnswerIdentity() throws Exception {
        Project project = newProject("Reanswer shared project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        answerService.finalizeAnswer(project.id(), routeId, root.id(), null, "answer", "user");

        // Re-answer creates a NEW Question node; the old node is no longer on
        // the re-answer route's lineage, so the read model still resolves a
        // single Answer identity per canonical node.
        Route reanswer = routeService.reanswerFromNode(project.id(), routeId, root.id(), "retry");
        assertThat(nodeService.getNode(reanswer.tipNodeId()).orElseThrow().id())
                .isNotEqualTo(root.id());

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isOk());
    }
}