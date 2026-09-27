package com.specagent.agent.decision;

import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AutonomyInputs;
import com.specagent.agent.protocol.CapabilityDescriptor;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.protocol.LineageEntry;
import com.specagent.agent.protocol.NodeBodyView;
import com.specagent.agent.protocol.NodeView;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.RouteContextView;
import com.specagent.agent.protocol.SnapshotMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:DeterministicEngineFaultPlanDraftInjectionTest.java
 *
 * 测试目标:起草失败的确定性注入([[fail-decision:N]],仅 engine=fake
 * 生效的测试基础设施):
 * - 回答文本中的指令武装对后续独立起草 run 的失败预算,回答周期自身的
 *   STATE_UPDATE 与内部 DECISION(kind=ANSWER_SUBMITTED)完全不受影响;
 * - 独立起草(kind=CONTINUE,锚定同节点)按预算逐次失败;
 * - 只读 NODE_QUERY 不消耗预算;
 * - 无指令的普通文本不武装任何东西——注入机制对生产普通输入零影响。
 */
class DeterministicEngineFaultPlanDraftInjectionTest {

    private final DeterministicEngineFaultPlan faultPlan = new DeterministicEngineFaultPlan();
    private final LocalDeterministicDecisionEngine engine =
            new LocalDeterministicDecisionEngine(faultPlan);

