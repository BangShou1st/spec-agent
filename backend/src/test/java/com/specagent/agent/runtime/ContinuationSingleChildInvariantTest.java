package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:ContinuationSingleChildInvariantTest.java
 *
 * 测试目标:Slice 2 评审收尾:由数据库本身保证每个父 run 只有一个续跑子 run。
 *
 * 本测试证明的是 {@code V23} 部分唯一索引
 * {@code UNIQUE(parent_run_id) WHERE parent_run_id IS NOT NULL}——而不是应用层
 * 幂等 key。因此两个子 run 使用不同的 run id 和不同的幂等 key,且两次插入都直接
 * 走 {@link AgentRunRepository#save},刻意绕过
 * {@code ContinuationCoordinator.continueIfEligible} 和
 * {@code RunService.createContinueRun}。
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
