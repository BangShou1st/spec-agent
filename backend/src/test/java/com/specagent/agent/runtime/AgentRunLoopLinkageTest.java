package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AgentRunLoopLinkageTest.java
 *
 * 测试目标:Slice 0:验证 {@code AgentRun} 循环关联字段(parentRunId、rootRunId、
 * cycleIndex)的持久化往返;旧数据行读出为 null;claimNextContinue 只领取 CONTINUE
 * 触发类型的 run;按 parentRunId 查找子 run;无锚点的 CONTINUE 循环 fail closed。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentRunLoopLinkageTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;

    @Test
    void childRunRoundTripsParentRootAndCycle() {
        Project project = projectService.createProject("loop-linkage");
        UUID parentId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(rootId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null));
        agentRunRepository.save(new AgentRun(parentId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null,
                rootId, rootId, 0));
        AgentRun child = new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", null, null, Instant.now(), null,
                parentId, rootId, 1);
        agentRunRepository.save(child);

        AgentRun reloaded = agentRunRepository.findById(child.id()).orElseThrow();
        assertThat(reloaded.parentRunId()).isEqualTo(parentId);
        assertThat(reloaded.rootRunId()).isEqualTo(rootId);
        assertThat(reloaded.cycleIndex()).isEqualTo(1);
    }

    @Test
    void legacyRowsReadLinkageAsNull() {
        Project project = projectService.createProject("loop-linkage-legacy");
        AgentRun run = new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null,
                null, null, null);
        agentRunRepository.save(run);

        AgentRun reloaded = agentRunRepository.findById(run.id()).orElseThrow();
        assertThat(reloaded.parentRunId()).isNull();
        assertThat(reloaded.rootRunId()).isNull();
        assertThat(reloaded.cycleIndex()).isNull();
    }

    @Test
    void claimNextContinueRunOnlyClaimsContinueTrigger() {
        Project project = projectService.createProject("loop-linkage-claim");
        AgentRun continued = new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", null, null, Instant.now(), null,
                null, null, null);
        agentRunRepository.save(continued);
        AgentRun draft = runService.createQueuedDraftQuestion(project.id());

        Optional<AgentRun> claimed = runService.claimNextContinue();

        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(continued.id());
        assertThat(runService.claimNextContinue()).isEmpty();
        assertThat(draft.status()).isEqualTo(AgentRunStatus.CREATED);
    }

    @Test
    void findChildByParentRunId() {
        Project project = projectService.createProject("loop-linkage-child");
        UUID parentId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(parentId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null));
        AgentRun child = new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", null, null, Instant.now(), null,
                parentId, parentId, 1);
        agentRunRepository.save(child);

        assertThat(agentRunRepository.findChildByParentRunId(parentId))
                .isPresent()
                .get().extracting(AgentRun::id).isEqualTo(child.id());
        assertThat(agentRunRepository.findChildByParentRunId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void continueCycleWithoutAnchorFailsClosed() {
        // Slice 3B:CONTINUE_CYCLE 通过生产链路真实执行。空路由上无锚点的子 run
        // 会起草根问题(INTERACTION),这会把链暂停在外部边界——它绝不再派生
        // 更深的子 run。
        Project project = projectService.createProject("loop-linkage-stale");
        AgentRun continued = new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", null, null, Instant.now(), null,
                null, null, null);
        agentRunRepository.save(continued);

        AgentRun claimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(continued.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued continuation run " + continued.id()));
        worker.executeRun(claimed);

        assertThat(agentRunRepository.findById(continued.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agentRunRepository.findChildByParentRunId(continued.id())).isEmpty();
    }
}
