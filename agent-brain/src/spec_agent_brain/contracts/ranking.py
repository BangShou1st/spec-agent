"""Strict, Runtime-auditable semantic ranking contract.

The model supplies bounded assessments; the Runtime computes the winner from
the request's eligibility mask and a versioned generic scorecard.  This module
is intentionally not wired into the production Decision engine yet.
"""

from typing import Dict, List, Literal, Sequence

from pydantic import Field, field_validator, model_validator

from . import protocol
from .inputs import StrictModel


class RankingPriorityClass(str):
    BLOCKING = "BLOCKING"
    REQUIRED_EXTERNAL_STEP = "REQUIRED_EXTERNAL_STEP"
    DIRECT_COMPLETION = "DIRECT_COMPLETION"
    OPTIONAL_PROGRESS = "OPTIONAL_PROGRESS"
    DURABLE_MATERIALIZATION = "DURABLE_MATERIALIZATION"


class RankingReasonCode(str):
    MISSING_USER_INFORMATION = "MISSING_USER_INFORMATION"
    UNRESOLVED_USER_CHOICE = "UNRESOLVED_USER_CHOICE"
    EXTERNAL_INFORMATION_REQUIRED = "EXTERNAL_INFORMATION_REQUIRED"
    GROUNDED_ARGUMENTS_AVAILABLE = "GROUNDED_ARGUMENTS_AVAILABLE"
    GOAL_SATISFIED = "GOAL_SATISFIED"
    MATERIAL_NOVELTY = "MATERIAL_NOVELTY"
    NOT_NEEDED = "NOT_NEEDED"


class RankingScores(StrictModel):
    blocker_closure: int = Field(ge=0, le=2)
    goal_progress: int = Field(ge=0, le=2)
    external_need: int = Field(ge=0, le=2)
    completion_proximity: int = Field(ge=0, le=2)
    payload_readiness: int = Field(ge=0, le=2)


class ActionAssessment(StrictModel):
    family: str
    applicable: bool
    priority_class: Literal[
        "BLOCKING",
        "REQUIRED_EXTERNAL_STEP",
        "DIRECT_COMPLETION",
        "OPTIONAL_PROGRESS",
        "DURABLE_MATERIALIZATION",
    ]
    reason_codes: List[Literal[
        "MISSING_USER_INFORMATION",
        "UNRESOLVED_USER_CHOICE",
        "EXTERNAL_INFORMATION_REQUIRED",
        "GROUNDED_ARGUMENTS_AVAILABLE",
        "GOAL_SATISFIED",
        "MATERIAL_NOVELTY",
        "NOT_NEEDED",
    ]] = Field(default_factory=list)
    evidence_refs: List[str] = Field(default_factory=list)
    scores: RankingScores

    @field_validator("family")
    @classmethod
    def _known_family(cls, value: str) -> str:
        if value not in protocol.ACTION_FAMILIES:
            raise ValueError(f"unknown action family: {value}")
        return value

    @field_validator("reason_codes")
    @classmethod
    def _unique_reason_codes(cls, value: List[str]) -> List[str]:
        if len(value) != len(set(value)):
            raise ValueError("ranking reason codes must be unique")
        return value

    @field_validator("evidence_refs")
    @classmethod
    def _unique_evidence_refs(cls, value: List[str]) -> List[str]:
        if any(not ref.strip() for ref in value):
            raise ValueError("ranking evidence refs must be non-blank")
        if len(value) != len(set(value)):
            raise ValueError("ranking evidence refs must be unique")
        return value

    @model_validator(mode="after")
    def _applicable_requires_evidence(self) -> "ActionAssessment":
        if self.applicable and not self.evidence_refs:
            raise ValueError("applicable ranking assessment requires evidence refs")
        return self


