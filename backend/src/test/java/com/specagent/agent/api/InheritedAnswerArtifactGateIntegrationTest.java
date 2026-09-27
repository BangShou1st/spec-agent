package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunFailureReasons;
import com.specagent.agent.runtime.IncompleteAnswerCycleException;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.agent.snapshot.LegacyFrozenInputUnavailableException;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.spec.SpecSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:InheritedAnswerArtifactGateIntegrationTest.java
 *
 * 测试目标:规格门禁必须看到规格上下文真正使用的答案——路线自身答案加上合法的
 * 继承前缀,而不只是路线自己的 tip 答案。P1-B 回归:从"STATE_UPDATE 未完成"的已回答
 * 节点分叉时,该答案被继承进分支上下文,而入队与执行前两道门禁都只检查分支自己的
 * tip 答案,导致分支可能生成一份看似完整却静默遗漏用户继承答案的规格。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 刻意不加 @Transactional(与 TypedRunFailureIntegrationTest 同理):失败终态化与
// RUN_FAILED 事件在独立的 REQUIRES_NEW 事务内原子提交——第四轮所有权协议的一致性
// 要求;外层测试事务会让 REQUIRES_NEW 看不到未提交的 run 行,从而掩盖本套件要断言
// 的那条记录。生产中 claim 事务在 worker 执行之前已经提交,本形态与之完全一致。
class InheritedAnswerArtifactGateIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private SpecSnapshotService specSnapshotService;
    @Autowired
    private RunService runService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private AgentRunEventService eventService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private RouteHistoryResolver routeHistoryResolver;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private final java.util.List<UUID> touchedProjectIds = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanUpRunRows() {
        for (UUID projectId : touchedProjectIds) {
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    java.util.Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    java.util.Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :projectId",
                    java.util.Map.of("projectId", projectId));
        }
    }

    /** 路线上有一个已回答的根问题,其 STATE_UPDATE 从未执行。 */
    private Project projectWithAnsweredRoot() {
        Project project = projectService.createProject("Inherited gate project");
        touchedProjectIds.add(project.id());
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the goal?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), root.id(),
                null, "goal answer", "user");
        return project;
    }

    private Route forkFromRoot(Project project) {
        UUID rootId = routeService.getRoute(project.activeRouteId()).orElseThrow().rootNodeId();
        return routeService.forkFromNode(project.id(), project.activeRouteId(), rootId,
                "branch route");
    }

    /** 给路线的每个有效答案补上检查点补丁。 */
    private void processAllEffectiveAnswers(UUID routeId) {
        Route route = routeService.getRoute(routeId).orElseThrow();
        List<UUID> lineage = routeHistoryResolver.resolveLineage(route.tipNodeId());
        for (Answer answer : routeHistoryResolver.resolveEffectiveAnswers(routeId, lineage)) {
            answerPatchService.save(answer.projectId(), answer.routeId(), answer.nodeId(),
                    answer.id(), List.of(new Claim(null, ClaimKind.fromCode("goal"),
                            "processed claim for " + answer.id(), ClaimStatus.fromCode("confirmed"),
                            0.9, answer.nodeId(), answer.id())), null);
        }
    }

    private List<Map<String, Object>> failedEvents(UUID runId) {
        return eventService.findByRunId(runId).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .map(AgentRunEvent::payload)
                .toList();
    }

    @Test
    void draftCannotAdvancePastAnUnprocessedAnswer() throws Exception {
        Project project = projectWithAnsweredRoot();
        UUID routeId = project.activeRouteId();
        UUID rootId = routeService.getRoute(routeId).orElseThrow().tipNodeId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"DRAFT_QUESTION\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"))
                .andExpect(jsonPath("$.details.answerId").isNotEmpty())
                .andExpect(jsonPath("$.details.routeId").value(routeId.toString()))
                .andExpect(jsonPath("$.details.nodeId").value(rootId.toString()));

        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId()).isEqualTo(rootId);
        assertThat(runService.claimNext()).isEmpty();
    }

    @Test
    void queuedDraftFailsClosedIfTheAnswerBecomesUnprocessedBeforeExecution() {
        Project project = projectService.createProject("Queued draft race " + UUID.randomUUID());
        touchedProjectIds.add(project.id());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "What is required?", null, List.of(), true);
        AgentRun queued = runService.createQueuedDraftQuestion(project.id());
        answerService.finalizeAnswer(project.id(), routeId, root.id(), null,
                "saved before claim", "user");

        AgentRun claimed = runService.claimDecisionCycleRun(queued.id()).orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(IncompleteAnswerCycleException.class);

        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId()).isEqualTo(root.id());
        assertThat(failedEvents(queued.id())).anySatisfy(payload ->
                assertThat(payload).containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE));
    }

    @Test
    void enqueueIsRejectedWhileTheInheritedAnswerIsUnprocessed() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);

        // 分支自己的 tip 是干净的(没有路线本地答案),但规格上下文会通过分叉点
        // 继承那个未处理的答案。
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"));

        assertThat(runService.claimNextArtifact()).isEmpty();
        assertThat(specSnapshotService.listByRoute(fork.id())).isEmpty();
    }

    @Test
    void queuedGenerationFailsClosedOnTheInheritedUnprocessedAnswer() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);

        // 入队门禁现在会正确拒绝这种状态(409);为了直接测试执行器门禁,
        // 假装在继承答案变未处理之前就已入队,直接经 RunService 排队该 run。
        AgentRun run = runService.createQueuedArtifactGeneration(
                project.id(), null, "gate", fork.id());

        AgentRun claimed = runService.claimNextArtifact().orElseThrow();
        assertThat(claimed.routeId()).isEqualTo(fork.id());
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(RuntimeException.class);

        assertThat(specSnapshotService.listByRoute(fork.id())).isEmpty();
        List<Map<String, Object>> failures = failedEvents(run.id());
        assertThat(failures).isNotEmpty();
        assertThat(failures.get(0))
                .containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE);
    }

    @Test
    void unprocessedAnswerThatIsNoLongerTheTipIsStillCaughtByTheEffectiveHistory() throws Exception {
        // 历史兼容夹具:直接构造这个现在已非法的状态。修复后的 DRAFT 路径绝不
        // 会产生这种状态;该夹具为旧路径写入的行保留覆盖。
        Project project = projectWithAnsweredRoot();
        Route source = routeService.getRoute(project.activeRouteId()).orElseThrow();
        UUID rootId = source.rootNodeId();
        Node historicalTip = nodeService.createChildNode(project.id(), source.id(), rootId,
                "Legacy later question?", null, List.of(), true);
        assertThat(historicalTip.id()).isEqualTo(
                routeService.getRoute(source.id()).orElseThrow().tipNodeId());

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"))
                .andExpect(jsonPath("$.details.answerId").isNotEmpty())
                .andExpect(jsonPath("$.details.routeId").value(source.id().toString()))
                .andExpect(jsonPath("$.details.nodeId").value(rootId.toString()));
    }

    @Test
    void nonTipUnprocessedAnswerCanBeRecoveredThroughTheFormalEntryPoint() throws Exception {
        Project project = projectService.createProject("Historical recovery project " + UUID.randomUUID());
        touchedProjectIds.add(project.id());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "What must be preserved?", null, List.of(), true);

        // 兼容夹具:给产生该答案的 run 附加原始的答案前 ContextSnapshot。
        // 随后用一次旧式直接节点写入把答案变为非 tip,复现旧 bug 造出的状态。
        AgentRun original = runService.createQueuedRunWithInputResult(
                project.id(), "ANSWER_TIP", root.id(), null, null, null, null);
        var originalSnapshot = contextBuilder.buildForRoute(
                project.id(), routeId, root.id(), original.id(), ContextOperationType.NORMAL);
        agentRunService.attachContext(original.id(), originalSnapshot.id(), "legacy_fixture_context");
        Answer answer = answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "preserve the constraint", "user");
        agentRunService.markPersistedAnswer(original.id(), answer.id(), "legacy_fixture_answer");
        agentRunService.complete(original.id(), AgentRunStatus.FAILED, "legacy_fixture_incomplete");
        Node later = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Legacy later question?", null, List.of(), true);

        MvcResult recovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + answer.id() + "\", \"nodeId\": \"" + root.id()
                                        + "\", \"sourceRouteId\": \"" + routeId + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID recoveryRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(recovery.getResponse().getContentAsString())
                        .get("runId").asText());
        worker.executeRun(runService.claimAnswerCycleRun(recoveryRunId).orElseThrow());

        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).hasValueSatisfying(patch -> {
            assertThat(patch.routeId()).isEqualTo(routeId);
            assertThat(patch.sourceNodeId()).isEqualTo(root.id());
            assertThat(patch.sourceAnswerId()).isEqualTo(answer.id());
            assertThat(patch.claims()).isNotEmpty();
            assertThat(patch.claims()).allSatisfy(claim -> {
                if (claim.sourceAnswerId() != null) {
                    assertThat(claim.sourceAnswerId()).isEqualTo(answer.id());
                }
                if (claim.sourceNodeId() != null) {
                    assertThat(claim.sourceNodeId()).isEqualTo(root.id());
                }
            });
        });
        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId()).isEqualTo(later.id());
        assertThat(answerService.findAnswersForRouteAndNodeIds(routeId, List.of(root.id())))
                .hasSize(1);
        assertThat(eventService.findByRunId(recoveryRunId).stream()
                .map(AgentRunEvent::eventType))
                .contains("HISTORICAL_ANSWER_RECOVERED")
                .doesNotContain("DECISION_STARTED");
    }

    @Test
    void historicalRecoveryWithoutOriginalSnapshotFailsClosed() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route source = routeService.getRoute(project.activeRouteId()).orElseThrow();
        UUID rootId = source.rootNodeId();
        Answer answer = answerService.findAnswerForNode(source.id(), rootId).orElseThrow();
        nodeService.createChildNode(project.id(), source.id(), rootId,
                "Legacy later question without snapshot?", null, List.of(), true);

        MvcResult recovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + answer.id() + "\", \"nodeId\": \"" + rootId
                                        + "\", \"sourceRouteId\": \"" + source.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID recoveryRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(recovery.getResponse().getContentAsString())
                        .get("runId").asText());

        assertThatThrownBy(() -> worker.executeRun(
                runService.claimAnswerCycleRun(recoveryRunId).orElseThrow()))
                .isInstanceOf(LegacyFrozenInputUnavailableException.class)
                .hasMessageContaining("LEGACY_FROZEN_INPUT_UNAVAILABLE");
        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).isEmpty();
        assertThat(routeService.getRoute(source.id()).orElseThrow().tipNodeId())
                .isNotEqualTo(rootId);
    }

    @Test
    void branchCanGenerateOnceEveryInheritedAnswerIsProcessed() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);
        Route source = routeService.getRoute(fork.sourceRouteId()).orElseThrow();
        UUID rootId = source.rootNodeId();
        Answer inherited = answerService.findAnswerForNode(source.id(), rootId).orElseThrow();

        // 在所属路线上使用正式恢复入口。这不是直接的 AnswerPatch 写入:运行时
        // 复用不可变答案、完成其检查点,并保持分支 tip 不被触碰。
        MvcResult recovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + inherited.id() + "\", \"nodeId\": \"" + rootId
                                        + "\", \"sourceRouteId\": \"" + source.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID recoveryRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(recovery.getResponse().getContentAsString())
                        .get("runId").asText());
        worker.executeRun(runService.claimAnswerCycleRun(recoveryRunId).orElseThrow());

        assertThat(answerPatchService.findBySourceAnswerId(inherited.id())).isPresent();
        assertThat(answerService.findAnswersForRouteAndNodeIds(source.id(), List.of(rootId)))
                .hasSize(1);
        UUID sourceTipAfterRecovery = routeService.getRoute(source.id()).orElseThrow().tipNodeId();
        assertThat(routeService.getRoute(fork.id()).orElseThrow().tipNodeId())
                .isEqualTo(rootId);

        // 第二次点击属于历史性、已有检查点的恢复。它必须是持久的空操作:
        // 不产生第二条 STATE_UPDATE、Answer、Patch 或任何图变更。
        MvcResult repeatedRecovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + inherited.id() + "\", \"nodeId\": \"" + rootId
                                        + "\", \"sourceRouteId\": \"" + source.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID repeatedRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(repeatedRecovery.getResponse().getContentAsString())
                        .get("runId").asText());
        worker.executeRun(runService.claimAnswerCycleRun(repeatedRunId).orElseThrow());
        assertThat(routeService.getRoute(source.id()).orElseThrow().tipNodeId())
                .isEqualTo(sourceTipAfterRecovery);
        assertThat(answerService.findAnswersForRouteAndNodeIds(source.id(), List.of(rootId)))
                .hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(inherited.id())).hasValueSatisfying(patch ->
                assertThat(patch.id()).isNotNull());
        assertThat(eventService.findByRunId(repeatedRunId).stream()
                .map(AgentRunEvent::eventType))
                .contains("STATE_UPDATE_SKIPPED")
                .doesNotContain("DECISION_STARTED");

        // 分支现在通过有效历史门禁,它自己的制品 run 可以按 id 认领;
        // 不做共享队列的排空。
        MvcResult artifact = mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\", \"sourceRouteId\": \""
                                + fork.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID artifactRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(artifact.getResponse().getContentAsString())
                        .get("runId").asText());
        AgentRun claimedArtifact = runService.claimArtifactRun(artifactRunId).orElseThrow();
        worker.executeRun(claimedArtifact);
        assertThat(specSnapshotService.listByRoute(fork.id())).hasSize(1);
    }

    @Test
    void unrelatedSiblingRouteWithUnprocessedAnswerDoesNotBlock() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);
        processAllEffectiveAnswers(fork.id());

        // 同一项目的第二个分支保留了一个未处理的 tip 答案;除了已处理的根之外
        // 它与分支不共享任何 lineage 材料,绝不能阻塞分支的生成。
        Route source = routeService.getRoute(fork.sourceRouteId()).orElseThrow();
        Node siblingTip = nodeService.createChildNode(project.id(), source.id(), source.tipNodeId(),
                "Sibling question?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), source.id(), siblingTip.id(),
                null, "sibling answer", "user");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isAccepted());

        assertThat(runService.claimNextArtifact()).isPresent();
    }
}
