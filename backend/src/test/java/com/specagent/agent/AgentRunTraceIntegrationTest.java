package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.AgentRunTraceIntegrationTest;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;

import com.specagent.common.Json;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AgentRunTraceIntegrationTest.java
 *
 * 测试目标:验证 AgentRun 最终 trace 保留完整生命周期的主要步骤,而不是被最后一步覆盖。
 * 答题场景通过异步 ANSWER_CYCLE 驱动,仅规格失败用例对 fake 模型做桩处理。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunTraceIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private Json json;

    @Test
    void answerRunTraceContainsMajorSteps() {
        Project project = projectService.createProject("Trace answer project");
        draftDriver.draftQuestion(project.id());

        var result = answerDriver.submitFreeText(project.id(), "trace the answer loop");

        AgentRun run = agentRunService.getRun(result.run().id()).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("persisted_answer")
                .contains("persisted_patch")
                .contains("completed");
        // 运行事件记录了 2 次调用循环的各阶段推进。
    }



}
