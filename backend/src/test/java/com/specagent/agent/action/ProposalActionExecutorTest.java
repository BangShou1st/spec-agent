package com.specagent.agent.action;

import com.specagent.agent.protocol.ActionProposal;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
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
 * 文件名:ProposalActionExecutorTest.java
 *
 * 测试目标:验证 ProposalActionExecutor 对各动作族的执行语义——REQUEST_USER_INPUT/
 * CREATE_NODE 产出子节点(未回答的 INTERACTION Question 必须先定稿 Answer 才允许追加)、
 * RESPOND_TO_USER 返回消息、WAIT 为空操作、INVOKE_CAPABILITY 经能力运行时执行(未知
 * 能力以带类型的 FAILED 结果 fail-closed 记录)、GENERATE_ARTIFACT/UPDATE_NODE/未知动作族
 * 分别抛出不支持/需确认/非法参数异常。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProposalActionExecutorTest {

    @Autowired
    private ProposalActionExecutor executor;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private AnswerPatchService answerPatchService;

    private Project project;
    private Route route;
    private Node rootNode;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("Action 测试项目");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        rootNode = nodeService.createRootNode(project.id(), route.id(),
                "根节点问题", null, List.of(), true);
    }

    @Test
    void requestUserInputCreatesChildNode() {
        // 未回答的 INTERACTION Question 必须保持为路线 tip;
        // 先为根节点定稿 Answer,才允许追加 agent 的 REQUEST_USER_INPUT 子节点。
        var answer = answerService.finalizeAnswer(project.id(), route.id(), rootNode.id(),
                null, "answered root", "test-user");
        answerPatchService.save(project.id(), route.id(), rootNode.id(), answer.id(), List.of(), null);

        ActionProposal proposal = proposal("REQUEST_USER_INPUT", Map.of(
                "questionText", "你的首要目标是什么？",
                "options", List.of(Map.of("label", "明确目标")),
                "allowFreeAnswer", true));
        ActionExecutionContext context = context(rootNode.id());

        ActionResult result = executor.execute(proposal, context);

        assertThat(result.actionFamily()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(result.producedNodeId()).isNotNull();
        Node child = nodeService.getNode(result.producedNodeId()).orElseThrow();
        assertThat(child.question()).isEqualTo("你的首要目标是什么？");
        assertThat(child.options()).hasSize(1);
        assertThat(child.options().get(0).label()).isEqualTo("明确目标");
        assertThat(child.allowFreeAnswer()).isTrue();
        assertThat(child.parentNodeId()).isEqualTo(rootNode.id());
    }

    @Test
    void createNodeCreatesChildNode() {
        var answer = answerService.finalizeAnswer(project.id(), route.id(), rootNode.id(),
                null, "answered root", "test-user");
        answerPatchService.save(project.id(), route.id(), rootNode.id(), answer.id(), List.of(), null);

        ActionProposal proposal = proposal("CREATE_NODE", Map.of(
                "question", "风险评估节点",
                "purpose", "识别项目风险"));
        ActionExecutionContext context = context(rootNode.id());

        ActionResult result = executor.execute(proposal, context);

        assertThat(result.actionFamily()).isEqualTo("CREATE_NODE");
        assertThat(result.producedNodeId()).isNotNull();
        Node child = nodeService.getNode(result.producedNodeId()).orElseThrow();
        assertThat(child.question()).isEqualTo("风险评估节点");
    }

    @Test
    void respondToUserReturnsMessage() {
        ActionProposal proposal = proposal("RESPOND_TO_USER", Map.of(
                "message", "根据分析，主要风险是时间线过紧。"));
        ActionExecutionContext context = context(rootNode.id());

        ActionResult result = executor.execute(proposal, context);

        assertThat(result.actionFamily()).isEqualTo("RESPOND_TO_USER");
        assertThat(result.message()).isEqualTo("根据分析，主要风险是时间线过紧。");
        assertThat(result.producedNodeId()).isNull();
    }

    @Test
    void waitReturnsNoop() {
        ActionProposal proposal = proposal("WAIT", Map.of());
        ActionExecutionContext context = context(rootNode.id());

        ActionResult result = executor.execute(proposal, context);

        assertThat(result.actionFamily()).isEqualTo("WAIT");
        assertThat(result.producedNodeId()).isNull();
    }

    @Test
    void invokeCapabilityExecutesThroughCapabilityRuntime() {
        // 未知的能力 id 现在以带类型的 FAILED 结果执行(fail-closed 记录到调用日志),
        // 而不是直接崩溃。
        ActionProposal proposal = proposal("INVOKE_CAPABILITY", Map.of(
                "capabilityId", "no.such.capability"));
        ActionExecutionContext context = context(rootNode.id());

        ActionResult result = executor.execute(proposal, context);

        assertThat(result.actionFamily()).isEqualTo("INVOKE_CAPABILITY");
        assertThat(result.message()).contains("FAILED");
    }

    @Test
    void generateArtifactIsUnsupported() {
        ActionProposal proposal = proposal("GENERATE_ARTIFACT", Map.of(
                "artifactType", "spec"));
        ActionExecutionContext context = context(rootNode.id());

        assertThatThrownBy(() -> executor.execute(proposal, context))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not supported in Stage B");
    }

    @Test
    void updateNodeIsDeferred() {
        ActionProposal proposal = proposal("UPDATE_NODE", Map.of());
        ActionExecutionContext context = context(rootNode.id());

        assertThatThrownBy(() -> executor.execute(proposal, context))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("requires confirmation");
    }

    @Test
    void unknownActionFamilyThrows() {
        ActionProposal proposal = proposal("UNKNOWN_ACTION", Map.of());
        ActionExecutionContext context = context(rootNode.id());

        assertThatThrownBy(() -> executor.execute(proposal, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown action family");
    }

    private ActionProposal proposal(String family, Map<String, Object> payload) {
        return new ActionProposal(
                family, payload, UUID.randomUUID(), "hash123",
                List.of(), UUID.randomUUID(), "idemp-1", List.of());
    }

    private ActionExecutionContext context(UUID anchorNodeId) {
        return new ActionExecutionContext(
                UUID.randomUUID(), project.id(), route.id(),
                UUID.randomUUID(), anchorNodeId, null, null);
    }
}
