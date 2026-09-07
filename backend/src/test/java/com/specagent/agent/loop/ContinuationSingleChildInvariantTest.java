package com.specagent.agent.loop;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 2 review closure: the database itself guarantees one continuation
 * child per parent run.
 *
 * <p>This test proves the {@code V23} partial unique index
 * {@code UNIQUE(parent_run_id) WHERE parent_run_id IS NOT NULL} — not the
 * application idempotency key. Both children therefore use distinct run ids
 * and distinct idempotency keys, and both inserts go straight through
 * {@link AgentRunRepository#save}, bypassing
 * {@code ContinuationCoordinator.continueIfEligible} and
 * {@code RunService.createContinueRun} on purpose.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContinuationSingleChildInvariantTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private AgentRunRepository agentRunRepository;

    @Test
    void secondChildWithSameParentIsRejectedByDatabase() {
        Project project = projectService.createProject("single-child-invariant");
        UUID parentId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(parentId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.COMPLETED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null));

        agentRunRepository.save(new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", "key-B", null, Instant.now(), null,
                parentId, parentId, 1));

        assertThatThrownBy(() -> agentRunRepository.save(new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", "key-C", null, Instant.now(), null,
                parentId, parentId, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void multipleRootsWithNullParentAreUnaffected() {
        Project project = projectService.createProject("single-child-null-roots");
        for (int i = 0; i < 3; i++) {
            agentRunRepository.save(new AgentRun(UUID.randomUUID(), project.id(),
                    project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                    null, null, null, null, null, null, AgentRunStatus.CREATED,
                    "{}", "DRAFT_QUESTION", null, null, Instant.now(), null));
        }

        assertThat(agentRunRepository.findByProject(project.id())).hasSize(3);
    }
}
