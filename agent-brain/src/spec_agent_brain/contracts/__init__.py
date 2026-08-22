"""Cross-language contracts of the agent-brain service."""

from .inputs import AgentInputSnapshot, AgentV2Event, AgentV2RequestEnvelope
from .decisions import (
    ActionProposal,
    AgentV2ResponseEnvelope,
    ModelDecisionAction,
    ModelDecisionOutput,
    ModelStateUpdateOutput,
    ObservationView,
    ProposedClaim,
    StateUpdateResult,
    UsageView,
)
from . import protocol

__all__ = [
    "AgentInputSnapshot",
    "AgentV2Event",
    "AgentV2RequestEnvelope",
    "ActionProposal",
    "AgentV2ResponseEnvelope",
    "ModelDecisionAction",
    "ModelDecisionOutput",
    "ModelStateUpdateOutput",
    "ObservationView",
    "ProposedClaim",
    "StateUpdateResult",
    "UsageView",
    "protocol",
]
