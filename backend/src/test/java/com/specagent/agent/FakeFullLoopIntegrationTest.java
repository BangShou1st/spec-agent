package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.FakeFullLoopIntegrationTest;

import com.specagent.agent.runtime.AgentRunService;

import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.spec.SourceKind;
import com.specagent.workspace.spec.SourceReference;
import com.specagent.workspace.spec.SpecSnapshot;
import com.specagent.workspace.spec.SpecSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:FakeFullLoopIntegrationTest.java
 *
 * 测试目标:fake 全循环走异步 ANSWER_CYCLE 的成功路径集成测试:
 * 问题草稿、答题、答案 patch、下一个节点、规格快照生成。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FakeFullLoopIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private SpecSnapshotService specSnapshotService;

    @Test
    void fullFakeLoopFromQuestionToAnswerPatchToNextNode() {
        Project project = projectService.createProject("Full loop project");

        UUID answeredNodeId = draftDriver.draftQuestion(project.id()).producedNodeId();

        var result = answerDriver.submitFreeText(
                project.id(), "I need to clarify the main outcome");

        // run 完成并记录了所有产出的 id。
        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.run().producedAnswerId()).isNotNull();
        assertThat(result.run().producedPatchId()).isNotNull();
        assertThat(result.run().producedNodeId()).isNotNull();

        // 答案已持久化。
        Answer answer = answerService.getAnswer(result.answerId()).orElseThrow();
        assertThat(answer.nodeId()).isEqualTo(answeredNodeId);
        assertThat(answer.freeText()).isEqualTo("I need to clarify the main outcome");

        // patch 已持久化,来源信息真实。
        AnswerPatch patch = answerPatchService.getPatch(result.patchId()).orElseThrow();
        assertThat(patch.sourceNodeId()).isEqualTo(answeredNodeId);
        assertThat(patch.sourceAnswerId()).isEqualTo(answer.id());
        assertThat(patch.claims()).isNotEmpty();

        AnswerPatch persistedPatch = answerPatchService.findByRoute(project.activeRouteId()).stream()
                .filter(p -> p.id().equals(patch.id()))
                .findFirst().orElseThrow();
        Claim confirmed = persistedPatch.claims().stream()
                .filter(Claim::isConfirmed)
                .findFirst().orElseThrow();
        assertThat(confirmed.sourceNodeId()).isEqualTo(answeredNodeId);
        assertThat(confirmed.sourceAnswerId()).isEqualTo(answer.id());

        // 下一个节点已存在,且扩展自被回答的节点。
        Node nextNode = nodeService.getNode(result.producedNodeId()).orElseThrow();
        assertThat(nextNode.parentNodeId()).isEqualTo(answeredNodeId);

        // 路由末梢推进到下一个节点。
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(nextNode.id());

        // 从活动路由新构建的上下文包含全部内容。
        ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                project.id(), result.run().id(), ContextOperationType.NORMAL);
        assertThat(context.includedNodeIds()).contains(answeredNodeId, nextNode.id());
        assertThat(context.includedAnswerIds()).contains(answer.id());
        assertThat(context.includedPatchIds()).contains(patch.id());
    }

    @Test
    void fakeAnswerRunPersistsAnswerAndPatch() {
        Project project = projectService.createProject("Answer run project");
        draftDriver.draftQuestion(project.id());

        var result = answerDriver.submitFreeText(
                project.id(), "The primary outcome must be measurable");

        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(answerService.getAnswer(result.answerId())).isPresent();
        assertThat(answerPatchService.getPatch(result.patchId())).isPresent();
        assertThat(result.run().producedAnswerId()).isEqualTo(result.answerId());
        assertThat(result.run().producedPatchId()).isEqualTo(result.patchId());
        assertThat(nodeService.getNode(result.run().producedNodeId())).isPresent();
    }

}
