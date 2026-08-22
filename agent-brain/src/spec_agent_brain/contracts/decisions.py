"""Strict Pydantic contracts for the response envelope (Python -> Spring)
and for the model outputs the brain parses inside one cycle.

The response envelope mirrors the Java ``AgentV2ResponseEnvelope`` record.
Model-output models are what the LLM itself must emit; they are parsed with
the same strictness and then stamped into a runtime-owned envelope.
"""

from typing import Any, Dict, List, Literal, Optional
from uuid import UUID

from pydantic import field_validator

from . import protocol
from .inputs import StrictModel


class ProposedClaim(StrictModel):
    kind: str
    text: str
    status: str
    confidence: Optional[float] = None
    source_refs: List[str] = []

    @field_validator("kind")
    @classmethod
    def _known_kind(cls, value: str) -> str:
        if value not in protocol.CLAIM_KINDS:
            raise ValueError(f"unknown claim kind: {value}")
        return value

    @field_validator("status")
    @classmethod
    def _known_status(cls, value: str) -> str:
        if value not in protocol.CLAIM_STATUSES:
            raise ValueError(f"unknown claim status: {value}")
        return value


class StateUpdateResult(StrictModel):
    claims: List[ProposedClaim] = []


class ObservationView(StrictModel):
    known: List[str] = []
    unknowns: List[str] = []
    conflicts: List[str] = []
    risks: List[str] = []


class ActionProposal(StrictModel):
    action_family: str
    payload: Dict[str, Any]
    base_context_snapshot_id: UUID
    base_context_hash: str
    source_refs: List[str] = []

    @field_validator("action_family")
    @classmethod
    def _known_family(cls, value: str) -> str:
        if value not in protocol.ACTION_FAMILIES:
            raise ValueError(f"unknown action family: {value}")
        return value


class UsageView(StrictModel):
    model_calls: int
    prompt_hashes: List[str] = []


class AgentV2ResponseEnvelope(StrictModel):
    protocol_version: Literal[protocol.DECISION_PROTOCOL_VERSION]
    run_id: UUID
    state_update: Optional[StateUpdateResult] = None
    observation: Optional[ObservationView] = None
    action_proposal: Optional[ActionProposal] = None
    usage: Optional[UsageView] = None
    diagnostics: Dict[str, Any] = {}


# --- Model output contracts (what the LLM must emit, strictly parsed) -------


class ModelStateUpdateOutput(StrictModel):
    """STATE_UPDATE model output: grounded claims only."""

    claims: List[ProposedClaim]


class ModelDecisionAction(StrictModel):
    action_family: str
    payload: Dict[str, Any]
    source_refs: List[str] = []

    @field_validator("action_family")
    @classmethod
    def _known_family(cls, value: str) -> str:
        if value not in protocol.ACTION_FAMILIES:
            raise ValueError(f"unknown action family: {value}")
        return value


class ModelDecisionOutput(StrictModel):
    """DECISION model output: reflection + planning in one response."""

    observation: ObservationView
    action: ModelDecisionAction
