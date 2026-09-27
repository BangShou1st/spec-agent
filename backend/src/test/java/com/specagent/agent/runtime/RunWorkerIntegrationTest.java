package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:RunWorkerIntegrationTest.java
 *
 * 测试目标:经本地 fake 引擎的问题草稿决策循环:排队 run 被领取,对决策引擎端口
 * 执行恰好一次 DECISION,把每个阶段记录为 append-only 事件,自动执行的
 * REQUEST_USER_INPUT 提案落地为真实 INTERACTION 节点——空路由上是路由根节点,
 * 之后是 tip 的子节点。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RunWorkerIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private com.specagent.workspace.route.RouteService routeService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private com.specagent.agent.runevent.AgentRunEventRepository eventRepository;

    @Test
    void emptyRouteDraftAppendsTheRootQuestionNode() {
        Project project = projectService.createProject("决策周期项目");
        assertThat(projectService.getProject(project.id()).orElseThrow()
                .activeRouteId()).isNotNull();

        AgentRun run = runService.createQueuedDraftQuestion(project.id());
        AgentRun claimed = runService.claimDecisionCycleRun(run.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued decision-cycle run " + run.id()));
        worker.executeRun(claimed);

        AgentRun completed = runService.getRun(run.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.producedNodeId()).isNotNull();

        Node root = nodeService.getNode(completed.producedNodeId()).orElseThrow();
        assertThat(root.question()).isEqualTo("What is the most important outcome?");
        assertThat(root.parentNodeId()).isNull();
        assertThat(routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId())
                .isEqualTo(root.id());

        List<String> lifecycle = eventRepository.findByRunId(run.id()).stream()
                .map(event -> event.eventType())
                .collect(Collectors.toList());
        // 纯续跑:一次 DECISION,没有 STATE_UPDATE 阶段。PROCESS_NOTE 条目
        // 是面向用户的进度注记(decision + executing)。
        assertThat(lifecycle).containsExactly(
                "RUN_CREATED",
                "SNAPSHOT_BUILT",
                "DECISION_STARTED",
                "PROPOSAL_CREATED",
                "PROCESS_NOTE",
                "EXECUTING",
                "PROCESS_NOTE",
                "RUN_COMPLETED");
    }

    @Test
    void draftAfterARootAppendsAChildAtTheTip() {
        Project project = projectService.createProject("认领执行项目");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "谁是最主要的用户？", null, List.of(), true);
        // 未回答的 Question 必须保持为路由 tip;先回答它,
        // 下一次草稿才能追加子节点。
        var answer = answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                root.id(), null, "answered root", "test-user");
        answerPatchService.save(project.id(), project.activeRouteId(), root.id(),
                answer.id(), List.of(), null);

        AgentRun run = runService.createQueuedDraftQuestion(project.id());
        AgentRun claimed = runService.claimDecisionCycleRun(run.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued decision-cycle run " + run.id()));
        worker.executeRun(claimed);

        AgentRun completed = runService.getRun(run.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        Node child = nodeService.getNode(completed.producedNodeId()).orElseThrow();
        assertThat(child.parentNodeId()).isEqualTo(root.id());

        assertThat(runService.claimNext()).isEmpty();
    }
}
