package com.specagent.eval;

import com.specagent.eval.P3ADecisionScoring.DecisionObservation;
import com.specagent.eval.P3ADecisionScoring.PayloadValidity;
import com.specagent.eval.P3ADecisionScoring.Summary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3ADecisionScoringTest.java
 *
 * 测试目标:P3-A C 层计分把四个量严格分开的确定性用例:
 * Action acceptable-set accuracy / Payload schema validity /
 * Runtime execution success / INFRA。特别锁定两个此前被搞错的边界:
 *   * 动作对但参数无效,不得算作 payload valid;
 *   * 动作不在 acceptable 集合但 payload 合法,不得算成 payload invalid。
 */
class P3ADecisionScoringTest {

    private static DecisionObservation observation(String id, String action,
                                                   boolean acceptable, PayloadValidity payload,
                                                   boolean success, boolean infra,
                                                   String protocolClass) {
        return new DecisionObservation(id, action, acceptable, payload, success,
                infra, infra ? "PROVIDER_FAILURE" : null, protocolClass);
    }

    @Test
    void payloadValidWhenActionProducedEvenIfActionNotInAcceptableSet() {
        // 动作不在 acceptable 集合,但动作产出 => 契约校验通过 => payload 合法。
        DecisionObservation wrongAction = observation("s1", "INVOKE_CAPABILITY",
                false, PayloadValidity.VALID, true, false, null);
        Summary summary = P3ADecisionScoring.summarize(List.of(wrongAction));

        assertThat(summary.actionAcceptableSetAccuracy()).isEqualTo(0.0);
        assertThat(summary.payloadValidity()).isEqualTo(1.0);
        assertThat(summary.evaluated()).isEqualTo(1);
    }

    @Test
    void actionCorrectButInvalidPayloadIsNotPayloadValid() {
        // 模型协议违约:payload 校验未通过 => 动作未产出 => 不得记为 payload valid。
        DecisionObservation invalidPayload = observation("s2", null,
                false, PayloadValidity.INVALID, false, false, "MODEL_CONTRACT_VIOLATION");
        Summary summary = P3ADecisionScoring.summarize(List.of(invalidPayload));

        assertThat(summary.payloadValidity()).isEqualTo(0.0);
        assertThat(summary.payloadValidityMeasuredCount()).isEqualTo(1);
        // 没有动作产出 -> acceptable-set accuracy 不可测。
        assertThat(summary.actionAcceptableSetAccuracy()).isNull();
        assertThat(summary.actionMeasuredCount()).isZero();
        assertThat(summary.modelProtocolFailures()).isEqualTo(1);
        assertThat(summary.protocolFailureBreakdown())
                .containsEntry("MODEL_CONTRACT_VIOLATION", 1);
    }

    @Test
    void infraFailuresAreExcludedFromQualityMetrics() {
        DecisionObservation infra = observation("s3", null,
                false, PayloadValidity.NOT_MEASURED, false, true, null);
        DecisionObservation good = observation("s4", "RESPOND_TO_USER",
                true, PayloadValidity.VALID, true, false, null);
        Summary summary = P3ADecisionScoring.summarize(List.of(infra, good));

        assertThat(summary.infraFailures()).isEqualTo(1);
        assertThat(summary.evaluated()).isEqualTo(1);
        assertThat(summary.actionAcceptableSetAccuracy()).isEqualTo(1.0);
        assertThat(summary.payloadValidity()).isEqualTo(1.0);
        assertThat(summary.executionSuccessRate()).isEqualTo(1.0);
        assertThat(summary.infraClassBreakdown()).containsEntry("PROVIDER_FAILURE", 1);
    }

    @Test
    void unmeasurablePayloadIsCountedNotMeasuredAndNotFabricated() {
        // 运行时没有走到 payload 校验(例如 action eligibility 拒绝)-> NOT_MEASURED。
        DecisionObservation notMeasured = observation("s5", null,
                false, PayloadValidity.NOT_MEASURED, false, false, null);
        Summary summary = P3ADecisionScoring.summarize(List.of(notMeasured));

        assertThat(summary.payloadValidity()).isNull();
        assertThat(summary.payloadValidityMeasuredCount()).isZero();
        assertThat(summary.payloadValidityNotMeasuredCount()).isEqualTo(1);
    }

    @Test
    void executionSuccessIsSeparateFromPayloadValidity() {
        // payload 合法但执行失败(例如过期上下文):payload 有效,执行不成功。
        DecisionObservation executedThenFailed = observation("s6", "CREATE_NODE",
                true, PayloadValidity.VALID, false, false, null);
        Summary summary = P3ADecisionScoring.summarize(List.of(executedThenFailed));

        assertThat(summary.payloadValidity()).isEqualTo(1.0);
        assertThat(summary.executionSuccessRate()).isEqualTo(0.0);
    }

    @Test
    void payloadValiditySignalUsesActionPresenceAndProtocolFailure() {
        assertThat(P3ADecisionScoring.payloadValidity("RESPOND_TO_USER", null))
                .isEqualTo(PayloadValidity.VALID);
        assertThat(P3ADecisionScoring.payloadValidity(null, "MODEL_CONTRACT_VIOLATION"))
                .isEqualTo(PayloadValidity.INVALID);
        assertThat(P3ADecisionScoring.payloadValidity(null, null))
                .isEqualTo(PayloadValidity.NOT_MEASURED);
    }

    @Test
    void protocolFailuresAreClassifiedSeparatelyFromInfra() {
        assertThat(P3ADecisionScoring.classifyModelProtocolFailure(
                "failed:MODEL_CONTRACT_VIOLATION", List.of()))
                .isEqualTo("MODEL_CONTRACT_VIOLATION");
        assertThat(P3ADecisionScoring.classifyModelProtocolFailure(
                "failed:AgentContractException: questionText must be a string", List.of()))
                .isEqualTo("MODEL_CONTRACT_VIOLATION");
        assertThat(P3ADecisionScoring.classifyModelProtocolFailure(
                "failed:model output is not valid JSON", List.of()))
                .isEqualTo("MODEL_OUTPUT_SCHEMA");
        assertThat(P3ADecisionScoring.classifyModelProtocolFailure(
                "failed:STATE_UPDATE contract: Extra inputs are not permitted",
                List.of("InvalidJsonError")))
                .isEqualTo("MODEL_OUTPUT_SCHEMA");
        // 真正的网络/provider 失败不属于模型协议违约。
        assertThat(P3ADecisionScoring.classifyModelProtocolFailure(
                "failed:AgentBrainUnavailableException: inference broker returned 502",
                List.of("AgentBrainUnavailableException")))
                .isNull();
    }
}
