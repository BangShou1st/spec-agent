package com.specagent.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runtime.NodeQueryService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:NodeQueryRunControllerApiIntegrationTest.java
 *
 * 测试目标:验证上下文节点查询 run 的结果 API——节点身份守卫(run 只能在它所针对的
 * 节点下被读取),以及结果视图要暴露降级变更动作为该 run 产生的顾问提案;覆盖
 * AWAITING_APPROVAL、无提案、POLICY_DENIED、NOT_CONFIRMABLE(均来自持久事件)、
 * 普通只读完成等结果状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NodeQueryRunControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentProposalService proposalService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private RouteRepository routeRepository;

    private Project project;
    private UUID routeId;
    private Node nodeA;
    private Node nodeB;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("节点问答结果测试");
        routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        nodeA = commandService.createRootDraftNode(
                project.id(), routeId, "REQUIREMENT", Map.of("text", "节点A"));
        nodeB = commandService.appendContinuation(
                        project.id(), routeId, nodeA.id(), "REQUIREMENT", Map.of("text", "节点B"))
                .node();
    }

    @Test
    void queryResultForWrongNodeReturns404() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");

        // run 属于 nodeA;在 nodeB 下请求它必须 fail-closed 返回 404,
        // 而不是泄漏其他节点的 run 结果。
        mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeB.id(), runId))
                .andExpect(status().isNotFound());
    }

    @Test
    void queryResultExposesProposalForAwaitingApprovalRun() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");
        AgentRun claimed = runService.claimNextNodeQuery().orElseThrow();
        worker.executeRun(claimed);
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        // 把变更动作降级为待审批的 node-query run 会产生一条按 runId 关联的提案;
        // 按运行时的方式挂上它,确认结果视图能暴露出来。
        var proposal = proposalService.createProposal(
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE"),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                        List.of("node:" + nodeA.id())),
                runId, project.id(), routeId);

        mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeA.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.proposalId").value(proposal.id().toString()))
                .andExpect(jsonPath("$.proposalStatus").value(ProposalStatus.PROPOSED.code()))
                .andExpect(jsonPath("$.actionFamily").value("CREATE_NODE"));
    }

    @Test
    void queryResultWithoutProposalHasNullProposalFields() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");

        MvcResult result = mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeA.id(), runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        JsonNode proposalId = body.get("proposalId");
        JsonNode proposalStatus = body.get("proposalStatus");
        JsonNode actionFamily = body.get("actionFamily");
        // 该 run 没有产生提案:字段存在但为 null。
        assertThat(proposalId == null || proposalId.isNull()).isTrue();
        assertThat(proposalStatus == null || proposalStatus.isNull()).isTrue();
        assertThat(actionFamily == null || actionFamily.isNull()).isTrue();
    }

    @Test
    void policyDeniedOutcomeIsRealizedFromDurableEvent() throws Exception {
        // 提案被 policy 拒绝的查询会完成 run,但必须呈现 POLICY_DENIED,
        // 而不是塌缩成 COMPLETED。结果从持久的 POLICY_DENIED 运行事件派生。
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");
        AgentRun claimed = runService.claimNextNodeQuery().orElseThrow();
        worker.executeRun(claimed);
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        eventService.append(runId, AgentRunPhase.COMPLETED,
                NodeQueryService.POLICY_DENIED_EVENT,
                Map.of("denyReason", "mutation-not-allowed", "actionFamily", "CREATE_NODE"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeA.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POLICY_DENIED"))
                .andExpect(jsonPath("$.proposalId").doesNotExist());
    }

    @Test
    void notConfirmableOutcomeIsRealizedFromDurableEvent() throws Exception {
        // 变更动作无法产出可接受提案的查询必须呈现 NOT_CONFIRMABLE,同样来自
        // 持久运行事件,绝不能从 trace 字符串解析而来。
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");
        AgentRun claimed = runService.claimNextNodeQuery().orElseThrow();
        worker.executeRun(claimed);
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        eventService.append(runId, AgentRunPhase.COMPLETED,
                NodeQueryService.MUTATION_NOT_CONFIRMABLE_EVENT,
                Map.of("actionFamily", "CREATE_NODE"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeA.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_CONFIRMABLE"))
                .andExpect(jsonPath("$.proposalId").doesNotExist());
    }

    @Test
    void plainCompletedRunStillReportsCompleted() throws Exception {
        // 正常的只读完成保持 COMPLETED:只有显式的持久事件证据才会改变语义状态。
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");
        AgentRun claimed = runService.claimNextNodeQuery().orElseThrow();
        worker.executeRun(claimed);
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeA.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
