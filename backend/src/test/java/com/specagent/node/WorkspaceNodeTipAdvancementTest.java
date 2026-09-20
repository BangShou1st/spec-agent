package com.specagent.node;

import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Route-tip semantics for derived knowledge: a knowledge/resource node may
 * hang off the current tip for provenance, but it must never displace an
 * INTERACTION tip — burying the pending question makes the route
 * un-answerable (the tip is the only answerable node).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkspaceNodeTipAdvancementTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RouteRepository routeRepository;

    @Test
    void knowledgeNodeNeverDisplacesAnUnansweredQuestionTip() {
        Project project = projectService.createProject("知识节点不顶掉问题");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();

        Node question = nodeService.createRootNode(
                project.id(), route.id(), "您计划集成哪些流媒体服务？", null,
                List.of(), true);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(question.id());

        Node knowledge = nodeService.createWorkspaceNode(
                project.id(), route.id(), question.id(),
                NodeKind.KNOWLEDGE, "REQUIREMENT", Map.of("text", "可以接入插件"),
                NodeAuthorKind.AGENT, KnowledgeStatus.CONFIRMED);

        // The knowledge node keeps its provenance parent, but the tip stays on
        // the pending question so the user can still answer it.
        assertThat(knowledge.parentNodeId()).isEqualTo(question.id());
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(question.id());
    }

    @Test
    void knowledgeNodeStillSeedsAnEmptyRoute() {
        Project project = projectService.createProject("空路线知识起点");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();

        Node knowledge = nodeService.createWorkspaceNode(
                project.id(), route.id(), null,
                NodeKind.KNOWLEDGE, "NOTE", Map.of("text", "起点笔记"),
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);

        Route reloaded = routeRepository.findById(route.id()).orElseThrow();
        assertThat(reloaded.tipNodeId()).isEqualTo(knowledge.id());
        assertThat(reloaded.rootNodeId()).isEqualTo(knowledge.id());

        // A question created after the knowledge head advances the tip again.
        Node question = nodeService.createChildNode(
                project.id(), route.id(), knowledge.id(), "下一步问什么？", null,
                List.of(), true);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(question.id());
    }
}