    @Test
    void answerTextArmsDraftFailuresWithoutDisturbingTheAnswerCycle() {
        UUID nodeId = UUID.randomUUID();
        var answerRequest = request("ANSWER_SUBMITTED", nodeId,
                "请记录 [[fail-decision:2]] 作为背景说明");

        // 回答周期自身完全不受影响:STATE_UPDATE 正常,内部 DECISION 正常
        assertThatCode(() -> engine.runStateUpdate(answerRequest))
                .doesNotThrowAnyException();
        assertThatCode(() -> engine.runDecision(answerRequest))
                .doesNotThrowAnyException();
        assertThat(faultPlan.remainingFailures(nodeId)).isZero();

        // 第一次独立起草:失败
        assertThatThrownBy(() -> engine.runDecision(request("CONTINUE", nodeId, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETERMINISTIC_ENGINE_FAULT");
        // 第二次独立起草:仍失败(武装了两次)
        assertThatThrownBy(() -> engine.runDecision(request("CONTINUE", nodeId, null)))
                .isInstanceOf(IllegalStateException.class);
        // 预算耗尽:第三次起草成功
        assertThatCode(() -> engine.runDecision(request("CONTINUE", nodeId, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void nodeQueryDoesNotConsumeArmedBudget() {
        UUID nodeId = UUID.randomUUID();
        engine.runStateUpdate(request("ANSWER_SUBMITTED", nodeId, "[[fail-decision:1]]"));

        // 只读查询不消耗预算,也不被注入打断
        assertThatCode(() -> engine.runDecision(request("NODE_QUERY", nodeId, null)))
                .doesNotThrowAnyException();
        // 预算未被查询消耗:随后的起草仍然按武装失败,再下一次才成功
        assertThatThrownBy(() -> engine.runDecision(request("CONTINUE", nodeId, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETERMINISTIC_ENGINE_FAULT");
        assertThatCode(() -> engine.runDecision(request("CONTINUE", nodeId, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void plainAnswerTextArmsNothing() {
        UUID nodeId = UUID.randomUUID();
        engine.runStateUpdate(request("ANSWER_SUBMITTED", nodeId,
                "普通回答,提到 fail-decision 与 [[其他格式]] 都不构成指令"));
        assertThatCode(() -> engine.runDecision(request("CONTINUE", nodeId, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> engine.runDecision(request("CONTINUE", UUID.randomUUID(), null)))
                .doesNotThrowAnyException();
    }

    private AgentRequestEnvelope request(String kind, UUID anchorNodeId, String freeText) {
        var snapshot = new AgentInputSnapshot(
                UUID.randomUUID().toString(), "context-hash", UUID.randomUUID(),
                UUID.randomUUID(), anchorNodeId,
                new RouteContextView(UUID.randomUUID(), anchorNodeId, null),
                List.of(new LineageEntry(
                        new NodeView(anchorNodeId,
                                new NodeBodyView("Q", List.of(), true), "INTERACTION"),
                        null, List.of())),
                List.of(),
                new SnapshotMetadata("draft injection test"), List.of(),
                List.<CapabilityDescriptor>of(), List.of(), List.of(), List.of(),
                new AutonomyInputs("ADVISOR"));
        return new AgentRequestEnvelope(
                "agent-input.v2", UUID.randomUUID(),
                new AgentEvent(kind, anchorNodeId, null, freeText),
                snapshot, List.of(), new DecisionBudget(2), null);
    }

    @Test
    void delayDirectiveArmsFromAnswerTextAndAppliesToIndependentDraftsOnly() {
        UUID nodeId = UUID.randomUUID();
        var answerRequest = request("ANSWER_SUBMITTED", nodeId,
                "背景 [[delay-decision-ms:1]] 说明");

        // 回答周期自身(STATE_UPDATE 武装 + 内部 DECISION)不受延迟影响:
        // 若内部 DECISION 被延迟,本测试将耗时 1ms 以上——确定性断言改用
        // 独立起草的调用次数来验证预算,而非墙钟时间。
        assertThatCode(() -> engine.runStateUpdate(answerRequest))
                .doesNotThrowAnyException();
        assertThatCode(() -> engine.runDecision(answerRequest))
                .doesNotThrowAnyException();

        // 无指令的 NODE_QUERY 不消耗延迟预算
        assertThatCode(() -> engine.runDecision(request("NODE_QUERY", nodeId, null)))
                .doesNotThrowAnyException();

        // 延迟(1ms)不改变引擎输出契约:三次武装调用(自治续跑/首次起草/
        // 重试)全部正常返回,普通文本零影响
        for (int i = 0; i < DeterministicEngineFaultPlan.DECISION_DELAY_CALL_BUDGET; i++) {
            assertThatCode(() -> engine.runDecision(request("CONTINUE", nodeId, null)))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * [[fail-artifact:N]](R5-A 的浏览器验收基础设施):武装锚定已回答节点,
     * 引爆按规格快照的有效历史(lineage)匹配——规格生成信封的锚是路线 tip,
     * 而自治续跑会推进 tip,按锚点匹配永远打不中。
     */
    @Test
    void answerTextArmsArtifactFailuresMatchedBySnapshotLineage() {
        UUID answeredNode = UUID.randomUUID();
        UUID tipNode = UUID.randomUUID();
        engine.runStateUpdate(request("ANSWER_SUBMITTED", answeredNode, "[[fail-artifact:1]]"));

        // 回答周期自身的 STATE_UPDATE 不受影响(上面已正常返回)。
        // 第一次规格生成:有效历史包含已武装节点 → 确定性失败,
        // 即使生成时的锚点是自治续跑推进后的 tip。
        assertThatThrownBy(() -> engine.runArtifactGeneration(
                artifactRequest(tipNode, List.of(answeredNode, tipNode))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETERMINISTIC_ENGINE_FAULT");
        // 预算恰好一次:失败后的重试规格生成成功。
        assertThatCode(() -> engine.runArtifactGeneration(
                artifactRequest(tipNode, List.of(answeredNode, tipNode))))
                .doesNotThrowAnyException();
    }

    @Test
    void artifactGenerationWithoutArmedNodesInLineageIsUnaffected() {
        UUID answeredNode = UUID.randomUUID();
        UUID unrelatedTip = UUID.randomUUID();
        engine.runStateUpdate(request("ANSWER_SUBMITTED", answeredNode, "[[fail-artifact:1]]"));

        // 无关路线的规格生成(有效历史不含已武装节点)完全不受影响,
        // 也不消耗预算:随后的武装路线生成仍按声明失败。
        assertThatCode(() -> engine.runArtifactGeneration(
                artifactRequest(unrelatedTip, List.of(unrelatedTip))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> engine.runArtifactGeneration(
                artifactRequest(unrelatedTip, List.of(answeredNode))))
                .isInstanceOf(IllegalStateException.class);

        // 无指令的普通回答武装不了任何东西——注入机制对普通输入零影响。
        UUID plainNode = UUID.randomUUID();
        engine.runStateUpdate(request("ANSWER_SUBMITTED", plainNode, "普通文本"));
        assertThatCode(() -> engine.runArtifactGeneration(
                artifactRequest(plainNode, List.of(plainNode))))
                .doesNotThrowAnyException();
    }

    /** 规格生成信封:锚点是路线 tip,有效历史由调用方给定的节点构成。 */
    private AgentRequestEnvelope artifactRequest(UUID tipNodeId, List<UUID> lineageNodeIds) {
        var lineage = lineageNodeIds.stream()
                .map(id -> new LineageEntry(
                        new NodeView(id, new NodeBodyView("Q", List.of(), true), "INTERACTION"),
                        null, List.of()))
                .toList();
        var snapshot = new AgentInputSnapshot(
                UUID.randomUUID().toString(), "context-hash", UUID.randomUUID(),
                UUID.randomUUID(), tipNodeId,
                new RouteContextView(UUID.randomUUID(), tipNodeId, null),
                lineage, List.of(),
                new SnapshotMetadata("artifact injection test"),
                List.of("context:" + UUID.randomUUID()),
                List.of(), null, List.of(), List.of(), List.of(), List.of(),
                new AutonomyInputs("ADVISOR"));
        return new AgentRequestEnvelope(
                "agent-input.v2", UUID.randomUUID(),
                new AgentEvent("CONTINUE", tipNodeId, null, null),
                snapshot, List.of(), new DecisionBudget(1), null);
    }
}