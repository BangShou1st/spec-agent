"""DECISION prompt: reflection + planning + primary action in ONE response.

Reflection and Planning are deliberately part of the same model call; this
boundary must never force them into separate HTTP/LLM round trips.
"""

import json
from typing import Any, Dict

from ..contracts.inputs import AgentV2RequestEnvelope

SYSTEM_PROMPT = """你是需求工作区的决策引擎。你在一次响应中完成反思（reflection）与规划（planning），只输出一个 JSON 对象。

输出结构：
{"observation": {"known": [...], "unknowns": [...], "conflicts": [...], "risks": [...]},
 "action": {"actionFamily": "...", "payload": {...}, "sourceRefs": [...]}}

规则：
1. observation 是你的结构化反思：known 只写有 Graph 依据的事实；unknowns 写缺失信息而不是猜测；conflicts 指出互斥的 claim；risks 区分观察到的证据与你的推断。
2. actionFamily 只能取：CREATE_NODE, UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE, REQUEST_USER_INPUT, RESPOND_TO_USER, INVOKE_CAPABILITY, GENERATE_ARTIFACT, WAIT。
3. 一个周期只提出一个主动作（primary action），不要发散多个动作。
4. 节点有四种稳定外类 kind：KNOWLEDGE（用户或 AI 撰写的知识/需求内容）、INTERACTION（交互，如提问）、RESOURCE（外部资源引用）、ARTIFACT（生成的制品）。新增能力通过 payload 语义表达，不存在按业务命名的动作。
5. REQUEST_USER_INPUT 的 payload 形如 {"kind": "INTERACTION", "questionText": "...", "purpose": "为什么问这个（可选，一句话）", "options": [{"label": "..."}], "allowFreeAnswer": true}；问题文本使用简体中文。
6. 创建非交互节点用 CREATE_NODE，payload 形如 {"kind": "KNOWLEDGE", "subtype": "...", "content": {"text": "..."}}；subtype 只能取该 kind 允许的值（如 KNOWLEDGE: IDEA/NOTE/REQUIREMENT/DECISION/RISK/ASSUMPTION）。
7. 建立语义关系用 CONNECT_NODE，payload 形如 {"relationClass": "SEMANTIC", "relationType": "RELATED_TO|DEPENDS_ON|DERIVED_FROM|CONFLICTS_WITH|SUPPORTS", "sourceRef": "node:...", "targetRef": "node:..."}；两个 ref 都必须来自 allowedSourceRefs。
8. 当事件是 NODE_QUERY（用户就某个节点提问，问题在 freeText 中），默认动作用 RESPOND_TO_USER 直接回答，payload 形如 {"message": "..."}；除非用户明确要求修改 Graph，否则不要提出任何修改动作。
9. 对非 NODE_QUERY 的决策，如果 snapshot.effectiveClaims 中存在 kind=conflict 且 status=unresolved 的 claim，你必须在 observation.conflicts 中明确指出冲突，并让本周期主动作直接推进冲突解决。默认动作必须是 REQUEST_USER_INPUT，问题要让用户在互斥目标/约束之间做明确取舍，不能用 WAIT、继续普通澄清问题或执行与冲突无关的动作绕开它。
10. 只有当当前 event 明确授权你代为权衡/决定冲突时，才可用 CREATE_NODE 创建 KNOWLEDGE/DECISION；content.text 必须写明最终取舍以及为什么这样取舍。不得用 REQUIREMENT、ASSUMPTION 等其他 subtype 静默替用户做决定。
11. availableCapabilities 是运行时按权限与上下文过滤后允许调用的能力清单；需要检索资源内容时用 INVOKE_CAPABILITY，payload 形如 {"capabilityId": "<清单中的 id>", "arguments": {"nodeRef": "node:..."}}；绝不要调用清单之外的能力，绝不要编造 capabilityId。
12. capabilityResults 是先前能力调用返回的观察证据（外部来源或生成摘要），可以引用其 sourceRefs 作为依据，但它们不是用户已确认的事实；不要把能力结果直接当作 confirmed 结论。
13. payload 中绝不携带任何 id 类字段（id、nodeId、optionId 等）；所有 id 由 Runtime 分配。
14. sourceRefs 只能引用输入中 allowedSourceRefs 列出的引用；绝不编造引用。
15. anchorRefs 用于声明操作锚点（如当前路由 tip 节点的 node: 引用），也必须是 allowedSourceRefs 的子集。
16. projectTitle 只是低权重的显示元数据，绝不是目标或需求；如果还没有可靠目标，就在 unknowns 中表达不确定，而不是编造一个目标。
17. 不要建议绕过用户确认的破坏性操作；默认处于顾问（ADVISOR）模式。
18. relations 与 relatedNodes 是受控的 1-hop 语义上下文（仅 NODE_QUERY）：relatedNodes 只包含直接关联的节点及其真实内容，绝不臆测未提供的第二跳关系；DEPENDS_ON / DERIVED_FROM / SUPPORTS 保留 source → target 方向语义，RELATED_TO / CONFLICTS_WITH 是对称事实；引用 relatedNode 时必须使用其 allowedSourceRefs 中的 node:<id> 引用。
19. 除该 JSON 对象外不要输出任何其他文字。"""


def _related_node_view(ref) -> Dict[str, Any]:
    """Projection of one related node: provenance plus the full node body."""
    return {
        "nodeId": str(ref.node_id),
        "relationType": ref.relation_type,
        "direction": ref.direction,
        "node": {
            "id": str(ref.node.id),
            "kind": ref.node.kind,
            "body": {
                "text": ref.node.body.text,
                "options": [
                    {"id": str(o.id), "label": o.label}
                    for o in ref.node.body.options
                ],
                "acceptsFreeText": ref.node.body.accepts_free_text,
            },
        },
    }


def render_user_prompt(envelope: AgentV2RequestEnvelope) -> str:
    snapshot = envelope.snapshot
    payload: Dict[str, Any] = {
        "event": envelope.event.model_dump(mode="json", by_alias=True),
        "snapshot": {
            "snapshotId": str(snapshot.snapshot_id),
            "contextHash": snapshot.context_hash,
            "anchorNodeId": str(snapshot.anchor_node_id) if snapshot.anchor_node_id else None,
            "allowedSourceRefs": snapshot.allowed_source_refs,
            "lineage": [
                {
                    "node": entry.node.model_dump(mode="json", by_alias=True),
                    "answer": entry.answer.model_dump(mode="json", by_alias=True) if entry.answer else None,
                    "patches": [p.model_dump(mode="json", by_alias=True) for p in entry.patches],
                }
                for entry in snapshot.lineage
            ],
            "effectiveClaims": [c.model_dump(mode="json", by_alias=True) for c in snapshot.effective_claims],
            "availableCapabilities": [c.model_dump(mode="json", by_alias=True) for c in snapshot.available_capabilities],
            "capabilityResults": [r.model_dump(mode="json", by_alias=True) for r in snapshot.capability_results],
            "relations": [
                {
                    "sourceNodeId": str(rel.source_node_id),
                    "targetNodeId": str(rel.target_node_id),
                    "relationType": rel.relation_type,
                }
                for rel in snapshot.relations
            ],
            "relatedNodes": [_related_node_view(ref) for ref in snapshot.related_nodes],
            "metadata": snapshot.metadata.model_dump(mode="json", by_alias=True),
        },
        "decisionBudget": envelope.decision_budget.model_dump(mode="json", by_alias=True),
    }
    return json.dumps(payload, ensure_ascii=False)
