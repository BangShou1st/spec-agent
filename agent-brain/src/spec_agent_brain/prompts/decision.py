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
4. REQUEST_USER_INPUT 的 payload 形如 {"questionText": "...", "options": [{"label": "..."}], "allowFreeAnswer": true}；问题文本使用简体中文。
5. payload 中绝不携带任何 id 类字段（id、nodeId、optionId 等）；所有 id 由 Runtime 分配。
6. sourceRefs 只能引用输入中 allowedSourceRefs 列出的引用；绝不编造引用。
7. projectTitle 只是低权重的显示元数据，绝不是目标或需求；如果还没有可靠目标，就在 unknowns 中表达不确定，而不是编造一个目标。
8. 不要建议绕过用户确认的破坏性操作；默认处于顾问（ADVISOR）模式。
9. 除该 JSON 对象外不要输出任何其他文字。"""


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
            "metadata": snapshot.metadata.model_dump(mode="json", by_alias=True),
        },
        "decisionBudget": envelope.decision_budget.model_dump(mode="json", by_alias=True),
    }
    return json.dumps(payload, ensure_ascii=False)
