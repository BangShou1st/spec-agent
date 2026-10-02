"""文件名:inputs.py

用途:请求信封(Spring -> Python)的严格 Pydantic 契约。

所有模型都使用 ``extra="forbid"``,未知字段一律 fail-closed;信封的协议
版本是 ``Literal`` 类型,未知版本直接拒绝。线上字段名通过 alias 生成器
使用 camelCase,Python 代码内部使用 snake_case。
"""

from typing import Any, Dict, List, Literal, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from . import protocol


def to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.title() for part in rest)


class StrictModel(BaseModel):
    """所有线上模型类的基类:拒绝未知字段,camelCase 别名。

    ``populate_by_name`` 允许 Python 代码用 snake_case 字段名构造模型;
    线上格式仍是 camelCase,因为序列化始终使用 ``by_alias=True``。
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
    # 仅当是无路由的 Floating Node NODE_QUERY(Stage C)时 ``route_id`` 才
    # 为 null。绑定路由的流程(STATE_UPDATE、普通 DECISION、ANSWER、SPEC、
    # REGENERATE)仍会在这里携带 UUID。
    route_id: Optional[UUID] = None
    tip_node_id: Optional[UUID] = None
    label: Optional[str] = None


class RetrievalState(StrictModel):
    retrieval_engine_version: Literal["python-rag.v1"]
    profile_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    index_generation: Optional[UUID] = None
    vector_unavailable: Optional[bool] = None
    supplemental_retrieval_unavailable: bool


class SnapshotMetadata(StrictModel):
    project_title: Optional[str] = None
    retrieval: Optional[RetrievalState] = None


class AutonomyInputs(StrictModel):
    mode: str


class CapabilityDescriptor(StrictModel):
    id: str
    version: str
    read_only: bool
    description: str = ""
    side_effect_class: str = "NONE"
    # 有界的 JSON-Schema 风格的参数形状(可选字段,以兼容旧冻结 payload
    # 与旧版本 brain 的线上/回放格式)。
    input_schema: Dict[str, Any] = Field(default_factory=dict)
    # 驱动可见性判断的结构化相关性事实("KIND" 或 "KIND:SUBTYPE");
    # 镜像 runtime 的投影,绝不是模型生成的内容。
    supports: List[str] = Field(default_factory=list)


class CapabilityResultView(StrictModel):
    """一次已完成的能力调用,以有界 observation 的形式暴露。

    能力结果是外部证据或为后续周期生成的摘要——绝不是自动确认的
    图上事实。
    """

    invocation_id: str
    capability_id: str
    status: str
    content: Dict[str, Any] = Field(default_factory=dict)
    source_refs: List[str] = Field(default_factory=list)
    provenance: Dict[str, Any] = Field(default_factory=dict)


class AvailableSkillView(StrictModel):
    """冻结输入快照中的一条有界 Skill 目录项。

    只有身份信息和有界元数据——绝不是完整的 SKILL.md、文件系统路径、
    embedding 分数或数据库内部结构。
    """

    skill_id: str
    name: str = ""
    description: str = ""
    compatibility_hint: Optional[str] = None


class UserRequiredSkillView(StrictModel):
    """用户通过节点绑定(" /" 选择器)显式指定的 Skill。

    这是用户指令,不是目录推荐:decision 周期必须激活这个 skill。
    只有当绑定的 id 确实存在于已发现的启用目录中时,Java 才会设置它。
    """

    skill_id: str
    name: str = ""


class SkillCatalogView(StrictModel):
    """带回放证据的有界 Skill 目录。

    ``fingerprint`` 加上 ``truncated`` 让回放能验证模型看到的是同一份目录;
    条目顺序稳定。当存在用户显式指令时,``user_required`` 携带用户对当前
    上下文的 skill 指定。
    """

    skills: List[AvailableSkillView] = Field(default_factory=list)
    truncated: bool = False
    fingerprint: str = ""
    user_required: Optional[UserRequiredSkillView] = None


class RelationView(StrictModel):
    """一条保持方向性的语义关系(线上格式)。

    镜像持久化的 ``ContextRelation``:source、target 与关系类型码。
    方向与存储时完全一致。
    """

    source_node_id: UUID
    target_node_id: UUID
    relation_type: str


class RelatedNodeRef(StrictModel):
    """有界 1-hop 语义上下文中的一个相关规范节点。

    ``direction`` 是相对于锚点而言的:锚点是关系 source 时为 ``OUTGOING``,
    是 target 时为 ``INCOMING``。对称关系在写入时已做规范化,所以
    ``INCOMING`` 只表示存储的端点把锚点放在了 target 一侧。``node`` 携带
    相关节点完整投影后的 NodeView/body,让模型读到真实正文而不只是一个
    不可读的 id。相关节点永远不属于 lineage。
    """

    node_id: UUID
    relation_type: str
    direction: str
    node: NodeView


class RetrievedContextItem(StrictModel):
    """由 runtime 排序后投影进冻结工作记忆的证据条目。

    有意不包含相关性分数和 embedding 细节。scope、authority、content
    和 provenance 才是模型可见的边界。
    """

    source_ref: str
    source_kind: str
    scope: Literal["ROUTE", "PROJECT", "RESOURCE"]
    origin_route_id: Optional[UUID] = None
    authority: Literal[
        "CONFIRMED", "USER_AUTHORED", "EXTERNAL_EVIDENCE", "DERIVED",
        "ASSUMED", "UNRESOLVED", "REJECTED"]
    content: str
    location: Optional[Dict[str, Any]] = None
    provenance: Dict[str, Any] = Field(default_factory=dict)
    retrieval_reason: str = ""


class AgentInputSnapshot(StrictModel):
    snapshot_id: UUID
    context_hash: str
    project_id: UUID
    # 仅当是无路由的 Floating Node NODE_QUERY(Stage C)时 ``route_id`` 才
    # 为 null。其他所有流程都必填(UUID)。必须与
    # ``RouteContextView.route_id`` 上的镜像字段一致。
    route_id: Optional[UUID] = None
    anchor_node_id: Optional[UUID] = None
    route_context: RouteContextView
    lineage: List[LineageEntry] = Field(default_factory=list)
    effective_claims: List[ClaimView] = Field(default_factory=list)
    metadata: SnapshotMetadata
    allowed_source_refs: List[str] = Field(default_factory=list)
    available_capabilities: List[CapabilityDescriptor] = Field(default_factory=list)
    # 面向全新 Decision 上下文的有界 Skill 目录(可选字段,以兼容旧冻结
    # payload 的线上/回放格式)。
    available_skills: SkillCatalogView = Field(default_factory=SkillCatalogView)
    capability_results: List[CapabilityResultView] = Field(default_factory=list)
    # 有界的 1-hop 语义上下文(仅 NODE_QUERY;其他操作为空)。
    relations: List[RelationView] = Field(default_factory=list)
    related_nodes: List[RelatedNodeRef] = Field(default_factory=list)
    retrieved_context: List[RetrievedContextItem] = Field(default_factory=list)
    autonomy: AutonomyInputs


class AgentV2Event(StrictModel):
    kind: str
    anchor_node_id: Optional[UUID] = None
    selected_option_id: Optional[UUID] = None
    free_text: Optional[str] = None
    persistence_intent: Optional[Literal["RECORD_DECISION_NODE"]] = None

    @field_validator("kind")
    @classmethod
    def _known_kind(cls, value: str) -> str:
        if value not in protocol.EVENT_KINDS:
            raise ValueError(f"unknown event kind: {value}")
        return value


class DecisionBudget(StrictModel):
    max_model_calls: int


class ActionEligibilityConstraint(StrictModel):
    eligible: bool
    reason_codes: List[str] = Field(default_factory=list)


class ActionEligibility(StrictModel):
    version: Literal[protocol.ACTION_ELIGIBILITY_VERSION]
    eligible_families: List[str]
    constraints: Dict[str, ActionEligibilityConstraint]
    basis_hash: str

    @model_validator(mode="after")
    def _complete_consistent_mask(self) -> "ActionEligibility":
        expected = set(protocol.ACTION_FAMILIES)
        listed = self.eligible_families
        if len(listed) != len(set(listed)):
            raise ValueError("duplicate eligible action family")
        if not set(listed).issubset(expected):
            raise ValueError("unknown eligible action family")
        if set(self.constraints) != expected:
            raise ValueError("eligibility constraints must cover every action family")
        for family in expected:
            if self.constraints[family].eligible != (family in listed):
                raise ValueError(
                    f"eligibility list/constraint mismatch for family: {family}")
        if len(self.basis_hash) != 64 or any(
                char not in "0123456789abcdef" for char in self.basis_hash):
            raise ValueError("eligibility basisHash must be a SHA-256 hex digest")
        return self


class AgentV2RequestEnvelope(StrictModel):
    protocol_version: Literal[protocol.INPUT_PROTOCOL_VERSION]
    run_id: UUID
    event: AgentV2Event
    snapshot: AgentInputSnapshot
    capabilities: List[CapabilityDescriptor] = Field(default_factory=list)
    decision_budget: DecisionBudget

    @model_validator(mode="after")
    def _route_id_contract(self) -> "AgentV2RequestEnvelope":
        """关于无路由 NODE_QUERY 的权威跨字段规则。

        ``route_id`` 在模型层有意声明为 Optional,以便严格的线上格式能承载
        Floating 节点的 NODE_QUERY。这个校验器是唯一承认该可空性的地方,
        且规则刻意收得很窄:

        - 非 ``NODE_QUERY`` 事件必须在 ``snapshot.routeId`` 和
          ``snapshot.routeContext.routeId`` 两处都携带路由 UUID。
        - ``NODE_QUERY`` 事件只承认两种合法形态:
            (a) 绑定路由:两个字段都是非空 UUID 且相等;
            (b) 无路由:两个字段都为 null。
        - 混合状态(一个为 null、另一个为 UUID)或 UUID 不相等一律拒绝。
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

        # NODE_QUERY:要么 null/null,要么 UUID/UUID 且相等。
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


class AgentV3RequestEnvelope(AgentV2RequestEnvelope):
    protocol_version: Literal[protocol.INPUT_PROTOCOL_VERSION_V3]
    action_eligibility: ActionEligibility


def parse_request_envelope(
        payload: Dict[str, Any]) -> AgentV2RequestEnvelope | AgentV3RequestEnvelope:
    """把原始 JSON 对象解析成请求信封,未知版本 fail-closed。"""
    version = payload.get("protocolVersion")
    if version == protocol.INPUT_PROTOCOL_VERSION:
        return AgentV2RequestEnvelope.model_validate(payload)
    if version == protocol.INPUT_PROTOCOL_VERSION_V3:
        return AgentV3RequestEnvelope.model_validate(payload)
    # 保留 Pydantic 的 ValidationError,而不是为未知版本另造一套并行异常。
    return AgentV2RequestEnvelope.model_validate(payload)
