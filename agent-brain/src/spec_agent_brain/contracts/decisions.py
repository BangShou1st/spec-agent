"""文件名:decisions.py

用途:响应信封(Python -> Spring)以及 brain 在单个周期内解析的模型输出
的严格 Pydantic 契约。

响应信封与 Java 侧的 ``AgentV2ResponseEnvelope`` record 一一对应。模型输出
模型是 LLM 本身必须产出的结构,用同等严格度解析,然后填入由 runtime
持有 id 的信封。
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

    @field_validator("known", "unknowns", "conflicts", "risks", mode="before")
    @classmethod
    def _coerce_entries_to_strings(cls, value: Any) -> Any:
        """容忍模型在 reflection 条目上的形态漂移。

        上下文变长后,模型有时会模仿它看到过的 observation 条目,输出对象
        (``{"sourceRef": ..., "excerpt": ...}``)而不是纯字符串。这些条目是
        模型生成的 reflection 文本,不属于信任边界,所以确定性的字符串化
        比让整个 DECISION 周期失败更安全(否则只会表现为一个不可诊断的
        brain_unavailable)。
        """
        if isinstance(value, list):
            return [_coerce_observation_entry(item) for item in value]
        return value


_OBSERVATION_TEXT_KEYS = ("text", "content", "excerpt", "note", "question",
                          "detail", "summary", "value", "observation")


def _coerce_observation_entry(value: Any) -> Any:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        ref = value.get("sourceRef") or value.get("source") or value.get("ref")
        for key in _OBSERVATION_TEXT_KEYS:
            text = value.get(key)
            if isinstance(text, str) and text.strip():
                if isinstance(ref, str) and ref.strip():
                    return f"{ref}: {text.strip()}"
                return text.strip()
        if isinstance(ref, str) and ref.strip():
            return ref.strip()
        parts = [f"{key}: {item}" for key, item in value.items()
                 if isinstance(item, str) and item.strip()]
        if parts:
            return "; ".join(parts)
        raise ValueError("observation entry object carries no text content")
    raise ValueError("observation entry must be a string")


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
    """与 Java 侧的 V3 eligibility 信任边界校验保持一致。"""
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


# --- 模型输出契约(LLM 必须产出的内容,严格解析) -----------------------------


class ModelStateUpdateOutput(StrictModel):
    """STATE_UPDATE 的模型输出:只允许 grounded 的 claims。"""

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
    """DECISION 的模型输出:reflection 与 planning 合并在一个响应里。"""

    observation: ObservationView
    action: ModelDecisionAction
