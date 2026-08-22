"""Strict Pydantic contracts for the request envelope (Spring -> Python).

Every model uses ``extra="forbid"`` so unknown fields fail closed, and the
envelope protocol version is a ``Literal`` so unknown versions are rejected.
Wire field names are camelCase via alias generation; Python code uses
snake_case.
"""

from typing import Any, Dict, List, Literal, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

from . import protocol


def to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.title() for part in rest)


class StrictModel(BaseModel):
    """Base for all wire models: unknown fields rejected, camelCase aliases.

    ``populate_by_name`` lets Python code construct models with snake_case
    names; the wire format stays camelCase because serialization always uses
    ``by_alias=True``.
    """

    model_config = ConfigDict(
        extra="forbid", alias_generator=to_camel, populate_by_name=True)


class OptionView(StrictModel):
    id: UUID
    label: str


class NodeBodyView(StrictModel):
    text: str
    options: List[OptionView] = Field(default_factory=list)
    accepts_free_text: bool


class NodeView(StrictModel):
    id: UUID
    body: NodeBodyView


class AnswerView(StrictModel):
    id: UUID
    node_id: UUID
    selected_option_id: Optional[UUID] = None
    free_text: Optional[str] = None


class ClaimView(StrictModel):
    kind: str
    text: str
    status: str
    confidence: Optional[float] = None
    source_node_id: Optional[UUID] = None
    source_answer_id: Optional[UUID] = None

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


class PatchView(StrictModel):
    id: UUID
    claims: List[ClaimView] = Field(default_factory=list)


class LineageEntry(StrictModel):
    node: NodeView
    answer: Optional[AnswerView] = None
    patches: List[PatchView] = Field(default_factory=list)


class RouteContextView(StrictModel):
    route_id: UUID
    tip_node_id: Optional[UUID] = None
    label: Optional[str] = None


class SnapshotMetadata(StrictModel):
    project_title: Optional[str] = None


class AutonomyInputs(StrictModel):
    mode: str


class CapabilityDescriptor(StrictModel):
    id: str
    version: str
    read_only: bool


class AgentInputSnapshot(StrictModel):
    snapshot_id: UUID
    context_hash: str
    project_id: UUID
    route_id: UUID
    anchor_node_id: Optional[UUID] = None
    route_context: RouteContextView
    lineage: List[LineageEntry] = Field(default_factory=list)
    effective_claims: List[ClaimView] = Field(default_factory=list)
    metadata: SnapshotMetadata
    allowed_source_refs: List[str] = Field(default_factory=list)
    available_capabilities: List[CapabilityDescriptor] = Field(default_factory=list)
    autonomy: AutonomyInputs


class AgentV2Event(StrictModel):
    kind: str
    anchor_node_id: Optional[UUID] = None
    selected_option_id: Optional[UUID] = None
    free_text: Optional[str] = None

    @field_validator("kind")
    @classmethod
    def _known_kind(cls, value: str) -> str:
        if value not in protocol.EVENT_KINDS:
            raise ValueError(f"unknown event kind: {value}")
        return value


class DecisionBudget(StrictModel):
    max_model_calls: int


class AgentV2RequestEnvelope(StrictModel):
    protocol_version: Literal[protocol.INPUT_PROTOCOL_VERSION]
    run_id: UUID
    event: AgentV2Event
    snapshot: AgentInputSnapshot
    capabilities: List[CapabilityDescriptor] = Field(default_factory=list)
    decision_budget: DecisionBudget


def parse_request_envelope(payload: Dict[str, Any]) -> AgentV2RequestEnvelope:
    """Parses a raw JSON object into the request envelope, fail-closed."""
    return AgentV2RequestEnvelope.model_validate(payload)
