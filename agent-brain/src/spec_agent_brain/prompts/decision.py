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
5. REQUEST_USER_INPUT 的 payload 形如 {"kind": "INTERACTION", "questionText": "...", "options": [{"label": "..."}], "allowFreeAnswer": true}；问题文本使用简体中文。
6. 创建非交互节点用 CREATE_NODE，payload 形如 {"kind": "KNOWLEDGE", "subtype": "...", "content": {"text": "..."}}；subtype 只能取该 kind 允许的值（如 KNOWLEDGE: IDEA/NOTE/REQUIREMENT/DECISION/RISK/ASSUMPTION）。
7. 建立语义关系用 CONNECT_NODE，payload 形如 {"relationClass": "SEMANTIC", "relationType": "RELATED_TO|DEPENDS_ON|DERIVED_FROM|CONFLICTS_WITH|SUPPORTS", "sourceRef": "node:...", "targetRef": "node:..."}；两个 ref 都必须来自 allowedSourceRefs。
8. 当事件是 NODE_QUERY（用户就某个节点提问，问题在 freeText 中），默认动作用 RESPOND_TO_USER 直接回答，payload 形如 {"message": "..."}；除非用户明确要求修改 Graph，否则不要提出任何修改动作。
9. availableCapabilities 是运行时按权限与上下文过滤后允许调用的能力清单；需要检索资源内容时用 INVOKE_CAPABILITY，payload 形如 {"capabilityId": "<清单中的 id>", "arguments": {"nodeRef": "node:..."}}；绝不要调用清单之外的能力，绝不要编造 capabilityId。
10. capabilityResults 是先前能力调用返回的观察证据（外部来源或生成摘要），可以引用其 sourceRefs 作为依据，但它们不是用户已确认的事实；不要把能力结果直接当作 confirmed 结论。
11. payload 中绝不携带任何 id 类字段（id、nodeId、optionId 等）；所有 id 由 Runtime 分配。
12. sourceRefs 只能引用输入中 allowedSourceRefs 列出的引用；绝不编造引用。
13. anchorRefs 用于声明操作锚点（如当前路由 tip 节点的 node: 引用），也必须是 allowedSourceRefs 的子集。
14. projectTitle 只是低权重的显示元数据，绝不是目标或需求；如果还没有可靠目标，就在 unknowns 中表达不确定，而不是编造一个目标。
15. 不要建议绕过用户确认的破坏性操作；默认处于顾问（ADVISOR）模式。
16. 除该 JSON 对象外不要输出任何其他文字。"""


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
            "metadata": snapshot.metadata.model_dump(mode="json", by_alias=True),
        },
        "decisionBudget": envelope.decision_budget.model_dump(mode="json", by_alias=True),
    }
    return json.dumps(payload, ensure_ascii=False)
