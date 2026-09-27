package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.AgentRunTraceSafetyTest;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;

import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
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
 * 文件名:AgentRunTraceSafetyTest.java
 *
 * 测试目标:验证运行时路径上的 trace 安全性(全程无公网)。持久化的 {@link AgentRun}
 * trace 必须始终可诊断,但绝不携带密钥或原始载荷:不含 API key、Authorization 头、
 * 用户答案文本。即使异常消息本身包含疑似密钥内容,供应商失败也只以安全的
 * {@code failed} 终态 trace 步骤呈现。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunTraceSafetyTest {

    private static final String SECRET_SENTINEL = "«redacted:sk-…»";
    private static final String ANSWER_SENTINEL = "trace safety answer payload 9k2m";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    @Test
    void successfulRunTraceNeverContainsSecretsOrPayload() {
        Project project = projectService.createProject("trace safety");
        draftDriver.draftQuestion(project.id());

        var result = answerDriver.submitFreeText(project.id(), ANSWER_SENTINEL);

        AgentRun run = agentRunService.getRun(result.run().id()).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.trace())
                .doesNotContain(ANSWER_SENTINEL)
                .doesNotContain(SECRET_SENTINEL)
                .doesNotContain("Bearer")
                .doesNotContain("sk-");
    }

    @Test
    void providerFailureCategoryAppearsInTraceWithoutSecretOrMessage() {
        // 通过 spy 模拟错误消息意外回显密钥的供应商失败:trace 必须只保留安全的终态步骤。
        org.mockito.Mockito.doAnswer(invocation -> {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.RATE_LIMITED,
                    "OpenCode request failed " + SECRET_SENTINEL);
        }).when(decisionEngine).runDecision(
                org.mockito.ArgumentMatchers.any(AgentRequestEnvelope.class));
        Project project = projectService.createProject("trace safety failure");

        assertThatThrownBy(() -> draftDriver.draftQuestion(project.id()))
                .isInstanceOf(OpenCodeModelException.class);

        AgentRun run = agentRunService.listByProject(project.id()).get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("failed")
                .doesNotContain(SECRET_SENTINEL)
                .doesNotContain("Bearer");
    }
}
