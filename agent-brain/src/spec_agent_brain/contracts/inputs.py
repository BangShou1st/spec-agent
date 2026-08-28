"""Strict Pydantic contracts for the request envelope (Spring -> Python).

Every model uses ``extra="forbid"`` so unknown fields fail closed, and the
envelope protocol version is a ``Literal`` so unknown versions are rejected.
Wire field names are camelCase via alias generation; Python code uses
snake_case.
"""

from typing import Any, Dict, List, Literal, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

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
    kind: str = "INTERACTION"

    @field_validator("kind")
    @classmethod
    def _known_kind(cls, value: str) -> str:
        if value not in protocol.NODE_KINDS:
            raise ValueError(f"unknown node kind: {value}")
        return value


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
    # ``route_id`` is null only for a routeless Floating Node NODE_QUERY
    # (Stage C). Route-bound flows (STATE_UPDATE, normal DECISION, ANSWER,
    # SPEC, REGENERATE) continue to carry a UUID here.
    route_id: Optional[UUID] = None
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
    description: str = ""
    side_effect_class: str = "NONE"


class CapabilityResultView(StrictModel):
    """A completed capability invocation exposed as a bounded observation.

    Capability results are external evidence or generated summaries for
    later cycles — never auto-confirmed graph truth.
    """

    invocation_id: str
    capability_id: str
    status: str
    content: Dict[str, Any] = Field(default_factory=dict)
    source_refs: List[str] = Field(default_factory=list)
    provenance: Dict[str, Any] = Field(default_factory=dict)


class AgentInputSnapshot(StrictModel):
    snapshot_id: UUID
    context_hash: str
    project_id: UUID
    # ``route_id`` is null only for a routeless Floating Node NODE_QUERY
    # (Stage C). Required (UUID) for every other flow. The mirror field on
    # ``RouteContextView.route_id`` must agree.
    route_id: Optional[UUID] = None
    anchor_node_id: Optional[UUID] = None
    route_context: RouteContextView
    lineage: List[LineageEntry] = Field(default_factory=list)
    effective_claims: List[ClaimView] = Field(default_factory=list)
    metadata: SnapshotMetadata
    allowed_source_refs: List[str] = Field(default_factory=list)
    available_capabilities: List[CapabilityDescriptor] = Field(default_factory=list)
    capability_results: List[CapabilityResultView] = Field(default_factory=list)
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

    @model_validator(mode="after")
    def _route_id_contract(self) -> "AgentV2RequestEnvelope":
        """Authoritative cross-field rule for routeless NODE_QUERY.

        The ``route_id`` field is intentionally Optional on the model layer so
        the strict wire shape can carry a Floating-node NODE_QUERY. This
        validator is the only place where that nullability is admitted, and
        it is deliberately narrow:

        - A non-``NODE_QUERY`` event MUST carry a route UUID in BOTH
          ``snapshot.routeId`` and ``snapshot.routeContext.routeId``.
        - A ``NODE_QUERY`` event admits exactly two valid modes:
            (a) route-bound: both fields are non-null UUIDs and equal;
            (b) routeless:   both fields are null.
        - Mixed state (one null, one UUID) or unequal UUIDs is rejected.
        """
        snapshot = self.snapshot
        snapshot_route = snapshot.route_id
        context_route = snapshot.route_context.route_id
        event_kind = self.event.kind

        if event_kind != "NODE_QUERY":
            if snapshot_route is None:
                raise ValueError(
                    f"{event_kind} requires snapshot.routeId; only NODE_QUERY "
                    "may carry a routeless (null) route id")
            if context_route is None:
                raise ValueError(
                    f"{event_kind} requires snapshot.routeContext.routeId; "
                    "only NODE_QUERY may carry a routeless (null) route id")
            if snapshot_route != context_route:
                raise ValueError(
                    f"{event_kind} requires snapshot.routeId and "
                    "snapshot.routeContext.routeId to be equal")
            return self

        # NODE_QUERY: null/null or UUID/UUID-and-equal.
        if (snapshot_route is None) != (context_route is None):
            raise ValueError(
                "NODE_QUERY must be either fully route-bound "
                "(both route ids are equal UUIDs) or fully routeless "
                "(both route ids are null); mixed state is rejected")
        if snapshot_route is not None and snapshot_route != context_route:
            raise ValueError(
                "NODE_QUERY route-bound mode requires snapshot.routeId and "
                "snapshot.routeContext.routeId to be equal")
        return self


def parse_request_envelope(payload: Dict[str, Any]) -> AgentV2RequestEnvelope:
    """Parses a raw JSON object into the request envelope, fail-closed."""
    return AgentV2RequestEnvelope.model_validate(payload)
