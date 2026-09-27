package com.specagent.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.policy.AgentProposalService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AgentProposalControllerApiIntegrationTest.java
 *
 * 测试目标:验证 Advisor 提案列表 API——PROPOSED 提案尚未决定,其 decidedAt/decidedBy
 * 必须序列化为 null(序列化不得抛异常,默认 /proposals 列表返回 200 而非 500);摘要携带
 * runId/inputNodeId 等运行时身份供前端重连;triggerType 由各提案的 AgentRun 派生并支持
 * 过滤/排除过滤;无 run 记录的旧提案接受后 originRunId 为 null。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentProposalControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private AgentProposalService proposalService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RunService runService;
    @Autowired private AgentRunService agentRunService;
    @Autowired private GraphCommandService commandService;

    private Project project;
    private UUID routeId;
    private Node anchor;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("提案列表测试 " + UUID.randomUUID());
        routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        anchor = commandService.createRootDraftNode(
                project.id(), routeId, "REQUIREMENT", Map.of("text", "锚点"));
    }

    @Test
    void proposedProposalListReturns200WithNullDecidedFields() throws Exception {
        proposalService.createProposal(
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE"),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                        List.of()),
                UUID.randomUUID(), project.id(), routeId);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(1);
        JsonNode summary = body.get(0);
        assertThat(summary.get("status").asText()).isEqualTo("PROPOSED");
        // PROPOSED 提案尚未决定:decidedAt/decidedBy 必须为 null。
        // 在 LinkedHashMap 修复之前,Map.of(...) 携带 null 值会抛 NullPointerException,
        // 列表会返回 HTTP 500。
        JsonNode decidedAt = summary.get("decidedAt");
        JsonNode decidedBy = summary.get("decidedBy");
        assertThat(decidedAt == null || decidedAt.isNull()).isTrue();
        assertThat(decidedBy == null || decidedBy.isNull()).isTrue();
    }

    /**
     * 深度评审第 8 项——待处理提案列表携带足够的运行时身份(runId + inputNodeId),
     * 让前端在页面刷新后能把持久化的 PROPOSED 提案重新连接到它的 NodeQuery 锚点节点。
     */
    @Test
    void proposalSummaryCarriesRuntimeIdentityForReloadReconnection() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "锚点问题？");
        // 把真实的 node-query run 绑定到提案,锚点才能解析出来。
        var proposal = proposalService.createProposal(
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                                "content", Map.of("text", "结论")),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                        List.of()),
                runId, project.id(), routeId);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(1);
        JsonNode summary = body.get(0);
        // 重连身份:proposalId、runId,以及产生该提案的查询运行的规范锚点
        // 节点 id(inputNodeId)。
        assertThat(summary.get("proposalId").asText()).isEqualTo(proposal.id().toString());
        assertThat(summary.get("runId").asText()).isEqualTo(runId.toString());
        assertThat(summary.get("inputNodeId").asText()).isEqualTo(anchor.id().toString());
        assertThat(summary.get("routeId").asText()).isEqualTo(routeId.toString());
        assertThat(summary.get("actionFamily").asText()).isEqualTo("CREATE_NODE");
        assertThat(summary.get("status").asText()).isEqualTo("PROPOSED");
    }

    /**
     * 最终评审第 3 项——待处理提案列表暴露由各提案的 AgentRun 派生的 triggerType。
     * 每种 run 类型都带 inputNodeId,所以前端必须按显式的 triggerType 过滤
     * NodeQuery 提案,绝不能靠 inputNodeId 推断。
     */
    @Test
    void proposalSummaryExposesTriggerTypeDerivedFromEachRun() throws Exception {
        UUID nodeQueryRunId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "锚点问题？");
        AgentRun answerRun = runService.createQueuedRunWithInputResult(
                project.id(), "ANSWER_TIP", anchor.id(), null, "补充说明", null, null);

        var queryProposal = proposalService.createProposal(
                new ActionProposal("CREATE_NODE", Map.of(
                        "kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "结论")),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-q-" + UUID.randomUUID(),
                        List.of()),
                nodeQueryRunId, project.id(), routeId);
        var answerProposal = proposalService.createProposal(
                new ActionProposal("REQUEST_USER_INPUT",
                        Map.of("questionText", "下一个问题"),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-a-" + UUID.randomUUID(),
                        List.of()),
                answerRun.id(), project.id(), routeId);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(2);
        JsonNode querySummary = findSummary(body, queryProposal.id().toString());
        JsonNode answerSummary = findSummary(body, answerProposal.id().toString());
        assertThat(querySummary.get("triggerType").asText()).isEqualTo("node_query");
        assertThat(answerSummary.get("triggerType").asText()).isEqualTo("answer_cycle");
        // 两个提案共享同一个锚点节点:triggerType 是 NodeQuery 恢复唯一安全的区分字段。
        assertThat(querySummary.get("inputNodeId").asText())
                .isEqualTo(answerSummary.get("inputNodeId").asText())
                .isEqualTo(anchor.id().toString());
    }

    /**
     * 服务端 triggerType 过滤:共享的提案列表可以收窄到单一触发类型,
     * 工作区由此不必拉取全量列表再在浏览器里后置过滤。
     */
    @Test
    void triggerTypeFilterKeepsOnlyProposalsOfThatRunType() throws Exception {
        UUID nodeQueryRunId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "锚点问题？");
        AgentRun answerRun = runService.createQueuedRunWithInputResult(
                project.id(), "ANSWER_TIP", anchor.id(), null, "补充说明", null, null);
        createRiskProposal(nodeQueryRunId, "q");
        createRiskProposal(answerRun.id(), "a");

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id())
                        .param("triggerType", "node_query"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("triggerType").asText()).isEqualTo("node_query");
    }

    /**
     * 互补的排除过滤。它存在的理由是工作区的两个加载器分别是"只要 NodeQuery"
     * 和"除 NodeQuery 外的一切";即使将来新增触发类型,排除过滤也能让后者保持正确。
     */
    @Test
    void excludeTriggerTypeFilterDropsProposalsOfThatRunType() throws Exception {
        UUID nodeQueryRunId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "锚点问题？");
        AgentRun answerRun = runService.createQueuedRunWithInputResult(
                project.id(), "ANSWER_TIP", anchor.id(), null, "补充说明", null, null);
        createRiskProposal(nodeQueryRunId, "q");
        var confirmable = createRiskProposal(answerRun.id(), "a");

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id())
                        .param("excludeTriggerType", "node_query"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("proposalId").asText())
                .isEqualTo(confirmable.id().toString());
        assertThat(body.get(0).get("triggerType").asText()).isEqualTo("answer_cycle");
    }

    /** 不带过滤参数时仍返回未过滤的全量列表(向后兼容)。 */
    @Test
    void omittedTriggerTypeFilterReturnsEveryProposal() throws Exception {
        UUID nodeQueryRunId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "锚点问题？");
        AgentRun answerRun = runService.createQueuedRunWithInputResult(
                project.id(), "ANSWER_TIP", anchor.id(), null, "补充说明", null, null);
        createRiskProposal(nodeQueryRunId, "q");
        createRiskProposal(answerRun.id(), "a");

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(2);
    }

    private com.specagent.agent.policy.AgentProposal createRiskProposal(UUID runId, String idempotencySuffix) {
        return proposalService.createProposal(
                new ActionProposal("CREATE_NODE", Map.of(
                        "kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "结论")),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(),
                        "idem-" + idempotencySuffix + "-" + UUID.randomUUID(),
                        List.of()),
                runId, project.id(), routeId);
    }

    private JsonNode findSummary(JsonNode list, String proposalId) {        for (JsonNode node : list) {
            if (proposalId.equals(node.get("proposalId").asText())) {
                return node;
            }
        }
        throw new AssertionError("Proposal summary not found: " + proposalId);
    }

    @Test
    void acceptLegacyProposalWithoutRunRowReturnsNullOriginRunId() throws Exception {
        var pending = proposalService.createProposal(
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                                "content", Map.of("text", "legacy")),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                        List.of("node:" + anchor.id())),
                UUID.randomUUID(), project.id(), routeId);

        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/proposals/{proposalId}/accept", pending.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
        JsonNode originRunId = body.get("originRunId");
        assertThat(originRunId == null || originRunId.isNull()).isTrue();
    }
}
