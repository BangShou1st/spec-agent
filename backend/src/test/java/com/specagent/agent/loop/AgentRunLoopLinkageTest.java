package com.specagent.agent.loop;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
 * Slice 0: loop-linkage persistence round-trip for {@code AgentRun}.
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
    void continueCycleExecutionIsAnExplicitSlice0Placeholder() {
        Project project = projectService.createProject("loop-linkage-placeholder");
        AgentRun continued = new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", null, null, Instant.now(), null,
                null, null, null);

        assertThatThrownBy(() -> worker.executeRun(continued))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CONTINUE_CYCLE");
    }
}
