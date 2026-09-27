package com.specagent.eval;

import com.specagent.agent.trace.SemanticTraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SemanticTraceBehaviorPreservationIntegrationTest.java
 *
 * 测试目标:同一个确定性场景,分别在语义追踪关闭与开启时运行。
 * 唯一容忍的差异是运行期生成的 UUID/上下文哈希;把这些身份归一化后,
 * 全部可观测行为与精确的序列化模型请求都必须保持一致。
 *
 * 【隔离性备注(间歇性失败,2026-09-20)】。在整套测试运行中,本测试
 * 偶发失败:"on" 尝试产生零个大脑请求,运行结果报告
 * {@code failed:IllegalStateException: No queued answer-cycle run}:工具链
 * 已入队自己的 ANSWER_CYCLE 运行,但共享队列的认领
 * ({@code claimNextAnswerCycle},"全表中最老的排队运行")在那一刻找不到
 * 可认领项。场景本身是确定性的:本类单独运行通过,且整个 eval 包连续 5/5
 * 次通过,因此触发因素是同一 JVM/DB 中的跨测试状态,而非场景语义。
 *
 * 修复:工具链现在认领它自己入队的那条运行
 * ({@code RunService.claimAnswerCycleRun(runId)},即 {@code AnswerCycleTestDriver}
 * 已在用的按 id 守卫),共享队列既不能把别人的运行递给它,也不能让自己的运行
 * 藏在其他测试的顺序后面。执行结果也改为在请求载荷之前比较,这样任何残留失败
 * 都会指出自身原因,而不是空洞的"空列表 vs 单元素列表"差异。
 *
 * 残留不确定性/后续:确切的交错时序未能按需复现。已在此排除:
 * {@code ScriptedBrain.requestPayloads} 跨尝试泄漏(每次运行的
 * {@code install()} 都会清空)和后台 {@code RunWorkerPoller} 泄漏
 * (启用 worker 的测试上下文在本包之前刚以 50 ms 轮询间隔压测过共享 DB,
 * 未产生干扰)。
 */
class SemanticTraceBehaviorPreservationIntegrationTest extends EvalHarnessBase {

    @Autowired
    private SemanticTraceRecorder semanticTraceRecorder;

    @AfterEach
    void restoreTraceSetting() {
        semanticTraceRecorder.setEnabledForTesting(true);
    }

    @Test
    void tracingDoesNotChangeRequestsCallsStateActionsOrEvaluation() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().get(0);

        semanticTraceRecorder.setEnabledForTesting(false);
        ObservationEnvelope off = runScenario(scenario, variant);
        List<String> offRequests = scriptedBrain.requestPayloads();
        java.util.UUID offProject = scenarioRunner.lastProjectId();
        cleanUpProject(offProject);

        semanticTraceRecorder.setEnabledForTesting(true);
        ObservationEnvelope on = runScenario(scenario, variant);
        List<String> onRequests = scriptedBrain.requestPayloads();

        // 在请求载荷序列之前先比较执行结果:当 "on" 尝试根本没到达大脑时,
        // 执行结果能直接指出原因,而不是留下含糊的"空载荷 vs 单载荷"差异。
        assertThat(on.executionResult())
                .as("on attempt must execute identically (off=%s, on violations=%s)",
                        off.executionResult(), on.violations())
                .isEqualTo(off.executionResult());
        assertThat(onRequests.stream().map(this::normalizeDynamicIdentity).toList())
                .containsExactlyElementsOf(offRequests.stream()
                        .map(this::normalizeDynamicIdentity).toList());
        assertThat(on.productionModelCalls()).isEqualTo(off.productionModelCalls());
        assertThat(on.providerRetries()).isEqualTo(off.providerRetries());
        assertThat(on.actualPrimaryAction()).isEqualTo(off.actualPrimaryAction());
        assertThat(on.executionResult()).isEqualTo(off.executionResult());
        assertThat(on.stateDelta()).isEqualTo(off.stateDelta());
        assertThat(on.violations()).isEqualTo(off.violations());
        assertThat(off.semanticTrace().stages()).isEmpty();
        assertThat(on.semanticTrace().stages()).containsKeys(
                "STATE_UPDATE_INPUT", "STATE_UPDATE_OUTPUT",
                "POST_STATE_UPDATE_STATE", "DECISION_INPUT", "DECISION_OUTPUT",
                "ACTION_ELIGIBILITY", "FINAL_RESULT");
        assertThat(on.semanticTrace().stages().get("ACTION_ELIGIBILITY"))
                .containsEntry("mode", "SHADOW")
                .containsEntry("selected_action", on.actualPrimaryAction());
    }

    private String normalizeDynamicIdentity(String value) {
        return value.replaceAll(
                        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        "<uuid>")
                .replaceAll("(?i)[0-9a-f]{64}", "<hash>");
    }
}
