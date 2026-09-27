package com.specagent.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.workspace.node.NodeResponse;
import com.specagent.workspace.route.RouteMutationResponse;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AnswerRouteIsolationApiIntegrationTest.java
 *
 * 测试目标:在面向模型的信封层面验证答案隔离,经由异步 AgentRun 命令入口触发。spy
 * 委托给真实的确定性引擎并捕获运行时发出的每个 {@link AgentRequestEnvelope}。
 * 分叉到新的激活路线后,后续每个答案 run 只能携带激活 lineage(共享根、run 本地答案),
 * 必须排除兄弟哨兵文本、兄弟路线的答案/补丁/节点以及旧路线 id。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnswerRouteIsolationApiIntegrationTest {

    private static final String SIBLING_SENTINEL = "API_SIBLING_SENTINEL_DO_NOT_LEAK_9d4b";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private com.specagent.workspace.node.NodeService nodeService;
    @Autowired
    private com.specagent.agent.DecisionCycleTestDriver draftDriver;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private final List<AgentRequestEnvelope> captured = new ArrayList<>();

    @BeforeEach
    void captureModelRequests() {
        captured.clear();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runStateUpdate(any(AgentRequestEnvelope.class));
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runDecision(any(AgentRequestEnvelope.class));
    }

    /** 用于排除性断言的拍平信封投影。 */
    private String envelopeText(AgentRequestEnvelope envelope) {
        StringBuilder combined = new StringBuilder();
        combined.append("route:").append(envelope.snapshot().routeId()).append('\n');
        // run 本地的事件携带触发本次运行的答案输入。
        if (envelope.event() != null && envelope.event().freeText() != null) {
            combined.append(envelope.event().freeText()).append('\n');
        }
        for (var entry : envelope.snapshot().lineage()) {
            if (entry.node() != null) {
                combined.append("node:").append(entry.node().id()).append('\n');
                var body = entry.node().body();
                if (body != null && body.text() != null) {
                    combined.append(body.text()).append('\n');
                }
            }
            if (entry.answer() != null) {
                combined.append("answer:").append(entry.answer().id()).append('\n');
                if (entry.answer().freeText() != null) {
                    combined.append(entry.answer().freeText()).append('\n');
                }
            }
            if (entry.patches() != null) {
                for (var patch : entry.patches()) {
                    combined.append("patch:").append(patch.id()).append('\n');
                }
            }
        }
        return combined.toString();
    }

    @Test
    void forkAnswerRequestsExcludeSiblingContent() throws Exception {
        Project project = projectService.createProject("API answer isolation");

        NodeResponse root = draftNext(project.id());

        // 回答根节点,然后在路线 R1 上回答第二个节点,文本带唯一的兄弟哨兵。
        AnswerRunView rootAnswer = submitAnswer(project.id(), "Root answer stays on R1");
        NodeResponse nodeA = nextNodeAfter(rootAnswer);
        AnswerRunView branchAnswer = submitAnswer(
                project.id(), SIBLING_SENTINEL + " the sibling branch answer");
        NodeResponse nodeA2 = nextNodeAfter(branchAnswer);
        UUID r1RouteId = branchAnswer.routeId();
        UUID r1AnswerId = branchAnswer.producedAnswerId();

        // 从共享根分叉:R2 变为激活路线。
        RouteMutationResponse fork = fork(project.id(), root.id());
        UUID r2RouteId = fork.route().id();

        int forkPoint = captured.size();
        // 共享根已在 R1 上回答过(单一不可变 Answer 身份),所以 R2 不能重新回答它
        // ——分叉后的第一个动作是在 R2 上起草下一个 Question(parent = 共享根),
        // 然后用户回答这个新 Question。
        NodeResponse forkChild = draftNext(project.id());
        AnswerRunView forkAnswer = submitAnswer(
                project.id(), "Fork branch answer local to R2");

        List<AgentRequestEnvelope> forkEnvelopes =
                captured.subList(forkPoint, captured.size());
        assertThat(forkEnvelopes).isNotEmpty();
        assertThat(forkAnswer.status()).isEqualTo("completed");

        for (AgentRequestEnvelope envelope : forkEnvelopes) {
            String text = envelopeText(envelope);
            assertThat(text)
                    .as("fork envelope must exclude sibling content")
                    .doesNotContain(SIBLING_SENTINEL)
                    .doesNotContain("node:" + nodeA.id())
                    .doesNotContain("node:" + nodeA2.id())
                    .doesNotContain("answer:" + r1AnswerId);
            assertThat(envelope.snapshot().routeId())
                    .as("fork envelopes target the active fork route")
                    .isEqualTo(r2RouteId);
        }
        // 激活 lineage 至少出现在一个分叉信封中:共享根节点与 run 本地的答案文本。
        assertThat(forkEnvelopes).anySatisfy(envelope -> {
            String text = envelopeText(envelope);
            assertThat(text).contains("node:" + root.id())
                    .contains("Fork branch answer local to R2");
        });

        // 任何兄弟路线的 lineage 条目都没有泄漏进任何分叉信封。
        assertThat(forkEnvelopes)
                .allSatisfy(envelope -> assertThat(envelope.snapshot().lineage())
                        .allSatisfy(entry -> {
                            if (entry.node() != null) {
                                assertThat(entry.node().id())
                                        .isNotEqualTo(nodeA.id())
                                        .isNotEqualTo(nodeA2.id());
                            }
                            if (entry.answer() != null) {
                                assertThat(entry.answer().id()).isNotEqualTo(r1AnswerId);
                            }
                        }));
    }

    private record AnswerRunView(UUID runId, UUID routeId, UUID producedNodeId,
                                 UUID producedAnswerId, String status) {
    }

    /**
     * 对当前激活 tip 入队一个 ANSWER_TIP run 并驱动 worker 执行;
     * 返回 run 读视图。
     */
    private AnswerRunView submitAnswer(UUID projectId, String freeText) throws Exception {
        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", projectId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"freeText\": \""
                                        + freeText + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = extractString(created.getResponse().getContentAsString(), "runId");

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());

        MvcResult read = mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        projectId, runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode view = objectMapper.readTree(read.getResponse().getContentAsString());
        return new AnswerRunView(
                UUID.fromString(view.get("runId").asText()),
                UUID.fromString(view.get("routeId").asText()),
                view.hasNonNull("producedNodeId") ? UUID.fromString(view.get("producedNodeId").asText()) : null,
                view.hasNonNull("producedAnswerId") ? UUID.fromString(view.get("producedAnswerId").asText()) : null,
                view.get("status").asText());
    }

    /** 读取已完成 run 产出的规范节点(测试夹具读取)。 */
    private NodeResponse nextNodeAfter(AnswerRunView view) {
        return NodeResponse.from(nodeService.getNode(view.producedNodeId()).orElseThrow());
    }

    private NodeResponse draftNext(UUID projectId) throws Exception {
        // 同步草稿端点已退役;改经生产运行时路径驱动异步 DRAFT_QUESTION run。
        com.specagent.agent.runtime.AgentRun draftRun = draftDriver.draftQuestion(projectId);
        return NodeResponse.from(nodeService.getNode(draftRun.producedNodeId()).orElseThrow());
    }

    private RouteMutationResponse fork(UUID projectId, UUID nodeId) throws Exception {
        UUID sourceRouteId = projectService.getProject(projectId).orElseThrow().activeRouteId();
        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork",
                        projectId, nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + sourceRouteId
                                + "\", \"label\": \"isolation fork\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                RouteMutationResponse.class);
    }

    private String extractString(String json, String field) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }
}