class SemanticRanking(StrictModel):
    protocol_version: Literal[protocol.RANKING_PROTOCOL_VERSION]
    eligibility_version: Literal[protocol.ACTION_ELIGIBILITY_VERSION]
    eligibility_basis_hash: str
    input_fingerprint: str
    assessments: List[ActionAssessment]
    ranking_weights_version: str

    @field_validator("eligibility_basis_hash", "input_fingerprint")
    @classmethod
    def _sha256(cls, value: str) -> str:
        if len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
            raise ValueError("ranking identity must be SHA-256 hex")
        return value

    @field_validator("ranking_weights_version")
    @classmethod
    def _weights_version(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("ranking weights version is required")
        return value

    @model_validator(mode="after")
    def _unique_assessment_families(self) -> "SemanticRanking":
        if not self.assessments:
            raise ValueError("semantic ranking assessments are required")
        families = [assessment.family for assessment in self.assessments]
        if len(families) != len(set(families)):
            raise ValueError("duplicate semantic ranking family")
        return self


class SemanticRankingSelection(StrictModel):
    winner_family: str
    winner_score: int
    tie_break_applied: bool
    winner_evidence_refs: List[str] = Field(default_factory=list)
    score_breakdown: Dict[str, Dict[str, int]] = Field(default_factory=dict)


class SemanticRankingError(ValueError):
    """Fail-closed ranking contract or winner-selection error."""


_PRIORITY_LEVEL = {
    "BLOCKING": 2,
    "REQUIRED_EXTERNAL_STEP": 2,
    "DIRECT_COMPLETION": 1,
    "OPTIONAL_PROGRESS": 0,
    "DURABLE_MATERIALIZATION": 0,
}

_WEIGHTS = {
    "priorityClassWeight": 1,
    "blockerClosureWeight": 3,
    "goalProgressWeight": 3,
    "externalNeedWeight": 4,
    "completionProximityWeight": 2,
    "payloadReadinessWeight": 1,
    "evidenceWeight": 1,
}


def _score(assessment: ActionAssessment) -> Dict[str, int]:
    values = {
        "priorityClass": _PRIORITY_LEVEL[assessment.priority_class],
        "blockerClosure": assessment.scores.blocker_closure,
        "goalProgress": assessment.scores.goal_progress,
        "externalNeed": assessment.scores.external_need,
        "completionProximity": assessment.scores.completion_proximity,
        "payloadReadiness": assessment.scores.payload_readiness,
        "evidence": 1 if assessment.evidence_refs else 0,
    }
    values["total"] = (
        values["priorityClass"] * _WEIGHTS["priorityClassWeight"]
        + values["blockerClosure"] * _WEIGHTS["blockerClosureWeight"]
        + values["goalProgress"] * _WEIGHTS["goalProgressWeight"]
        + values["externalNeed"] * _WEIGHTS["externalNeedWeight"]
        + values["completionProximity"] * _WEIGHTS["completionProximityWeight"]
        + values["payloadReadiness"] * _WEIGHTS["payloadReadinessWeight"]
        + values["evidence"] * _WEIGHTS["evidenceWeight"]
    )
    return values


def select_winner(
        eligible_families: Sequence[str],
        ranking: SemanticRanking,
) -> SemanticRankingSelection:
    """Select a winner without family-specific precedence or fallback."""
    eligible = list(eligible_families)
    if len(eligible) != len(set(eligible)):
        raise SemanticRankingError("duplicate eligible action family")
    unknown = set(eligible) - set(protocol.ACTION_FAMILIES)
    if unknown:
        raise SemanticRankingError(f"unknown eligible action family: {sorted(unknown)}")
    if ranking.ranking_weights_version != protocol.RANKING_WEIGHTS_VERSION:
        raise SemanticRankingError("ranking weights version does not match")

    assessments = {assessment.family: assessment for assessment in ranking.assessments}
    ineligible = set(assessments) - set(eligible)
    if ineligible:
        raise SemanticRankingError(
            f"ranking assessment contains ineligible family: {sorted(ineligible)}")
    missing = set(eligible) - set(assessments)
    if missing:
        raise SemanticRankingError(
            f"ranking assessment is incomplete; missing: {sorted(missing)}")

    scored = {
        family: _score(assessment)
        for family, assessment in assessments.items()
        if assessment.applicable
    }
    if not scored:
        raise SemanticRankingError("ranking has no applicable eligible family")

    ordered = sorted(
        scored,
        key=lambda family: (-scored[family]["total"], protocol.ACTION_FAMILIES.index(family)),
    )
    winner = ordered[0]
    tie_break = len(ordered) > 1 and scored[winner]["total"] == scored[ordered[1]]["total"]
    breakdown = {family: scored[family] for family in ordered}
    return SemanticRankingSelection(
        winner_family=winner,
        winner_score=scored[winner]["total"],
        tie_break_applied=tie_break,
        winner_evidence_refs=assessments[winner].evidence_refs,
        score_breakdown=breakdown,
    )
