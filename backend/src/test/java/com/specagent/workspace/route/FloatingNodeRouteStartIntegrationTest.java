package com.specagent.workspace.route;

import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "继续生成问题"的路由语义:
 * 1. 浮动的知识/资源节点可以开启一条新独立路线(节点成为根+tip);
 * 2. 已挂在某条路线谱系上的知识节点可以作为 fork 分支点(无需答案——
 *    "分支点必须有答案"只约束可回答的问题节点);
 * 3. 问题节点保持原有的"已回答才能分叉"约束。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FloatingNodeRouteStartIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RouteService routeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private com.specagent.workspace.answer.AnswerService answerService;

    @Test
    void floatingKnowledgeNodeStartsAStandaloneRoute() {
        Project project = projectService.createProject("想法开新路线");
        // 先在主路线放一个问题,让项目存在可用路线;想法保持浮动。
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "主路线的问题", null, List.of(), true);
        Node idea = nodeService.createFloatingWorkspaceNode(
                project.id(), NodeKind.KNOWLEDGE, "IDEA",
                Map.of("text", "想做一个爬虫"),
                com.specagent.workspace.node.NodeAuthorKind.USER,
                com.specagent.workspace.node.KnowledgeStatus.PROPOSED);

        Route started = routeService.startRouteFromNode(project.id(), idea.id(), null);

        assertThat(started.rootNodeId()).isEqualTo(idea.id());
        assertThat(started.tipNodeId()).isEqualTo(idea.id());
        assertThat(started.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(started.branchType()).isNull();
        // 新路线成为 Active 路线,下一个问题起草即锚定该想法节点。
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(started.id());
    }

    @Test
    void aNodeAlreadyOnALineageCannotStartAnotherRoute() {
        Project project = projectService.createProject("谱系节点不可再开线");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "主路线的问题", null, List.of(), true);
        // 谱系上的知识节点同样不允许再开一条独立路线(它已有归属)。
        Node knowledge = nodeService.createWorkspaceNode(
                project.id(), project.activeRouteId(), root.id(),
                NodeKind.KNOWLEDGE, "IDEA", Map.of("text", "据此继续"),
                com.specagent.workspace.node.NodeAuthorKind.AGENT,
                com.specagent.workspace.node.KnowledgeStatus.PROPOSED);

        assertThatThrownBy(() -> routeService.startRouteFromNode(project.id(), knowledge.id(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already belongs to a route lineage");
    }

    @Test
    void anInteractionNodeCanNeverStartAStandaloneRoute() {
        Project project = projectService.createProject("问题不可开独立线");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "主路线的问题", null, List.of(), true);

        // 问题节点只能在路线谱系里被回答,永远不允许作为独立路线起点。
        assertThatThrownBy(() -> routeService.startRouteFromNode(project.id(), root.id(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only a floating knowledge/resource node");
    }

    @Test
    void anAttachedKnowledgeNodeCanForkWithoutAnyAnswer() {
        Project project = projectService.createProject("知识节点分叉");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "主路线的问题", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                root.id(), null, "answered", "test-user");
        // 知识节点挂在已答问题下,进入路线谱系(不顶掉 tip 语义下它也不需要
        // 答案):从它分叉必须成功——这正是"已接入的想法继续生成问题"。
        Node knowledge = nodeService.createWorkspaceNode(
                project.id(), project.activeRouteId(), root.id(),
                NodeKind.KNOWLEDGE, "IDEA", Map.of("text", "据此继续"),
                com.specagent.workspace.node.NodeAuthorKind.AGENT,
                com.specagent.workspace.node.KnowledgeStatus.PROPOSED);

        Route forked = routeService.forkFromNode(
                project.id(), project.activeRouteId(), knowledge.id(), null);

        assertThat(forked.tipNodeId()).isEqualTo(knowledge.id());
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(forked.id());
    }
}
