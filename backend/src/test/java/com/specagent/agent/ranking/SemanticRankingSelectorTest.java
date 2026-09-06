package com.specagent.agent.ranking;

import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.eligibility.ActionEligibility;
import com.specagent.agent.eligibility.ActionEligibilityConstraint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticRankingSelectorTest {

    @Test
    void crossLanguageGoldenFixtureParsesStrictly() throws Exception {
        String json = Files.readString(
                Path.of("..", "contracts", "fixtures", "agent-ranking-v1-valid.json"),
                StandardCharsets.UTF_8);

        SemanticRanking ranking = AgentContracts.read(json, SemanticRanking.class);

        assertThat(ranking.protocolVersion()).isEqualTo(SemanticRanking.VERSION);
        assertThat(ranking.assessments()).hasSize(3);
    }

    @Test
    void blockingUserInformationBeatsDirectCompletionWithoutFamilyPrecedence() {
        ActionEligibility eligibility = eligibility(ActionFamily.REQUEST_USER_INPUT,
                ActionFamily.RESPOND_TO_USER);
        SemanticRanking ranking = ranking(
                assessment(ActionFamily.RESPOND_TO_USER, true,
                        RankingPriorityClass.DIRECT_COMPLETION,
                        List.of(RankingReasonCode.NOT_NEEDED), List.of("node:current"),
                        scores(0, 0, 0, 2, 1)),
                assessment(ActionFamily.REQUEST_USER_INPUT, true,
                        RankingPriorityClass.BLOCKING,
                        List.of(RankingReasonCode.MISSING_USER_INFORMATION),
                        List.of("claim:blocker"), scores(2, 2, 0, 0, 2)));

        SemanticRankingSelection selection = SemanticRankingSelector.select(
                eligibility, ranking);

        assertThat(selection.winnerFamily()).isEqualTo(ActionFamily.REQUEST_USER_INPUT);
        assertThat(selection.tieBreakApplied()).isFalse();
    }

    @Test
    void groundedRequiredCapabilityBeatsClarificationAndCompletion() {
        ActionEligibility eligibility = eligibility(ActionFamily.INVOKE_CAPABILITY,
                ActionFamily.REQUEST_USER_INPUT, ActionFamily.RESPOND_TO_USER);
        SemanticRanking ranking = ranking(
                assessment(ActionFamily.REQUEST_USER_INPUT, true,
                        RankingPriorityClass.BLOCKING,
                        List.of(RankingReasonCode.MISSING_USER_INFORMATION),
                        List.of("claim:weak-ambiguity"), scores(1, 1, 0, 0, 1)),
                assessment(ActionFamily.RESPOND_TO_USER, true,
                        RankingPriorityClass.DIRECT_COMPLETION,
                        List.of(RankingReasonCode.NOT_NEEDED), List.of("node:current"),
                        scores(0, 0, 0, 1, 1)),
                assessment(ActionFamily.INVOKE_CAPABILITY, true,
                        RankingPriorityClass.REQUIRED_EXTERNAL_STEP,
                        List.of(RankingReasonCode.EXTERNAL_INFORMATION_REQUIRED,
                                RankingReasonCode.GROUNDED_ARGUMENTS_AVAILABLE),
                        List.of("node:resource", "claim:goal"), scores(0, 2, 2, 1, 2)));

        SemanticRankingSelection selection = SemanticRankingSelector.select(
                eligibility, ranking);

        assertThat(selection.winnerFamily()).isEqualTo(ActionFamily.INVOKE_CAPABILITY);
    }

    @Test
    void directCompletionWinsWhenNoBlockingOrExternalNeedExists() {
        ActionEligibility eligibility = eligibility(ActionFamily.REQUEST_USER_INPUT,
                ActionFamily.RESPOND_TO_USER, ActionFamily.INVOKE_CAPABILITY);
        SemanticRanking ranking = ranking(
                assessment(ActionFamily.REQUEST_USER_INPUT, false,
                        RankingPriorityClass.OPTIONAL_PROGRESS,
                        List.of(RankingReasonCode.NOT_NEEDED), List.of("node:current"),
                        scores(0, 0, 0, 0, 0)),
                assessment(ActionFamily.INVOKE_CAPABILITY, false,
                        RankingPriorityClass.OPTIONAL_PROGRESS,
                        List.of(RankingReasonCode.NOT_NEEDED), List.of("node:current"),
                        scores(0, 0, 0, 0, 0)),
                assessment(ActionFamily.RESPOND_TO_USER, true,
                        RankingPriorityClass.DIRECT_COMPLETION,
                        List.of(RankingReasonCode.GOAL_SATISFIED), List.of("claim:goal"),
                        scores(0, 2, 0, 2, 2)));

        assertThat(SemanticRankingSelector.select(eligibility, ranking).winnerFamily())
                .isEqualTo(ActionFamily.RESPOND_TO_USER);
    }

    @Test
    void irrelevantOrderingAndEvidenceOrderingDoNotChangeWinner() {
        ActionEligibility eligibility = eligibility(ActionFamily.REQUEST_USER_INPUT,
                ActionFamily.RESPOND_TO_USER);
        SemanticRanking first = ranking(
                assessment(ActionFamily.RESPOND_TO_USER, true,
                        RankingPriorityClass.DIRECT_COMPLETION,
                        List.of(RankingReasonCode.GOAL_SATISFIED),
                        List.of("node:current", "claim:goal"), scores(0, 1, 0, 2, 2)),
                assessment(ActionFamily.REQUEST_USER_INPUT, true,
                        RankingPriorityClass.BLOCKING,
                        List.of(RankingReasonCode.MISSING_USER_INFORMATION),
                        List.of("claim:blocker"), scores(2, 2, 0, 0, 2)));
        SemanticRanking reordered = ranking(
                assessment(ActionFamily.REQUEST_USER_INPUT, true,
                        RankingPriorityClass.BLOCKING,
                        List.of(RankingReasonCode.MISSING_USER_INFORMATION),
                        List.of("claim:blocker"), scores(2, 2, 0, 0, 2)),
                assessment(ActionFamily.RESPOND_TO_USER, true,
                        RankingPriorityClass.DIRECT_COMPLETION,
                        List.of(RankingReasonCode.GOAL_SATISFIED),
                        List.of("claim:goal", "node:current"), scores(0, 1, 0, 2, 2)));

        SemanticRankingSelection firstSelection = SemanticRankingSelector.select(
                eligibility, first);
        SemanticRankingSelection reorderedSelection = SemanticRankingSelector.select(
                eligibility, reordered);

        assertThat(reorderedSelection.winnerFamily()).isEqualTo(firstSelection.winnerFamily());
        assertThat(reorderedSelection.winnerScore()).isEqualTo(firstSelection.winnerScore());
    }

    @Test
    void ineligibleFamilyCannotAppearInRanking() {
        ActionEligibility eligibility = eligibility(ActionFamily.RESPOND_TO_USER);
        SemanticRanking ranking = ranking(
                assessment(ActionFamily.RESPOND_TO_USER, true,
                        RankingPriorityClass.DIRECT_COMPLETION,
                        List.of(RankingReasonCode.GOAL_SATISFIED), List.of("claim:goal"),
                        scores(0, 1, 0, 2, 2)),
                assessment(ActionFamily.REQUEST_USER_INPUT, true,
                        RankingPriorityClass.BLOCKING,
                        List.of(RankingReasonCode.MISSING_USER_INFORMATION),
                        List.of("claim:blocker"), scores(2, 2, 0, 0, 2)));

        assertThatThrownBy(() -> SemanticRankingSelector.select(eligibility, ranking))
                .isInstanceOf(SemanticRankingException.class)
                .hasMessageContaining("ineligible");
    }

    private SemanticRanking ranking(ActionAssessment... assessments) {
        return new SemanticRanking(
                SemanticRanking.VERSION,
                ActionEligibility.VERSION,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of(assessments), "semantic-ranking-weights.v1");
    }

    private ActionAssessment assessment(ActionFamily family, boolean applicable,
                                       RankingPriorityClass priorityClass,
                                       List<RankingReasonCode> reasonCodes,
                                       List<String> evidenceRefs,
                                       RankingScores scores) {
        return new ActionAssessment(family, applicable, priorityClass, reasonCodes,
                evidenceRefs, scores);
    }

    private RankingScores scores(int blockerClosure, int goalProgress, int externalNeed,
                                 int completionProximity, int payloadReadiness) {
        return new RankingScores(blockerClosure, goalProgress, externalNeed,
                completionProximity, payloadReadiness);
    }

    private ActionEligibility eligibility(ActionFamily... eligibleFamilies) {
        EnumMap<ActionFamily, ActionEligibilityConstraint> constraints =
                new EnumMap<>(ActionFamily.class);
        List<String> eligible = new ArrayList<>();
        for (ActionFamily family : eligibleFamilies) {
            eligible.add(family.code());
        }
        for (ActionFamily family : ActionFamily.values()) {
            constraints.put(family, eligible.contains(family.code())
                    ? ActionEligibilityConstraint.allow()
                    : ActionEligibilityConstraint.deny());
        }
        return new ActionEligibility(ActionEligibility.VERSION, eligible,
                constraints.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().code(), Map.Entry::getValue)),
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
    }
}
