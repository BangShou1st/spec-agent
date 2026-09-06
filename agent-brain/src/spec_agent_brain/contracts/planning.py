"""Strict diagnostic contract for structured semantic planning state.

The model classifies what the current task still needs before any action
is selected. Every flag carries bounded reason codes and evidence refs;
free-form reasoning is forbidden. This module is diagnostic-only and is
not wired into the production Decision engine.
"""

from typing import Dict, List, Literal

from pydantic import Field, field_validator

from .inputs import StrictModel

PLANNING_STATE_VERSION = "planning-state.v1"

USER_INPUT_REASON_CODES = (
    "NEED_MORE_INFO",
    "BLOCKER_OPEN",
    "CHOICE_UNRESOLVED",
    "USER_INPUT_SUFFICIENT",
)

EXTERNAL_STEP_REASON_CODES = (
    "EXTERNAL_EVIDENCE_REQUIRED",
    "EXTERNAL_ACTION_REQUIRED",
    "ARGUMENTS_GROUNDED",
    "CAPABILITY_RELEVANT_NOT_REQUIRED",
    "NO_EXTERNAL_NEED",
)

DIRECT_RESPONSE_REASON_CODES = (
    "GOAL_SATISFIED",
    "NOTHING_NEW_TO_ASK",
    "DIRECT_RESPONSE_NOT_SUFFICIENT",
)

NEW_KNOWLEDGE_REASON_CODES = (
    "DURABLE_FACT_WORTH_KEEPING",
    "NOTHING_DURABLE",
)

REASON_CODES_BY_FLAG: Dict[str, tuple] = {
    "user_input_required": USER_INPUT_REASON_CODES,
    "external_step_required": EXTERNAL_STEP_REASON_CODES,
    "direct_response_sufficient": DIRECT_RESPONSE_REASON_CODES,
    "new_durable_knowledge_present": NEW_KNOWLEDGE_REASON_CODES,
}

EVIDENCE_REF_PREFIXES = (
    "node:",
    "answer:",
    "patch:",
    "context:",
    "route:",
    "claim:",
    "capability:",
)


class PlanningFlag(StrictModel):
    value: bool
    reason_codes: List[str] = Field(default_factory=list)
    evidence_refs: List[str] = Field(default_factory=list)

    @field_validator("reason_codes")
    @classmethod
    def _unique_reason_codes(cls, value: List[str]) -> List[str]:
        if len(value) != len(set(value)):
            raise ValueError("planning reason codes must be unique")
        return value

    @field_validator("evidence_refs")
    @classmethod
    def _unique_evidence_refs(cls, value: List[str]) -> List[str]:
        if any(not ref.strip() for ref in value):
            raise ValueError("planning evidence refs must be non-blank")
        if len(value) != len(set(value)):
            raise ValueError("planning evidence refs must be unique")
        return value


class PlanningState(StrictModel):
    version: Literal[PLANNING_STATE_VERSION]
    user_input_required: PlanningFlag
    external_step_required: PlanningFlag
    direct_response_sufficient: PlanningFlag
    new_durable_knowledge_present: PlanningFlag


class PlanningStateError(ValueError):
    """Fail-closed planning-state contract error."""


def _check_flag(name: str, flag: PlanningFlag) -> None:
    allowed = REASON_CODES_BY_FLAG[name]
    if not flag.reason_codes:
        raise PlanningStateError(f"planning flag {name} requires reason codes")
    unknown = [code for code in flag.reason_codes if code not in allowed]
    if unknown:
        raise PlanningStateError(
            f"planning flag {name} has unknown reason codes: {unknown}")
    if not flag.evidence_refs:
        raise PlanningStateError(f"planning flag {name} requires evidence refs")
    bad = [ref for ref in flag.evidence_refs
             if not ref.startswith(EVIDENCE_REF_PREFIXES)]
    if bad:
        raise PlanningStateError(
            f"planning flag {name} has bad evidence refs: {bad}")


def validate_planning_state(payload: object) -> PlanningState:
    """Parse and fully validate one model-produced planning state."""
    try:
        state = PlanningState.model_validate(payload)
    except Exception as exc:
        raise PlanningStateError(f"planning schema failure: {exc}") from exc
    for name in REASON_CODES_BY_FLAG:
        _check_flag(name, getattr(state, name))
    return state
