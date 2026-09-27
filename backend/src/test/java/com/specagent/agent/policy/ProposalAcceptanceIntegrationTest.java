package com.specagent.agent.policy;

import com.specagent.agent.runtime.ProposalAcceptanceService;

import com.specagent.agent.protocol.ActionProposal;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.graph.GraphOperation;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ProposalAcceptanceIntegrationTest.java
 *
 * 测试目标:验证 Advisor 提案的接受流程——接受时针对当前图事实重新校验新鲜度
 * (锚点过期则拒绝且保持 PROPOSED)、经命令层执行变更(CREATE_NODE/SEMANTIC 连接)、
 * 在带类型的操作日志中记录接受操作;并覆盖重复接受拒绝、持久化 run 回传 originRunId
 * 并登记续写检查、幽灵 runId 返回 null 且不写检查等场景。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProposalAcceptanceIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private ProposalAcceptanceService acceptanceService;
    @Autowired private AgentProposalService proposalService;
    @Autowired private com.specagent.agent.runtime.AgentRunService agentRunService;
    @Autowired private com.specagent.agent.runtime.AgentRunRepository agentRunRepository;
    @Autowired private com.specagent.agent.runtime.ContinuationCheckRepository checkRepository;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;

    private Project project;
    private Route route;
    private Node tip;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("提案接受测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        tip = graphCommandService.appendContinuation(
                project.id(), route.id(), root.id(), "REQUIREMENT",
                Map.of("text", "tip 需求")).node();
    }

    private AgentProposal createPendingNodeProposal() {
        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "离线同步可能产生冲突")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + tip.id()));
        return proposalService.createProposal(proposal, UUID.randomUUID(),
                project.id(), route.id());
    }

    @Test
    void acceptExecutesNodeCreationAndLogsOperation() {
        AgentProposal pending = createPendingNodeProposal();

        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThat(result.actionFamily()).isEqualTo("CREATE_NODE");
        assertThat(result.producedNodeId()).isNotNull();
        Node created = nodeRepository.findById(result.producedNodeId()).orElseThrow();
        assertThat(created.subtype()).isEqualTo("RISK");
        assertThat(created.authorKind().code()).isEqualTo("AGENT");

        assertThat(proposalService.getProposal(pending.id()).orElseThrow().status())
                .isEqualTo(ProposalStatus.ACCEPTED);

        assertThat(graphCommandService.listOperations(project.id()))
                .anySatisfy(op -> {
                    assertThat(op.type()).isEqualTo(GraphOperation.Type.ACCEPT_AGENT_PROPOSAL);
                    assertThat(op.actor()).isEqualTo(GraphOperation.Actor.AGENT);
                    assertThat(op.causedBy()).isEqualTo("proposal:" + pending.id());
                });
    }

    @Test
    void acceptRejectsStaleAnchorAndStaysPending() {
        AgentProposal pending = createPendingNodeProposal();

        // 在接受之前路线 tip 被推进。
        graphCommandService.appendContinuation(
                project.id(), route.id(), tip.id(), "NOTE", Map.of("text", "new tip"));

        assertThatThrownBy(() -> acceptanceService.acceptAndExecute(pending.id(), "user"))
                .isInstanceOf(com.specagent.agent.action.StaleProposalException.class);

        assertThat(proposalService.getProposal(pending.id()).orElseThrow().status())
                .isEqualTo(ProposalStatus.PROPOSED);
    }

    @Test
    void acceptSemanticConnectCreatesRelationWithAgentProvenance() {
        Node root = nodeRepository.findById(tip.parentNodeId()).orElseThrow();
        ActionProposal proposal = new ActionProposal(
                "CONNECT_NODE",
                Map.of("relationClass", "SEMANTIC", "relationType", "DERIVED_FROM",
                        "sourceRef", "node:" + tip.id(), "targetRef", "node:" + root.id()),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of());
        AgentProposal pending = proposalService.createProposal(
                proposal, UUID.randomUUID(), project.id(), route.id());

        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThat(result.relationId()).isNotNull();
        var relation = relationRepository.findById(result.relationId()).orElseThrow();
        assertThat(relation.relationType()).isEqualTo(NodeRelationType.DERIVED_FROM);
        assertThat(relation.origin()).isEqualTo(NodeRelation.Origin.AGENT);
        assertThat(relation.createdByProposalId()).isEqualTo(pending.id());
    }

    @Test
    void acceptTwiceIsRejected() {
        AgentProposal pending = createPendingNodeProposal();
        acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThatThrownBy(() -> acceptanceService.acceptAndExecute(pending.id(), "user"))
                .isInstanceOf(ProposalAlreadyDecidedException.class)
                .hasMessageContaining("already been decided")
                .extracting(e -> ((ProposalAlreadyDecidedException) e).currentStatus())
                .isEqualTo("ACCEPTED");
    }

    @Test
    void acceptWithPersistedRunReturnsOriginRunIdAndRequestsContinuation() {
        com.specagent.agent.runtime.AgentRun run = agentRunService.create(project.id(), route.id(),
                com.specagent.agent.runtime.AgentRunTriggerType.DECISION_CYCLE, null, null,
                "DRAFT_QUESTION");
        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "persisted origin")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + tip.id()));
        AgentProposal pending = proposalService.createProposal(
                proposal, run.id(), project.id(), route.id());

        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThat(result.originRunId()).isEqualTo(run.id());
        assertThat(checkRepository.findPendingByRunId(run.id())).isPresent();
    }

    @Test
    void acceptWithGhostRunIdReturnsNullOriginAndWritesNoCheck() {
        UUID ghostRunId = UUID.randomUUID();
        assertThat(agentRunRepository.findById(ghostRunId)).isEmpty();
        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "ghost origin")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + tip.id()));
        AgentProposal pending = proposalService.createProposal(
                proposal, ghostRunId, project.id(), route.id());

        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThat(result.producedNodeId()).isNotNull();
        assertThat(result.originRunId()).isNull();
        assertThat(checkRepository.findPendingByRunId(ghostRunId)).isEmpty();
    }
}
