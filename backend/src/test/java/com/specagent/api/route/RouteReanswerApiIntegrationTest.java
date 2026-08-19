package com.specagent.api.route;

import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.answer.AnswerRepository;
import com.specagent.agent.FakeAnswerRunResult;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private RouteInheritedAnswerRepository inheritedAnswerRepository;

    @Test
    void reanswerKeepsCanonicalQuestionAndFreezesOnlyParentPrefix() throws Exception {
        Project project = projectService.createProject("Re-answer project");
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Root answer");
        FakeAnswerRunResult targetRun = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), "Original answer");
        // The run produces the next unanswered question; re-answer the node
        // whose answer was just recorded.
        Node target = nodeRepository.findById(targetRun.answer().nodeId()).orElseThrow();
        UUID sourceRouteId = targetRun.run().routeId();
        UUID oldAnswerId = targetRun.answer().id();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/reanswer",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("{" +
                                "\"sourceRouteId\":\"" + sourceRouteId + "\"," +
                                "\"label\":\"Try another answer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.route.isActive").value(true))
                .andExpect(jsonPath("$.route.tipNodeId").value(target.id().toString()))
                .andExpect(jsonPath("$.route.branchType").value("reanswer"))
                .andExpect(jsonPath("$.route.sourceRouteId").value(sourceRouteId.toString()))
                .andExpect(jsonPath("$.route.branchAtNodeId").value(target.id().toString()));

        UUID reanswerRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        Route reanswer = routeService.getRoute(reanswerRouteId).orElseThrow();
        assertThat(reanswer.tipNodeId()).isEqualTo(target.id());
        assertThat(answerRepository.findByRouteAndNodeIds(reanswer.id(), java.util.List.of(target.id())))
                .isEmpty();
        assertThat(answerRepository.findById(oldAnswerId).orElseThrow().routeId())
                .isEqualTo(sourceRouteId);
        assertThat(inheritedAnswerRepository.findByBranchRouteId(reanswer.id()))
                .extracting(RouteInheritedAnswer::nodeId)
                .doesNotContain(target.id());
    }

    @Test
    void reanswerRequiresExplicitSourceRoute() throws Exception {
        Project project = projectService.createProject("Re-answer validation");
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Root answer");
        FakeAnswerRunResult targetRun = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), "Original answer");
        Node target = nodeRepository.findById(targetRun.answer().nodeId()).orElseThrow();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/reanswer",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
