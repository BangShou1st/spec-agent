package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.specagent.eval.P3BDecisionQuality.FailureClassification.*;
import static com.specagent.eval.P3BDecisionQuality.GateOutcome.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Pure aggregation regression: missing gates must not dilute error denominators. */
class P3BGateMeasurementTest {
    private static final Map<String, P3BCorpus.P3BOracle> ORACLES = Map.of(
            "pass", oracle(P3BCorpus.GateExpectation.PASS, false),
            "veto", oracle(P3BCorpus.GateExpectation.VETO, false),
            "gap", oracle(P3BCorpus.GateExpectation.VETO, true),
            "semantic", oracle(P3BCorpus.GateExpectation.NO_DETERMINISTIC_SURFACE, false));

    @Test
    void unknownGateCannotDiluteFalseNegativeDenominator() {
        var summary = summarize(observation("pass", VETOED, NONE),
                observation("pass", UNKNOWN, UNKNOWN_FAILURE));
        assertThat(summary.eligibilityFalseNegatives()).isEqualTo(1);
        assertThat(summary.eligibilityFalseNegativeDenominator()).isEqualTo(1);
        assertThat(summary.gateAccuracy()).isEqualTo(0.0);
        assertThat(summary.eligibilityUnknownCount()).isEqualTo(1);
    }

    @Test
    void unknownGateCannotDiluteFalsePositiveDenominator() {
        var summary = summarize(observation("veto", PASSED, NONE),
                observation("veto", UNKNOWN, MODEL_PROTOCOL_VIOLATION));
        assertThat(summary.eligibilityFalsePositives()).isEqualTo(1);
        assertThat(summary.eligibilityFalsePositiveDenominator()).isEqualTo(1);
        assertThat(summary.gateMeasuredCount()).isEqualTo(1);
        assertThat(summary.eligibilityUnknownCount()).isEqualTo(1);
    }

    @Test
    void measuredCorrectAndIncorrectGatesBothRemainInTheirDenominators() {
        var summary = summarize(observation("pass", PASSED, NONE),
                observation("pass", VETOED, NONE), observation("veto", PASSED, NONE),
                observation("veto", VETOED, NONE));
        assertThat(summary.eligibilityFalseNegativeDenominator()).isEqualTo(2);
        assertThat(summary.eligibilityFalsePositiveDenominator()).isEqualTo(2);
        assertThat(summary.eligibilityFalseNegatives()).isEqualTo(1);
        assertThat(summary.eligibilityFalsePositives()).isEqualTo(1);
        assertThat(summary.gateAccuracy()).isEqualTo(0.5);
    }

    @Test
    void absentGatesLeaveZeroDenominatorsAndUnmeasuredQuality() {
        var summary = summarize(observation("pass", UNKNOWN, UNKNOWN_FAILURE),
                observation("veto", UNKNOWN, UNKNOWN_FAILURE));
        assertThat(summary.eligibilityFalseNegativeDenominator()).isZero();
        assertThat(summary.eligibilityFalsePositiveDenominator()).isZero();
        assertThat(summary.gateAccuracy()).isNull();
        assertThat(summary.gateMeasuredCount()).isZero();
        assertThat(summary.eligibilityUnknownCount()).isEqualTo(2);
        assertThat(summary.payloadValidity()).isNull();
        assertThat(summary.modelSelectionAccuracy()).isNull();
    }

    @Test
    void knownGapsRemainVisibleButCannotOffsetMeasuredVetoDenominator() {
        var summary = summarize(observation("veto", VETOED, NONE),
                observation("gap", UNKNOWN, UNKNOWN_FAILURE),
                observation("gap", PASSED, NONE));
        assertThat(summary.eligibilityFalsePositiveDenominator()).isEqualTo(1);
        assertThat(summary.eligibilityFalsePositives()).isZero();
        assertThat(summary.knownEligibilityGapsObserved()).isEqualTo(2);
        assertThat(summary.eligibilityUnknownCount()).isEqualTo(1);
    }

    @Test
    void infrastructureAndSemanticOnlyCasesNeverEnterGateErrorDenominators() {
        var summary = summarize(observation("pass", VETOED, RATE_LIMITED),
                observation("veto", PASSED, PROVIDER_ERROR),
                observation("semantic", PASSED, NONE));
        assertThat(summary.infraFailures()).isEqualTo(2);
        assertThat(summary.eligibilityFalseNegativeDenominator()).isZero();
        assertThat(summary.eligibilityFalsePositiveDenominator()).isZero();
        assertThat(summary.gateAccuracy()).isNull();
        assertThat(summary.semanticOnlyNotScored()).isEqualTo(1);
    }

    private static P3BCorpus.P3BOracle oracle(P3BCorpus.GateExpectation gate, boolean gap) {
        return new P3BCorpus.P3BOracle("unit", gate, Set.of(), gap, null, false, false);
    }

    private static P3BDecisionQuality.DecisionObservation observation(
            String scenario, P3BDecisionQuality.GateOutcome gate,
            P3BDecisionQuality.FailureClassification failure) {
        return new P3BDecisionQuality.DecisionObservation(scenario, "unit", null, false,
                null, null, null, false, gate, List.of(),
                P3BDecisionQuality.PayloadValidity.NOT_MEASURED,
                false, false, null, false, false, false, false, false, false, 0, 0, failure);
    }

    private static P3BDecisionQuality.Summary summarize(
            P3BDecisionQuality.DecisionObservation... observations) {
        return P3BDecisionQuality.summarize(List.of(observations), ORACLES);
    }
}
