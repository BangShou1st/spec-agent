import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from spec_agent_brain.contracts.ranking import (
    ActionAssessment,
    RankingPriorityClass,
    RankingScores,
    SemanticRanking,
    SemanticRankingError,
    select_winner,
)

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"


def scores(blocker=0, goal=0, external=0, completion=0, payload=0):
    return RankingScores(
        blocker_closure=blocker,
        goal_progress=goal,
        external_need=external,
        completion_proximity=completion,
        payload_readiness=payload,
    )


def assessment(family, applicable, priority, reasons, refs, score):
    return ActionAssessment(
        family=family,
        applicable=applicable,
        priority_class=priority,
        reason_codes=reasons,
        evidence_refs=refs,
        scores=score,
    )


def ranking(*assessments):
    return SemanticRanking(
        protocol_version="agent-ranking.v1",
        eligibility_version="action-eligibility.v1",
        eligibility_basis_hash="a" * 64,
        input_fingerprint="b" * 64,
        assessments=list(assessments),
        ranking_weights_version="semantic-ranking-weights.v1",
    )


def test_blocking_user_information_beats_direct_completion():
    result = select_winner(
        ["REQUEST_USER_INPUT", "RESPOND_TO_USER"],
        ranking(
            assessment(
                "RESPOND_TO_USER", True, RankingPriorityClass.DIRECT_COMPLETION,
                ["NOT_NEEDED"], ["node:current"], scores(completion=2, payload=1)),
            assessment(
                "REQUEST_USER_INPUT", True, RankingPriorityClass.BLOCKING,
                ["MISSING_USER_INFORMATION"], ["claim:blocker"],
                scores(blocker=2, goal=2, payload=2)),
        ),
    )
    assert result.winner_family == "REQUEST_USER_INPUT"
    assert not result.tie_break_applied


def test_grounded_required_capability_beats_clarification_and_completion():
    result = select_winner(
        ["INVOKE_CAPABILITY", "REQUEST_USER_INPUT", "RESPOND_TO_USER"],
        ranking(
            assessment(
                "REQUEST_USER_INPUT", True, RankingPriorityClass.BLOCKING,
                ["MISSING_USER_INFORMATION"], ["claim:weak-ambiguity"],
                scores(blocker=1, goal=1, payload=1)),
            assessment(
                "RESPOND_TO_USER", True, RankingPriorityClass.DIRECT_COMPLETION,
                ["NOT_NEEDED"], ["node:current"], scores(completion=1, payload=1)),
            assessment(
                "INVOKE_CAPABILITY", True, RankingPriorityClass.REQUIRED_EXTERNAL_STEP,
                ["EXTERNAL_INFORMATION_REQUIRED", "GROUNDED_ARGUMENTS_AVAILABLE"],
                ["node:resource", "claim:goal"],
                scores(goal=2, external=2, completion=1, payload=2)),
        ),
    )
    assert result.winner_family == "INVOKE_CAPABILITY"


def test_direct_completion_wins_without_blocker_or_external_need():
    result = select_winner(
        ["REQUEST_USER_INPUT", "RESPOND_TO_USER", "INVOKE_CAPABILITY"],
        ranking(
            assessment("REQUEST_USER_INPUT", False, RankingPriorityClass.OPTIONAL_PROGRESS,
                       ["NOT_NEEDED"], ["node:current"], scores()),
            assessment("INVOKE_CAPABILITY", False, RankingPriorityClass.OPTIONAL_PROGRESS,
                       ["NOT_NEEDED"], ["node:current"], scores()),
            assessment("RESPOND_TO_USER", True, RankingPriorityClass.DIRECT_COMPLETION,
                       ["GOAL_SATISFIED"], ["claim:goal"],
                       scores(goal=2, completion=2, payload=2)),
        ),
    )
    assert result.winner_family == "RESPOND_TO_USER"


def test_irrelevant_ordering_and_evidence_ordering_do_not_change_winner():
    first = ranking(
        assessment("RESPOND_TO_USER", True, RankingPriorityClass.DIRECT_COMPLETION,
                   ["GOAL_SATISFIED"], ["node:current", "claim:goal"],
                   scores(goal=1, completion=2, payload=2)),
        assessment("REQUEST_USER_INPUT", True, RankingPriorityClass.BLOCKING,
                   ["MISSING_USER_INFORMATION"], ["claim:blocker"],
                   scores(blocker=2, goal=2, payload=2)),
    )
    reordered = ranking(
        assessment("REQUEST_USER_INPUT", True, RankingPriorityClass.BLOCKING,
                   ["MISSING_USER_INFORMATION"], ["claim:blocker"],
                   scores(blocker=2, goal=2, payload=2)),
        assessment("RESPOND_TO_USER", True, RankingPriorityClass.DIRECT_COMPLETION,
                   ["GOAL_SATISFIED"], ["claim:goal", "node:current"],
                   scores(goal=1, completion=2, payload=2)),
    )
    first_result = select_winner(["REQUEST_USER_INPUT", "RESPOND_TO_USER"], first)
    reordered_result = select_winner(["REQUEST_USER_INPUT", "RESPOND_TO_USER"], reordered)
    assert reordered_result.winner_family == first_result.winner_family
    assert reordered_result.winner_score == first_result.winner_score


def test_ineligible_family_is_rejected():
    value = ranking(
        assessment("RESPOND_TO_USER", True, RankingPriorityClass.DIRECT_COMPLETION,
                   ["GOAL_SATISFIED"], ["claim:goal"], scores(goal=1, completion=2)),
        assessment("REQUEST_USER_INPUT", True, RankingPriorityClass.BLOCKING,
                   ["MISSING_USER_INFORMATION"], ["claim:blocker"], scores(blocker=2)),
    )
    with pytest.raises(SemanticRankingError, match="ineligible"):
        select_winner(["RESPOND_TO_USER"], value)


def test_scores_are_bounded_and_contract_is_strict():
    with pytest.raises(ValidationError):
        scores(blocker=3)
    with pytest.raises(ValidationError):
        ActionAssessment(
            family="RESPOND_TO_USER",
            applicable=True,
            priority_class=RankingPriorityClass.DIRECT_COMPLETION,
            reason_codes=["unknown"],
            evidence_refs=["claim:goal"],
            scores=scores(),
        )


def test_cross_language_ranking_fixture_parses_and_selects():
    payload = json.loads(
        (FIXTURES_DIR / "agent-ranking-v1-valid.json").read_text(encoding="utf-8"))
    value = SemanticRanking.model_validate(payload)
    result = select_winner(
        ["REQUEST_USER_INPUT", "RESPOND_TO_USER", "INVOKE_CAPABILITY"], value)
    assert result.winner_family == "REQUEST_USER_INPUT"
