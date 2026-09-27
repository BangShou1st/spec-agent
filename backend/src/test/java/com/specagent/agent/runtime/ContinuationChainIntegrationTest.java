package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.ObservationView;
import com.specagent.agent.protocol.UsageView;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.route.RouteService;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityResult;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 文件名:ContinuationChainIntegrationTest.java
 *
 * 测试目标:Slice 3B:创建出的续跑子 run 通过生产链路真实执行一轮全新的
 * Observe → DECISION → Act 循环。
 *
 * 刻意不加 {@code @Transactional}:worker 的终态续跑钩子在提交后(after commit)
 * 才评估,回滚的测试事务永远不会触发它。fixture 在 {@link #cleanUp()} 中按项目清理。
 *
 * 测试不基于动作族名、冲突文本或 planner 标志做分支。续跑只通过协调器的
 * 持久化裁决判定;下一个提案由 fake brain 决定。
 */
@SpringBootTest
@ActiveProfiles("test")
class ContinuationChainIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private AgentRunEventService eventService;
    @Autowired private CapabilityInvocationRepository invocationRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private LoopProperties loopProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.specagent.workspace.answer.AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private com.specagent.agent.policy.AgentProposalService proposalService;
    @Autowired private com.specagent.agent.runtime.ProposalAcceptanceService acceptanceService;
    @SpyBean
    private ContinuationCheckRepository checkRepository;
    @Autowired private com.specagent.workspace.route.RouteService routeService;

    @SpyBean
    private ContinuationDispatchService dispatchService;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private final List<UUID> projectIds = new ArrayList<>();
    private int configuredMaxCycles = -1;

    @AfterEach
    void cleanUp() {
        if (configuredMaxCycles > 0) {
            loopProperties.setMaxCycles(configuredMaxCycles);
            configuredMaxCycles = -1;
        }
        Mockito.reset(decisionEngine);
        for (UUID projectId : projectIds) {
            jdbcTemplate.update(
                    "DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                            + "(SELECT id FROM agent_runs WHERE project_id = ?)",
                    (Object) projectId);
            jdbcTemplate.update(
                    "DELETE FROM agent_run_events WHERE run_id IN "
                            + "(SELECT id FROM agent_runs WHERE project_id = ?)",
                    (Object) projectId);
            jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?",
                    (Object) projectId);
            jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?",
                    (Object) projectId);
            jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?",
                    (Object) projectId);
        }
        projectIds.clear();
    }

    @Test
    void capabilitySuccessChainsToTerminalChild() {
        Project project = newProjectWithResource("chain-success");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after capability success")).id();

        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the continuation child to be claimable"));
        worker.executeRun(childClaimed);

        assertThat(decisions.get()).isEqualTo(2);
        assertThat(agentRunService.getRun(root.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        // 子 run 的全新快照观察到了父 run 的能力结果。
        var snapshot = contextBuilder.buildForRoute(
                project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                UUID.randomUUID(), ContextOperationType.NORMAL);
        assertThat(snapshotBuilder.build(snapshot).capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.SUCCEEDED.name()));
        // 终态子 run 不留下孙 run。
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(agentRunService.listByProject(project.id())).hasSize(2);
    }

    @Test
    void failedCapabilityIsVisibleToContinuationChild() {
        Project project = newProjectWithResource("chain-failed");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return brokenCapabilityInvoke(request);
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after capability failure")).id();

        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the continuation child to be claimable"));
        worker.executeRun(childClaimed);

        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        var snapshot = contextBuilder.buildForRoute(
                project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                UUID.randomUUID(), ContextOperationType.NORMAL);
        assertThat(snapshotBuilder.build(snapshot).capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.FAILED.name()));
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
    }

    @Test
    void maxCyclesOneStopsAtRoot() {
        Project project = newProjectWithResource("chain-budget-1");
        withMaxCycles(1);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        assertThat(agentRunService.getRun(root.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void maxCyclesTwoStopsAfterOneChild() {
        Project project = newProjectWithResource("chain-budget-2");
        withMaxCycles(2);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected one continuation child")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(agentRunService.listByProject(project.id())).hasSize(2);
    }

    @Test
    void staleChildFailsClosedWithoutModelCall() {
        Project project = newProjectWithResource("chain-stale");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // 外部因果在子 run 创建之后推进了 tip。
        nodeService.createChildNode(project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                "external question moves the tip?", null, List.of(), true);

        int decisionsBefore = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the stale child to stay claimable"));
        try {
            worker.executeRun(childClaimed);
        } catch (RuntimeException expected) {
            // 过期锚点 fail closed;worker 仍然做终态化。
        }

        int decisionsAfter = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        assertThat(decisionsAfter).isEqualTo(decisionsBefore);
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(eventService.findByRunId(childId).stream()
                .anyMatch(e -> "EXECUTING".equals(e.eventType()))).isFalse();
    }

    @Test
    void repeatedTerminalHookKeepsExactlyOneChild() {
        Project project = newProjectWithResource("chain-exactly-once");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id();

        // 重试的终态钩子(worker 重试、重复投递)必须收敛到同一个已持久化的
        // 子 run,绝不能出现第二行。重复投递走 dispatch——绝不通过重新执行
        // 终态 COMPLETED run 的模型/动作路径。
        dispatchService.process(root.id());

        assertThat(agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id()).isEqualTo(childId);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void graphMutationChainsToChildSeeingFreshNode() {
        Project project = newProjectWithResource("chain-graph");
        // 未回答的问题不能获得血统子节点:先回答根节点,
        // 打桩的 CREATE_NODE 才能追加。
        var routeBefore = routeRepository.findById(project.activeRouteId()).orElseThrow();
        finalizeAndCheckpoint(project, routeBefore.tipNodeId(), "answered for append");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createNoteProposal(request, "continuation observes this note");
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID produced = agentRunService.getRun(root.id()).orElseThrow()
                .producedNodeId();
        assertThat(produced).isNotNull();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after graph mutation")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        var snapshot = contextBuilder.buildForRoute(
                project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                UUID.randomUUID(), ContextOperationType.NORMAL);
        assertThat(snapshotBuilder.build(snapshot).lineage().stream()
                .map(entry -> entry.node().id()))
                .contains(produced);
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
    }

    @Test
    void userInputQuestionParksWithoutChild() {
        // 不打桩:生产 fake brain 对草稿的回答是用户输入问题,
        // 执行器将其持久化为 INTERACTION 节点——一个永久的外部边界。
        Project project = newProjectWithoutResource("chain-user-boundary");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.producedNodeId()).isNotNull();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void pendingApprovalParksWithoutChild() {
        Project project = newProjectWithResource("chain-approval");
        withMaxCycles(5);
        Mockito.doAnswer(invocation ->
                createDecisionNodeProposal(invocation.getArgument(0)))
                .when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(eventService.findByRunId(root.id()).stream()
                .anyMatch(e -> "AWAITING_APPROVAL".equals(e.eventType()))).isTrue();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void acceptedProposalExecutesOnceAndContinuesToChildSeeingResult() {
        Project project = newProjectWithResource("chain-approval-accept");
        // 未回答的问题不能获得血统子节点:先回答根节点,
        // 被接受的 CREATE_NODE 才能追加。
        var routeBefore = routeRepository.findById(project.activeRouteId()).orElseThrow();
        finalizeAndCheckpoint(project, routeBefore.tipNodeId(), "answered for append");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createAnchoredDecisionNodeProposal(request);
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();

        // 用户接受:提案精确执行一次,发起 run 获得持久化效果,
        // 重新打开的检查分发到恰好一个续跑子 run。
        var pending = proposalService.findByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException("expected a pending proposal"));
        var accepted = acceptanceService.acceptAndExecute(pending.id(), "test-user");
        assertThat(accepted.producedNodeId()).isNotNull();

        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "accept must continue the chain")).id();
        assertThat(agentRunService.getRun(root.id()).orElseThrow().producedNodeId())
                .isEqualTo(accepted.producedNodeId());
        assertThat(eventService.findByRunId(root.id()).stream()
                .anyMatch(e -> "ACCEPTANCE_EXECUTED".equals(e.eventType()))).isTrue();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        // 子 run 实际 DECISION 输入的血统包含被批准的节点。
        var requests = Mockito.mockingDetails(decisionEngine)
                .getInvocations().stream()
                .filter(call -> "runDecision".equals(call.getMethod().getName()))
                .map(call -> (AgentRequestEnvelope) call.getArgument(0))
                .toList();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).snapshot().lineage().stream()
                .map(entry -> entry.node().id()))
                .contains(accepted.producedNodeId());

        // 重复接受收敛到唯一赢家——绝不第二次执行,绝没有第二个子 run。
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> acceptanceService.acceptAndExecute(pending.id(), "test-user"))
                .isInstanceOf(com.specagent.agent.policy.ProposalAlreadyDecidedException.class);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void rejectedProposalCreatesNoChildAndExecutesNothing() {
        Project project = newProjectWithResource("chain-approval-reject");
        withMaxCycles(5);
        Mockito.doAnswer(invocation ->
                createDecisionNodeProposal(invocation.getArgument(0)))
                .when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        var pending = proposalService.findByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException("expected a pending proposal"));

        proposalService.rejectProposal(pending.id(), "test-user");

        assertThat(agentRunService.getRun(root.id()).orElseThrow().producedNodeId()).isNull();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
        Integer nodeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ?",
                Integer.class, project.id());
        // fixture 种子:1 个 resource + 1 个 knowledge + 1 个根问题。拒绝
        // 不执行任何动作,因此不会出现 agent 节点。
        assertThat(nodeCount).isEqualTo(3);
    }

    @Test
    void lostAcceptDispatchRecoversPendingCheckToChild() {
        Project project = newProjectWithResource("chain-approval-recover");
        // 与接受用例相同的先回答 fixture:被接受的 CREATE_NODE
        // 必须有血统父节点。
        var routeBeforeRecover = routeRepository.findById(project.activeRouteId()).orElseThrow();
        finalizeAndCheckpoint(project, routeBeforeRecover.tipNodeId(), "answered for append");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createAnchoredDecisionNodeProposal(request);
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        var pending = proposalService.findByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException("expected a pending proposal"));

        // 接受时刻的 afterCommit 分发抛异常:接受本身已提交(执行恰好
        // 完成一次),检查保持 pending。
        Mockito.doThrow(new IllegalStateException("accept dispatch down"))
                .doCallRealMethod()
                .when(dispatchService).process(any(UUID.class));
        var accepted = acceptanceService.acceptAndExecute(pending.id(), "test-user");
        assertThat(accepted.producedNodeId()).isNotNull();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
        assertThat(checkRepository.findPendingByRunId(root.id())).isPresent();
        Mockito.reset(dispatchService);

        // 恢复把仍 pending 的检查收敛到恰好一个子 run。
        dispatchService.recoverPending();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "recovery must continue the accepted chain")).id();
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void deniedProposalCreatesNoChild() {
        Project project = newProjectWithResource("chain-denied");
        withMaxCycles(5);
        Mockito.doAnswer(invocation ->
                updateNodeProposal(invocation.getArgument(0)))
                .when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.trace()).contains("policy_denied");
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void terminalRespondPersistsDurableMessageAtomicallyWithoutChild() {
        Project project = newProjectWithResource("chain-respond");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        // 根 run 的第一次 DECISION 真实执行(能力成功),续跑子 run 的
        // DECISION 做出回应:子 run 的终态 RESPOND 必须把用户可见消息
        // 持久化保存。
        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        AgentRun child = agentRunService.getRun(childId).orElseThrow();
        assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 持久化:RESPOND_MESSAGE 事件行是唯一事实来源。
        List<String> messages = eventService.findByRunId(childId).stream()
                .filter(e -> "RESPOND_MESSAGE".equals(e.eventType()))
                .map(e -> e.payload().get("message"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        assertThat(messages).containsExactly("chain observed and done");

        // 原子性:没有消息事件和 RUN_COMPLETED 标记时,COMPLETED 绝不可见
        // ——全部由一次终态化提交。
        assertThat(eventService.findByRunId(childId).stream()
                .anyMatch(e -> "RUN_COMPLETED".equals(e.eventType()))).isTrue();

        // 幂等读取:消息视图每次读取都来自同一事件行(不存在会发散的第二存储)。
        List<String> reread = eventService.findByRunId(childId).stream()
                .filter(e -> "RESPOND_MESSAGE".equals(e.eventType()))
                .map(e -> e.payload().get("message"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        assertThat(reread).isEqualTo(messages);

        // 终态回应之后不再有子 run:链在此结束。
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
    }

    @Test
    void childDecisionSeesParentCapabilityResultInRequest() {
        Project project = newProjectWithResource("chain-fresh-observe");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        // 证据是子 run 实际的 DECISION 输入——不是重建的测试快照:第二次
        // runDecision 请求携带父 run 的 SUCCEEDED 能力结果。(能力 fixture 不
        // 产生图节点,因此血统证据放在图变更用例中验证。)
        var requests = Mockito.mockingDetails(decisionEngine)
                .getInvocations().stream()
                .filter(call -> "runDecision".equals(call.getMethod().getName()))
                .map(call -> (AgentRequestEnvelope) call.getArgument(0))
                .toList();
        assertThat(requests).hasSize(2);
        AgentRequestEnvelope second = requests.get(1);
        assertThat(second.snapshot().capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.SUCCEEDED.name()));
    }

    @Test
    void childDecisionSeesParentFailedCapabilityResultInRequest() {
        Project project = newProjectWithResource("chain-fresh-observe-failed");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return brokenCapabilityInvoke(request);
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after capability failure")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        // FAILED 一侧同样的实际输入证明:持久化的失败作为证据保存,
        // 并进入子 run 真实的 DECISION 输入。
        var requests = Mockito.mockingDetails(decisionEngine)
                .getInvocations().stream()
                .filter(call -> "runDecision".equals(call.getMethod().getName()))
                .map(call -> (AgentRequestEnvelope) call.getArgument(0))
                .toList();
        assertThat(requests).hasSize(2);
        AgentRequestEnvelope second = requests.get(1);
        assertThat(second.snapshot().capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.FAILED.name()));
    }

    @Test
    void childDecisionSeesParentGraphMutationInRequestLineage() {
        Project project = newProjectWithResource("chain-fresh-observe-graph");
        // 未回答的问题不能获得 lineage 子节点:先回答根节点,
        // 桩定的 CREATE_NODE 才能追加。
        var routeBefore = routeRepository.findById(project.activeRouteId()).orElseThrow();
        finalizeAndCheckpoint(project, routeBefore.tipNodeId(), "answered for append");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createNoteProposal(request, "continuation observes this note");
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID produced = agentRunService.getRun(root.id()).orElseThrow()
                .producedNodeId();
        assertThat(produced).isNotNull();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after graph mutation")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        // 证据是子 run 实际 DECISION 输入的血统——不是重建的测试快照:
        // 第二次请求的血统包含 Run1 持久化产出的节点。
        var requests = Mockito.mockingDetails(decisionEngine)
                .getInvocations().stream()
                .filter(call -> "runDecision".equals(call.getMethod().getName()))
                .map(call -> (AgentRequestEnvelope) call.getArgument(0))
                .toList();
        assertThat(requests).hasSize(2);
        AgentRequestEnvelope second = requests.get(1);
        assertThat(second.snapshot().lineage().stream()
                .map(entry -> entry.node().id()))
                .contains(produced);
    }

    @Test
    void completedRunNeverReexecutesModelOrAction() {
        Project project = newProjectWithResource("chain-fail-closed");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        int decisionsAfterFirstRun = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();

        // 终态 COMPLETED run 的重复投递必须 fail closed——绝不重跑模型/动作。
        // 恢复只能走 dispatch,绝不能对终态行执行 executeRun。
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> worker.executeRun(
                                runService.getRun(root.id()).orElseThrow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
        int decisionsAfterDuplicate = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        assertThat(decisionsAfterDuplicate).isEqualTo(decisionsAfterFirstRun);
    }

    @Test
    void lostAfterCommitRecoversPendingCheckToChild() {
        Project project = newProjectWithResource("chain-recover");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        // 模拟在 COMMIT 之后、afterCommit 分发之前崩溃:终态检查行处于
        // pending 且子 run 尚不存在。恢复必须创建恰好一个子 run 并把检查
        // 标记为已处理。快速路径的子 run 按外键顺序删除(先事件后 run),
        // 使重放从与崩溃时相同的持久化状态开始。
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isPresent();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id();
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id = ?", childId);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE id = ?", childId);
        jdbcTemplate.update(
                "INSERT INTO agent_run_continuation_checks"
                        + " (run_id, requested_at, processed_at, request_generation)"
                        + " VALUES (?, CURRENT_TIMESTAMP, NULL, 1)"
                        + " ON CONFLICT (run_id) DO UPDATE"
                        + " SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL,"
                        + " request_generation ="
                        + " agent_run_continuation_checks.request_generation + 1",
                root.id());

        dispatchService.recoverPending();

        UUID recovered = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "recovery must create the missing child")).id();
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(recovered))
                .orElseThrow());
        assertThat(agentRunService.getRun(recovered).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void recoveryWithExistingChildMarksProcessedWithoutSecondChild() {
        Project project = newProjectWithResource("chain-recover-dedup");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // 模拟在子 run 创建与 markProcessed 之间崩溃:子 run 已存在但检查
        // 仍 pending。恢复必须经由 ALREADY_CONTINUED 收敛——不产生第二个子 run。
        jdbcTemplate.update(
                "INSERT INTO agent_run_continuation_checks"
                        + " (run_id, requested_at, processed_at, request_generation)"
                        + " VALUES (?, CURRENT_TIMESTAMP, NULL, 1)"
                        + " ON CONFLICT (run_id) DO UPDATE"
                        + " SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL,"
                        + " request_generation ="
                        + " agent_run_continuation_checks.request_generation + 1",
                root.id());

        dispatchService.recoverPending();

        assertThat(agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id()).isEqualTo(childId);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
    }

    @Test
    void staleGenerationCompletionNeverMarksSupersedingRequest() {
        Project project = newProjectWithResource("chain-generation-race");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // 确定性地重建精确的 ABA 交错:快速路径已消费 generation 1,所以删除
        // 子 run(按外键顺序)并重新请求两次——generation 2 固定"在途的过期"
        // 完成,generation 3 是顶替它的新请求。
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id = ?", childId);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE id = ?", childId);
        dispatchService.request(root.id());
        ContinuationCheck generationStale = checkRepository.findPendingByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a pending check after re-request"));

        // Slice 6 预览:审批接受会对同一终态 run 重新发起评估。generation
        // 必须递增并重新打开。
        dispatchService.request(root.id());
        ContinuationCheck generationNew = checkRepository.findPendingByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a pending check after second re-request"));
        assertThat(generationNew.generation())
                .isEqualTo(generationStale.generation() + 1);

        // 过期完成先执行:它创建了子 run,但按 generation 门控的 mark 以旧
        // generation 去更新一个已携带新 generation 的行——0 行被更新。
        dispatchService.process(generationStale);
        UUID firstChild = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "stale completion must still create the child")).id();

        // 新 generation 必须保持 pending——过期的完成绝不能消费顶替它的请求。
        assertThat(checkRepository.findPendingByRunId(root.id()))
                .isPresent()
                .get().extracting(ContinuationCheck::generation)
                .isEqualTo(generationNew.generation());

        // 恢复收敛新 generation:ALREADY_CONTINUED、同一个子 run、恰好一行,
        // 检查最终被处理。
        dispatchService.recoverPending();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id()).isEqualTo(firstChild);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
    }

    @Test
    void failedFastPathDispatchLeavesRunCompletedAndCheckPending() {
        Project project = newProjectWithResource("chain-best-effort");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        // 终态后立即执行的分发抛异常:失败必须只隔离在投递环节——父 run
        // 保持 COMPLETED(绝不作为执行失败重新抛出),检查保持 pending。
        // doThrow().doCallRealMethod() 把失败范围限定在快速路径;后面的恢复
        // 走真实分发。
        Mockito.doThrow(new IllegalStateException("dispatch transport down"))
                .doCallRealMethod()
                .when(dispatchService).process(any(UUID.class));

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
        assertThat(checkRepository.findPendingByRunId(root.id())).isPresent();

        // 恢复随后正常收敛仍 pending 的检查。
        dispatchService.recoverPending();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "recovery must converge the deferred check")).id();
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();

        // 桩只抛一次(doThrow().doCallRealMethod()):显式 reset,
        // 避免后续测试继承该失败。
        Mockito.reset(dispatchService);
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void markPhaseFailureRollsBackChildCreationForSafeReplay() {
        Project project = newProjectWithResource("chain-mark-rollback");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        // 快速路径已消费 generation 1:确定性重建崩溃形态(无子 run,
        // 一个 pending 的 generation)。
        UUID fastChild = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected the fast-path child")).id();
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id = ?", fastChild);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE id = ?", fastChild);
        dispatchService.request(root.id());
        ContinuationCheck pending = checkRepository.findPendingByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a pending check"));

        // 在协调器创建子 run 之后使 mark 阶段失败:显式的 TransactionTemplate
        // 事务必须把子 run 与 mark 一起回滚——半次评估(有子 run 无 mark)在
        // 最终的原子设计下绝不能提交。
        Mockito.doThrow(new IllegalStateException("mark store unavailable"))
                .when(checkRepository).markProcessed(any(UUID.class), anyLong());
        try {
            dispatchService.process(pending);
            org.assertj.core.api.Assertions.fail(
                    "mark-phase failure must propagate for recovery retry");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("mark store unavailable");
        } finally {
            Mockito.reset(checkRepository);
        }

        // 回滚后没有子 run 行幸存,检查仍 pending:恢复会安全重放同一评估。
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
        assertThat(checkRepository.findPendingByRunId(root.id())).isPresent();

        dispatchService.recoverPending();
        UUID recovered = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "recovery must create the child after rollback")).id();
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(recovered))
                .orElseThrow());
        assertThat(agentRunService.getRun(recovered).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void activeRouteSwitchFailsContinuationClosedWithoutModelCall() {
        Project project = newProjectWithResource("chain-route-switch");
        var routeBefore = routeRepository.findById(project.activeRouteId()).orElseThrow();
        finalizeAndCheckpoint(project, routeBefore.tipNodeId(), "answered for append");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        org.mockito.stubbing.Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createNoteProposal(request, "continuation observes this note");
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // 子 run 等待期间活动路由被切换——但旧 tip 未被动过,因此仅凭
        // 过期锚点检查仍会通过。必须由共享的 ContextGuard(活动路由匹配)
        // 拒绝。
        // 派生知识 tip 语义下,live tip 就是那个已回答的问题本身(笔记只是
        // 挂靠在其下),fork 分支点已有 finalized answer,直接分叉即可。
        UUID liveTip = routeRepository.findById(project.activeRouteId())
                .orElseThrow().tipNodeId();
        var forked = routeService.forkFromNode(project.id(), project.activeRouteId(),
                liveTip, "switched");
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(forked.id());

        int decisionsBefore = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the switched child to stay claimable"));
        try {
            worker.executeRun(childClaimed);
        } catch (RuntimeException expected) {
            // 守卫拒绝使 run fail closed;worker 做终态化。
        }

        int decisionsAfter = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        assertThat(decisionsAfter).isEqualTo(decisionsBefore);
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(eventService.findByRunId(childId).stream()
                .anyMatch(e -> "EXECUTING".equals(e.eventType()))).isFalse();
    }

    private Project newProjectWithResource(String name) {
        Project project = projectService.createProject(name + "-" + UUID.randomUUID());
        projectIds.add(project.id());
        commandService.attachResource(project.id(), project.activeRouteId(), null,
                "TEXT", Map.of("text", "continuation chain resource text"));
        // 血统中一个持久化的 KNOWLEDGE 节点:失败能力 fixture 把损坏的
        // 引用指向该节点(一个真实的、被允许的、但不是 resource 的引用,
        // 因此适配器会持久化 FAILED)。
        nodeService.createWorkspaceNode(project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                com.specagent.workspace.node.NodeKind.KNOWLEDGE, "NOTE",
                Map.of("text", "continuation chain knowledge"),
                com.specagent.workspace.node.NodeAuthorKind.USER,
                com.specagent.workspace.node.KnowledgeStatus.PROPOSED);
        nodeService.createChildNode(project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                "continuation chain root question?", null, List.of(), true);
        return projectService.getProject(project.id()).orElseThrow();
    }

    private Project newProjectWithoutResource(String name) {
        // 刻意使用空路由:生产 fake brain 自己起草根问题,
        // 执行器将其持久化为 INTERACTION。
        Project project = projectService.createProject(name + "-" + UUID.randomUUID());
        projectIds.add(project.id());
        return project;
    }

    /** 带已完成 STATE_UPDATE 检查点的直接 fixture 答案。 */
    private void finalizeAndCheckpoint(Project project, UUID nodeId, String freeText) {
        var answer = answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                nodeId, null, freeText, "test-user");
        answerPatchService.save(project.id(), project.activeRouteId(), nodeId,
                answer.id(), List.of(), null);
    }

    private void withMaxCycles(int maxCycles) {
        if (configuredMaxCycles < 0) {
            configuredMaxCycles = loopProperties.getMaxCycles();
        }
        loopProperties.setMaxCycles(maxCycles);
    }

    private AgentRun claim(AgentRun enqueued) {
        return runService.claimDecisionCycleRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued decision-cycle run " + enqueued.id()));
    }

    private void stubDecisionsAfterFirstWithTerminalRespond(AtomicInteger decisions) {
        Answer<Object> forward = invocation -> {
            if (decisions.getAndIncrement() == 0) {
                return invocation.callRealMethod();
            }
            return terminalRespond(invocation.getArgument(0));
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());
    }

    private AgentResponseEnvelope terminalRespond(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        AgentResponseEnvelope response = new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("Continuation observed prior facts."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("RESPOND_TO_USER",
                        Map.of("message", "chain observed and done"),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
        return response;
    }

    private AgentResponseEnvelope createNoteProposal(AgentRequestEnvelope request,
                                                     String text) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("A durable note is worth keeping."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "NOTE",
                                "content", Map.of("text", text)),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }

    private AgentResponseEnvelope createDecisionNodeProposal(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("A decision needs confirmation."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "DECISION",
                                "content", Map.of("text", "decide the scope boundary")),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }

    /**
     * Slice 6:同一个 DECISION 提案,但锚定在活动 tip 上,使后续的用户接受
     * 能通过接受阶段的过期锚点门禁(上面无锚点的变体只能暂停——在非空路由上
     * 它永远不可能被接受,这正是正确的 fail-closed 行为)。
     */
    private AgentResponseEnvelope createAnchoredDecisionNodeProposal(
            AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        UUID tip = request.snapshot().anchorNodeId();
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("A decision needs confirmation."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "DECISION",
                                "content", Map.of("text", "decide the scope boundary")),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(),
                        tip == null ? List.of() : List.of("node:" + tip)),
                new UsageView(1, List.of()), Map.of());
    }

    private AgentResponseEnvelope updateNodeProposal(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("An update has no runtime path."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("UPDATE_NODE",
                        Map.of("nodeRef", "node:" + UUID.randomUUID()),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }

    private AgentResponseEnvelope brokenCapabilityInvoke(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        // 一个真实的、但不是 resource 的血统节点:通过契约校验器(引用在允许
        // 集合内),却在适配器内部失败,持久化 FAILED 能力结果而不使 run 崩溃。
        String nodeRef = request.snapshot().lineage().stream()
                .filter(entry -> !"INTERACTION".equals(entry.node().kind())
                        && !"RESOURCE".equals(entry.node().kind()))
                .map(entry -> "node:" + entry.node().id())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "expected non-resource lineage nodes in the snapshot"));
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("A resource reference broke."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("INVOKE_CAPABILITY",
                        Map.of("capabilityId", "resource.extract_text",
                                "arguments", Map.of("nodeRef", nodeRef)),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }
}
