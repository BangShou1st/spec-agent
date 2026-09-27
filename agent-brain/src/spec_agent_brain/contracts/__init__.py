"""文件名:__init__.py

用途:contracts 子包的对外出口,集中导出 agent-brain 服务的跨语言契约
(Python 与 Spring/Java 双方共同遵守的数据结构)。
"""

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
