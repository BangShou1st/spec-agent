package com.specagent.agent.runtime;

import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AgentRunRecoveryIntegrationTest.java
 *
 * 测试目标:任务级失败恢复(读侧 + 提交侧)。所有失败 run 通过生产路径
 * 构造(入队 → 认领 → 终态化 + RUN_FAILED 事件),覆盖:
 * - 回答已保存 → CONTINUE_PROCESSING,重试映射为 RESUME_ANSWER 且不创建
 *   第二个 Answer;
 * - 重试幂等:双击/并发返回同一个 run;在途时原始条目携带 retryRunId;
 * - 被后续成功取代的失败从清单消失且重试 409;
 * - 两条路线各自失败:重试永远绑定原路线,切换 Active 不串目标;
 * - 路线前进后目标过期:STALE,重试 409;
 * - 换题重试不会变成起草;节点查询重试保留原问题(允许 routeId=null);
 * - 缺少 RUN_CREATED 事件的旧失败不可重试(RECOVERY_CONTEXT_UNAVAILABLE);
 * - 失败 → 重试 → 再次失败:清单只显示最新失败,不叠卡。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentRunRecoveryIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProjectService projectService;
    @Autowired NodeService nodeService;
    @Autowired AnswerService answerService;
    @Autowired RouteService routeService;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired AgentRunEventService eventService;
    @Autowired AgentRunRecoveryService recoveryService;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired com.specagent.workspace.graph.GraphCommandService commandService;


    private void failRun(AgentRun run, String reason) {
        agentRunService.fail(run.id(), "failed:" + reason);
        eventService.append(run.id(), AgentRunPhase.FAILED, "RUN_FAILED",
                Map.of("reason", reason));
    }

    private AgentRun queuedAnswerRun(UUID projectId, UUID routeId, UUID nodeId, String text) {
        return runService.createQueuedRunWithInputResultForRoute(
                projectId, "ANSWER_TIP", nodeId, null, null, text, null,
                null, null, null, routeId);
    }

    private List<UnresolvedFailureView> unresolved(UUID projectId) {
        return recoveryService.listUnresolved(projectId);
    }

    @Test
    void answerSavedFailureMapsToResumeAndNeverDuplicatesAnswer() throws Exception {
        Project project = projectService.createProject("恢复-回答已保存 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                project.id(), routeId, node.id(), null, "已保存的回答", "test-user");

        AgentRun failed = queuedAnswerRun(project.id(), routeId, node.id(), "已保存的回答");
        runService.claimAnswerCycleRun(failed.id());
        agentRunService.markPersistedAnswer(failed.id(), answer.id(), "t");
        failRun(failed, "brain_timeout");

        var views = unresolved(project.id());
        assertThat(views).hasSize(1);
        var view = views.get(0);
        assertThat(view.availableAction()).isEqualTo("CONTINUE_PROCESSING");
        assertThat(view.actionLabel()).isEqualTo("继续处理");
        assertThat(view.routeId()).isEqualTo(routeId.toString());
        assertThat(view.sourceNodeId()).isEqualTo(node.id().toString());
        assertThat(view.reasonCode()).isEqualTo("brain_timeout");

        // 重试:返回 RESUME_ANSWER,绑定同一 answer,绝不创建第二个 Answer
        AgentRun retry = recoveryService.retry(project.id(), failed.id());
        assertThat(retry.operation()).isEqualTo("RESUME_ANSWER");
        assertThat(retry.routeId()).isEqualTo(routeId);
        assertThat(retry.idempotencyKey()).isEqualTo("retry:" + failed.id());
        long answerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM answers WHERE project_id = :projectId",
                Map.of("projectId", project.id()), Long.class);
        assertThat(answerCount).isEqualTo(1);

        // 双击/并发:同一失败任务的重试再次到达 → 同一个 run(idempotent)
        AgentRun again = recoveryService.retry(project.id(), failed.id());
        assertThat(again.id()).isEqualTo(retry.id());

        // 清单:原始条目携带在途 retryRunId,只有一条(不叠卡)
        var during = unresolved(project.id());
        assertThat(during).hasSize(1);
        assertThat(during.get(0).retryRunId()).isEqualTo(retry.id().toString());
    }

    @Test
    void successSupersedesFailureInListAndRejectsLateRetry() {
        Project project = projectService.createProject("恢复-已被成功取代 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                project.id(), routeId, node.id(), null, "回答", "test-user");
        AgentRun failed = queuedAnswerRun(project.id(), routeId, node.id(), "回答");
        runService.claimAnswerCycleRun(failed.id());
        agentRunService.markPersistedAnswer(failed.id(), answer.id(), "t");
        failRun(failed, "brain_timeout");

        AgentRun retry = recoveryService.retry(project.id(), failed.id());
        agentRunService.complete(retry.id(), AgentRunStatus.COMPLETED, "recovered");

        assertThat(unresolved(project.id())).isEmpty();
        assertThatThrownByConflict(() -> recoveryService.retry(project.id(), failed.id()),
                "SUPERSEDED_BY_SUCCESS");
    }

    @Test
    void twoRouteFailuresRecoverIndependentlyRegardlessOfActivePointer() {
        Project project = projectService.createProject("恢复-双路线 " + UUID.randomUUID());
        UUID routeA = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeA,
                "What is the goal?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeA, root.id(), null, "answered", "u");
        Route routeB = routeService.forkFromNode(project.id(), routeA, root.id(), "分支B");

        AgentRun failedA = runService.createQueuedDraftQuestion(
                project.id(), "k-a-" + UUID.randomUUID(),
                com.specagent.agent.runtime.AgentRunRequestFingerprint.forClientRequest(
                        project.id(), "DRAFT_QUESTION", null, routeA, null, null, null),
                routeA);
        AgentRun failedB = runService.createQueuedDraftQuestion(
                project.id(), "k-b-" + UUID.randomUUID(),
                com.specagent.agent.runtime.AgentRunRequestFingerprint.forClientRequest(
                        project.id(), "DRAFT_QUESTION", null, routeB.id(), null, null, null),
                routeB.id());
        failRun(failedA, "brain_timeout");
        failRun(failedB, "model_provider_failure");

        var views = unresolved(project.id());
        assertThat(views).hasSize(2);
        assertThat(views).extracting(UnresolvedFailureView::routeId)
                .containsExactlyInAnyOrder(routeA.toString(), routeB.id().toString());
        // 临时故障(含 model_provider_failure)不再导向设置页:按操作家族
        // 给出可重试动作(R2-D)
        assertThat(views).extracting(UnresolvedFailureView::availableAction)
                .containsExactlyInAnyOrder("RETRY_GENERATION", "RETRY_GENERATION");

        // 切换 Active 到分支 B:路线 A 的失败重试仍然绑定路线 A
        routeService.setActiveRoute(project.id(), routeB.id());
        AgentRun retryA = recoveryService.retry(project.id(), failedA.id());
        assertThat(retryA.routeId()).isEqualTo(routeA);
        assertThat(retryA.triggerType()).isEqualTo(AgentRunTriggerType.DECISION_CYCLE);

        AgentRun retryB = recoveryService.retry(project.id(), failedB.id());
        assertThat(retryB.routeId()).isEqualTo(routeB.id());

        // 两个重试互不相同,各自的幂等键绑定各自的失败任务
        assertThat(retryA.id()).isNotEqualTo(retryB.id());
        assertThat(retryA.idempotencyKey()).isEqualTo("retry:" + failedA.id());
        assertThat(retryB.idempotencyKey()).isEqualTo("retry:" + failedB.id());
    }

    @Test
    void retractedSourceNodeMakesFailureStaleAndRetryIsRejected() {
        Project project = projectService.createProject("恢复-路线前进 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);
        AgentRun failed = runService.createQueuedDraftQuestion(
                project.id(), "k-" + UUID.randomUUID(),
                com.specagent.agent.runtime.AgentRunRequestFingerprint.forClientRequest(
                        project.id(), "DRAFT_QUESTION", null, routeId, null, null, null),
                routeId);
        failRun(failed, "brain_timeout");

        // 冲突场景:源节点被撤回(节点删除/回收的真实语义),失败目标不再成立
        nodeService.setRetracted(root.id(), true);

        var views = unresolved(project.id());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).stale()).isTrue();
        assertThat(views.get(0).availableAction()).isEqualTo("STALE");
        assertThatThrownByConflict(() -> recoveryService.retry(project.id(), failed.id()),
                "STALE_RECOVERY_TARGET");
    }

    @Test
    void regenerateFailureRetriesAsRegenerateNotDraft() {
        Project project = projectService.createProject("恢复-换题 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "旧问题", null, List.of(), true);
        AgentRun failed = runService.createQueuedRegenerate(
                project.id(), routeId, root.id(), "换一个更具体的问法");
        failRun(failed, "brain_timeout");

        AgentRun retry = recoveryService.retry(project.id(), failed.id());
        assertThat(retry.triggerType()).isEqualTo(AgentRunTriggerType.REGENERATE_NODE);
        assertThat(retry.inputNodeId()).isEqualTo(root.id());
        assertThat(retry.routeId()).isEqualTo(routeId);
        var views = unresolved(project.id());
        assertThat(views).anySatisfy(v -> {
            assertThat(v.availableAction()).isEqualTo("RETRY_REGENERATE");
            assertThat(v.actionLabel()).isEqualTo("重试换题");
        });
    }

    @Test
    void nodeQueryFailureRetryPreservesQuestionAndNullRoute() {
        Project project = projectService.createProject("恢复-节点问答 " + UUID.randomUUID());
        // 归档活动路线,构造完全无路线的浮动节点(与 RoutelessNodeQuery 同法)
        routeService.archiveRoute(project.id(), project.activeRouteId());
        Node floating = commandService.createFloatingDraftNode(
                project.id(), null, "IDEA", Map.of("text", "漂浮的想法"));

        UUID failedId = runService.createQueuedNodeQuery(
                project.id(), null, floating.id(), "这个想法为什么重要？");
        failRun(agentRunService.getRun(failedId).orElseThrow(), "brain_timeout");

        var views = unresolved(project.id());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).availableAction()).isEqualTo("RETRY_NODE_QUERY");
        assertThat(views.get(0).routeId()).isNull();

        AgentRun retry = recoveryService.retry(project.id(), failedId);
        assertThat(retry.triggerType()).isEqualTo(AgentRunTriggerType.NODE_QUERY);
        assertThat(retry.routeId()).isNull();
        assertThat(retry.inputNodeId()).isEqualTo(floating.id());
        // 幂等:再次重试返回同一个 run
        assertThat(recoveryService.retry(project.id(), failedId).id()).isEqualTo(retry.id());
        var payload = eventService.findByRunId(retry.id()).stream()
                .filter(e -> "RUN_CREATED".equals(e.eventType())).findFirst().orElseThrow();
        assertThat(payload.payload().get("question")).isEqualTo("这个想法为什么重要？");
    }

    @Test
    void legacyFailureWithoutRunCreatedEventCannotBeRetried() {
        Project project = projectService.createProject("恢复-旧任务 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);
        AgentRun failed = runService.createQueuedDraftQuestion(
                project.id(), "k-" + UUID.randomUUID(),
                com.specagent.agent.runtime.AgentRunRequestFingerprint.forClientRequest(
                        project.id(), "DRAFT_QUESTION", null, routeId, null, null, null),
                routeId);
        failRun(failed, "brain_timeout");
        // 模拟缺少可靠恢复上下文的旧任务:RUN_CREATED 事件不存在
        jdbc.update("DELETE FROM agent_run_events WHERE run_id = :runId",
                Map.of("runId", failed.id()));

        assertThatThrownByConflict(() -> recoveryService.retry(project.id(), failed.id()),
                "RECOVERY_CONTEXT_UNAVAILABLE");
    }

    @Test
    void retriedFailureThatFailsAgainShowsSingleLatestFailure() {
        Project project = projectService.createProject("恢复-不叠卡 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                project.id(), routeId, node.id(), null, "回答", "test-user");
        AgentRun failed = queuedAnswerRun(project.id(), routeId, node.id(), "回答");
        runService.claimAnswerCycleRun(failed.id());
        agentRunService.markPersistedAnswer(failed.id(), answer.id(), "t");
        failRun(failed, "brain_timeout");

        AgentRun retry = recoveryService.retry(project.id(), failed.id());
        failRun(retry, "brain_timeout");

        var views = unresolved(project.id());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).runId()).isEqualTo(retry.id().toString());
        assertThat(views.get(0).availableAction()).isEqualTo("CONTINUE_PROCESSING");
    }

    @Test
    void httpContractExposesUnresolvedListAndRetry() throws Exception {
        Project project = projectService.createProject("恢复-HTTP 契约 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);
        AgentRun failed = runService.createQueuedDraftQuestion(
                project.id(), "k-" + UUID.randomUUID(),
                com.specagent.agent.runtime.AgentRunRequestFingerprint.forClientRequest(
                        project.id(), "DRAFT_QUESTION", null, routeId, null, null, null),
                routeId);
        failRun(failed, "brain_timeout");

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/unresolved", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(failed.id().toString()))
                .andExpect(jsonPath("$[0].availableAction").value("RETRY_GENERATION"))
                .andExpect(jsonPath("$[0].actionLabel").value("重试生成"))
                .andExpect(jsonPath("$[0].routeId").value(routeId.toString()))
                .andExpect(jsonPath("$[0].sourceNodeId").isNotEmpty());

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs/{runId}/retry",
                        project.id(), failed.id()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.operation").value("DRAFT_QUESTION"));
    }

    /**
     * 临时服务故障(连接拒绝/5xx/限流)绝不武断导向设置页(第二轮复核
     * R2-D 的闭合):失败码 brain_unavailable / model_provider_failure 不
     * 证明凭据配置错误,恢复入口按操作家族给出可重试动作;服务恢复后
     * 用户从原失败位置直接重试,重试绑定原路线。
     */
    @Test
    void transientBrainFailureOffersRetryInsteadOfSettingsRedirect() {
        Project project = projectService.createProject("恢复-临时故障 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        nodeService.createRootNode(project.id(), routeId,
                "What is the scope?", null, List.of(), true);
        AgentRun failed = runService.createQueuedDraftQuestion(project.id());
        runService.claimDecisionCycleRun(failed.id());
        failRun(failed, "brain_unavailable");

        var views = unresolved(project.id());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).availableAction()).isEqualTo("RETRY_GENERATION");
        assertThat(views.get(0).actionLabel()).isEqualTo("重试生成");

        // 服务恢复后:原失败位置重试成功路径存在,且绑定原路线
        AgentRun retry = recoveryService.retry(project.id(), failed.id());
        assertThat(retry.triggerType()).isEqualTo(AgentRunTriggerType.DECISION_CYCLE);
        assertThat(retry.routeId()).isEqualTo(routeId);
        assertThat(retry.idempotencyKey()).isEqualTo("retry:" + failed.id());
    }

    /** 同上,验证 model_provider_failure(提供方 5xx 等)也不再映射设置页。 */
    @Test
    void transientProviderFailureAlsoStaysRetryable() {
        Project project = projectService.createProject("恢复-提供方故障 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the scope?", null, List.of(), true);
        UUID failedId = runService.createQueuedNodeQuery(
                project.id(), routeId, node.id(), "有哪些约束？");
        failRun(agentRunService.getRun(failedId).orElseThrow(), "model_provider_failure");

        var views = unresolved(project.id());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).availableAction()).isEqualTo("RETRY_NODE_QUERY");
        assertThat(views.get(0).actionLabel()).isEqualTo("重试该查询");
    }

    /**
     * 同节点、同路线的不同 NODE_QUERY 问题是独立任务(6-3):后一个
     * 不相关问题成功绝不隐藏前一个失败;只有同一问题的重试链才收敛。
     */
    @Test
    void differentNodeQueryQuestionsOnSameNodeStayIndependent() {
        Project project = projectService.createProject("恢复-问答意图 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);

        UUID failedA = runService.createQueuedNodeQuery(
                project.id(), routeId, node.id(), "问题A：目标是什么？");
        failRun(agentRunService.getRun(failedA).orElseThrow(), "brain_timeout");
        UUID failedB = runService.createQueuedNodeQuery(
                project.id(), routeId, node.id(), "问题B：边界在哪里？");
        failRun(agentRunService.getRun(failedB).orElseThrow(), "brain_timeout");
        assertThat(unresolved(project.id())).hasSize(2);

        // 后一个不相关问题的查询成功:两个失败都必须继续展示,不能被
        // "同位置同家族成功"错误地合并隐藏
        UUID unrelated = runService.createQueuedNodeQuery(
                project.id(), routeId, node.id(), "问题C：谁负责？");
        runService.claimNodeQueryRun(unrelated);
        agentRunService.complete(unrelated, AgentRunStatus.COMPLETED, "done");
        var afterUnrelated = unresolved(project.id());
        assertThat(afterUnrelated).hasSize(2);
        assertThat(afterUnrelated).extracting(UnresolvedFailureView::runId)
                .containsExactlyInAnyOrder(failedA.toString(), failedB.toString());

        // 同一问题的重试链:对 A 的重试完成后,A 被解决,B 仍然独立展示
        AgentRun retryA = recoveryService.retry(project.id(), failedA);
        agentRunService.complete(retryA.id(), AgentRunStatus.COMPLETED, "done");
        var afterRetryA = unresolved(project.id());
        assertThat(afterRetryA).hasSize(1);
        assertThat(afterRetryA.get(0).runId()).isEqualTo(failedB.toString());
    }

    private void assertThatThrownByConflict(Runnable action, String expectedCode) {
        try {
            action.run();
            org.assertj.core.api.Assertions.fail("expected conflict: " + expectedCode);
        } catch (com.specagent.common.ApiException ex) {
            assertThat(ex.code()).isEqualTo(expectedCode);
        }
    }
}
