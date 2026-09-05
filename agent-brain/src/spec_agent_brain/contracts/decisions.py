"""Strict Pydantic contracts for the response envelope (Python -> Spring)
and for the model outputs the brain parses inside one cycle.

The response envelope mirrors the Java ``AgentV2ResponseEnvelope`` record.
Model-output models are what the LLM itself must emit; they are parsed with
the same strictness and then stamped into a runtime-owned envelope.
"""

from typing import Any, Dict, List, Literal, Optional, TYPE_CHECKING
from uuid import UUID

from pydantic import field_validator, model_validator

from . import protocol
from .inputs import StrictModel

if TYPE_CHECKING:
    from .inputs import AgentV3RequestEnvelope


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
    proposal_id: UUID = None
    idempotency_key: str = None
    anchor_refs: List[str] = []

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


class AgentV3ResponseEnvelope(AgentV2ResponseEnvelope):
    protocol_version: Literal[protocol.DECISION_PROTOCOL_VERSION_V3]
    selected_eligibility_version: Literal[protocol.ACTION_ELIGIBILITY_VERSION]
    selected_eligibility_basis_hash: str
    eligibility_evidence_refs: List[str] = []

    @model_validator(mode="after")
    def _eligibility_digest_shape(self) -> "AgentV3ResponseEnvelope":
        value = self.selected_eligibility_basis_hash
        if len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
            raise ValueError("selected eligibility basis hash must be SHA-256 hex")
        return self


def validate_v3_response_for_request(
        request: "AgentV3RequestEnvelope",
        response: AgentV3ResponseEnvelope) -> None:
    """Mirrors Java's V3 eligibility trust-boundary checks."""
    eligibility = request.action_eligibility
    if response.selected_eligibility_version != eligibility.version:
        raise ValueError("selected eligibility version does not match request")
    if response.selected_eligibility_basis_hash != eligibility.basis_hash:
        raise ValueError("selected eligibility basis hash does not match request")
    if response.action_proposal is None:
        raise ValueError("V3 Decision requires an action proposal")
    if response.action_proposal.action_family not in eligibility.eligible_families:
        raise ValueError("selected action family is not eligible")
    allowed = set(request.snapshot.allowed_source_refs)
    if any(ref not in allowed for ref in response.eligibility_evidence_refs):
        raise ValueError("eligibility evidence ref is outside allowed source refs")


# --- Model output contracts (what the LLM must emit, strictly parsed) -------


class ModelStateUpdateOutput(StrictModel):
    """STATE_UPDATE model output: grounded claims only."""

    claims: List[ProposedClaim]


class ModelDecisionAction(StrictModel):
    action_family: str
    payload: Dict[str, Any]
    source_refs: List[str] = []
    anchor_refs: List[str] = []

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
