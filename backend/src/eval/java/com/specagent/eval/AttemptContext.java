package com.specagent.eval;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.agent.trace.SemanticTrace;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AttemptContext.java
 *
 * 用途:一次评测尝试(attempt)前后观察到的权威状态快照。评测框架只负责
 * 观察和编排——这里每个字段都是从 Java 运行时直接读取的事实,绝不由第二套
 * 图模型重新推导,保证评测结果反映生产行为。
 *
 * 协作:由 {@link ScenarioRunner} 在执行场景时构造,供 LayerA/LayerBFast
 * 等分层校验器消费。
 */
public record AttemptContext(
        UUID projectId,
        UUID runId,
        AgentRun run,
        List<AgentRunEventView> events,
        StateSummary preState,
        StateSummary postState,
        Map<String, Integer> stateDelta,
        String actualPrimaryAction,
        String executionResult,
        List<AgentProposal> proposals,
        Map<String, Integer> capabilityInvocations,
        List<UUID> preAnswerIds,
        List<UUID> postAnswerIds,
        ContextSnapshot decisionSnapshot,
        SemanticTrace semanticTrace,
        List<String> observedStages,
        int providerRetries,
        long latencyMs,
        Map<String, Long> stageLatencyMs,
        String failureDetail) {

    /** 运行事件的精简视图(只保留事件类型和负载)。 */
    public record AgentRunEventView(String eventType, Map<String, Object> payload) {
    }
}
