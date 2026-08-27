package com.specagent.api.route;

import com.specagent.answer.AnswerRepository;
import com.specagent.agent.FakeAnswerRunResult;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteInheritedAnswer;
import com.specagent.route.RouteInheritedAnswerRepository;
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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Re-answer under the final product model: it creates a NEW canonical
 * Question Node. The old Question and its immutable Answer stay untouched on
 * the source route; the inherited prefix freezes only the answers strictly
 * before the old target; the new Question starts the re-answer route waiting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RouteReanswerApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private com.specagent.agent.DecisionCycleTestDriver draftDriver;
    @Autowired
    private com.specagent.agent.AnswerCycleTestDriver answerDriver;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private RouteInheritedAnswerRepository inheritedAnswerRepository;

    @Test
    void reanswerCreatesNewQuestionNodeCopiesSemanticsAndFreezesParentPrefix() throws Exception {
        Project project = projectService.createProject("Re-answer project");
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "Root answer");
        var targetRun = answerDriver.submitFreeText(project.id(), "Original answer");
        // The run records the answered node as its input; re-answer that node.
        Node target = nodeRepository.findById(targetRun.run().inputNodeId()).orElseThrow();
        UUID sourceRouteId = targetRun.run().routeId();
        UUID oldAnswerId = targetRun.answerId();
        UUID oldParentId = target.parentNodeId();
        // Source tip before re-answer (the run may have advanced past the
        // target by drafting the next question).
        UUID sourceTipBefore = routeService.getRoute(sourceRouteId).orElseThrow().tipNodeId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/reanswer",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("{" +
                                "\"sourceRouteId\":\"" + sourceRouteId + "\"," +
                                "\"label\":\"Try another answer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.route.isActive").value(true))
                .andExpect(jsonPath("$.route.branchType").value("reanswer"))
                .andExpect(jsonPath("$.route.sourceRouteId").value(sourceRouteId.toString()))
                .andExpect(jsonPath("$.route.branchAtNodeId").value(target.id().toString()));

        UUID reanswerRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        Route reanswer = routeService.getRoute(reanswerRouteId).orElseThrow();

        // 1. New Question Node identity: tip is a NEW node, never the old one.
        assertThat(reanswer.tipNodeId()).isNotEqualTo(target.id());
        Node newQuestion = nodeRepository.findById(reanswer.tipNodeId()).orElseThrow();
        assertThat(newQuestion.kind().code()).isEqualTo("INTERACTION");
        assertThat(newQuestion.subtype()).isEqualTo("QUESTION");
        // 2. Copied question/purpose/options.
        assertThat(newQuestion.question()).isEqualTo(target.question());
        assertThat(newQuestion.purpose()).isEqualTo(target.purpose());
        assertThat(newQuestion.options()).hasSize(target.options().size());
        assertThat(newQuestion.allowFreeAnswer()).isEqualTo(target.allowFreeAnswer());
        // 3. Parent is the old target's parent.
        assertThat(newQuestion.parentNodeId()).isEqualTo(oldParentId);
        // 4. Inherited prefix excludes the old target answer.
        assertThat(inheritedAnswerRepository.findByBranchRouteId(reanswer.id()))
                .extracting(RouteInheritedAnswer::nodeId)
                .doesNotContain(target.id());
        assertThat(answerRepository.findByRouteAndNodeIds(reanswer.id(),
                List.of(reanswer.tipNodeId()))).isEmpty();
        // 5. Source route unchanged: tip and old Question/Answer untouched.
        assertThat(answerRepository.findById(oldAnswerId).orElseThrow().routeId())
                .isEqualTo(sourceRouteId);
        assertThat(nodeRepository.findById(target.id()).orElseThrow().isRetracted()).isFalse();
        Route sourceReloaded = routeService.getRoute(sourceRouteId).orElseThrow();
        assertThat(sourceReloaded.tipNodeId()).isEqualTo(sourceTipBefore);
    }

    @Test
    void reanswerCreatesNewQuestionWhenTargetIsRoot() throws Exception {
        Project project = projectService.createProject("Re-answer root project");
        draftDriver.draftQuestion(project.id());
        var rootRun = answerDriver.submitFreeText(project.id(), "Root original answer");
        Node root = nodeRepository.findById(rootRun.run().inputNodeId()).orElseThrow();
        UUID sourceRouteId = rootRun.run().routeId();
        assertThat(root.parentNodeId()).isNull();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/reanswer",
                        project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{" +
                                "\"sourceRouteId\":\"" + sourceRouteId + "\"," +
                                "\"label\":\"Root retry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.branchType").value("reanswer"));

        UUID reanswerRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        Route reanswer = routeService.getRoute(reanswerRouteId).orElseThrow();
        assertThat(reanswer.tipNodeId()).isNotEqualTo(root.id());
        // Root re-answer: the new Question is a fresh root (no parent).
        Node newRoot = nodeRepository.findById(reanswer.tipNodeId()).orElseThrow();
        assertThat(newRoot.parentNodeId()).isNull();
        assertThat(newRoot.question()).isEqualTo(root.question());
    }

    @Test
    void reanswerRequiresExplicitSourceRoute() throws Exception {
        Project project = projectService.createProject("Re-answer validation");
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "Root answer");
        var targetRun = answerDriver.submitFreeText(project.id(), "Original answer");
        Node target = nodeRepository.findById(targetRun.run().inputNodeId()).orElseThrow();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/reanswer",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}