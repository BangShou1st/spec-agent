package com.specagent.workspace.node;

import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:WorkspaceNodeTipAdvancementTest.java
 *
 * 测试目标:派生知识节点的路线 tip 语义——知识/资源节点可以作为溯源挂在
 * 当前 tip 之下,但绝不能顶掉 INTERACTION 类型的 tip(埋掉待回答的问题会让
 * 路线无法被回答,tip 是唯一可回答的节点);同时验证知识节点仍可作为空路线
 * 的起点推进 tip。
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

        // 知识节点保留其溯源父节点,但 tip 仍停留在待回答的问题上,
        // 用户仍可回答它。
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

        // 知识节点成为头部之后,新创建的问题会再次推进 tip。
        Node question = nodeService.createChildNode(
                project.id(), route.id(), knowledge.id(), "下一步问什么？", null,
                List.of(), true);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(question.id());
    }
}
