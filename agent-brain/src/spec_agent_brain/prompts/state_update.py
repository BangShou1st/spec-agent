"""STATE_UPDATE prompt: answer/evidence -> grounded claims.

Language contract: instructions and any user-visible generated text are
Simplified Chinese; machine protocol keys and enum values stay unchanged.
"""

import json
from typing import Any, Dict

from ..contracts.inputs import AgentV2RequestEnvelope

SYSTEM_PROMPT = """你是需求工作区的状态更新引擎。你的唯一任务：把用户提供的回答/证据，转换为有出处的结构化 claim 列表，并检查新信息与当前有效 claims 是否存在真正互斥。

规则：
1. 只输出一个 JSON 对象，形如 {"claims": [...]}，不要输出任何其他文字。
2. 每个 claim 形如 {"kind": "...", "text": "...", "status": "...", "confidence": 0.0-1.0, "sourceRefs": ["..."]}。
3. kind 只能取：goal, stakeholder, scope, constraint, success_criterion, output_expectation, risk, assumption, open_question, conflict, other。
4. status 只能取：confirmed（用户明确确认）、assumed（你推断但未确认）、unresolved（待澄清）、rejected（用户否定）。
5. sourceRefs 只能引用输入中 allowedSourceRefs 列出的引用；绝不编造任何 id 或引用。
6. snapshot.effectiveClaims 是本次回答之前已经生效的需求状态。你必须把 event 中的新回答/证据与 effectiveClaims 中的 confirmed claim，以及会实质限制方案的 assumed claim 做一致性检查。
7. 只有两项要求在同一范围、时间或资源条件下不能同时成立时才是 conflict；普通取舍压力、信息不足、偏好差异或可通过常规规划同时满足的内容不是 conflict。
8. 发现真实互斥时，必须额外输出 kind=conflict、status=unresolved 的 claim。文本要明确写出冲突双方和不能同时成立的原因；已有一侧能用 allowedSourceRefs 溯源时应引用对应 ref。不得把互斥双方静默地都标为 confirmed 而不输出 conflict。
9. 如果用户本次回答明确否定了旧 claim，应输出对应 rejected/替代 claim；不要把已经被明确撤销的旧要求继续当作 unresolved conflict。
10. 上下文中的 projectTitle 只是低权重的工作区显示元数据，绝不是目标、需求或范围；不得仅凭标题推断任何 confirmed claim。
11. 没有依据的内容要么不输出，要么标记为 assumed/unresolved；不确定就表达不确定。
12. claim 文本使用简体中文。"""


def render_user_prompt(envelope: AgentV2RequestEnvelope) -> str:
    snapshot = envelope.snapshot
    payload: Dict[str, Any] = {
        "event": envelope.event.model_dump(mode="json", by_alias=True),
        "snapshot": {
            "snapshotId": str(snapshot.snapshot_id),
            "allowedSourceRefs": snapshot.allowed_source_refs,
            "lineage": [
                {
                    "node": entry.node.model_dump(mode="json", by_alias=True),
                    "answer": entry.answer.model_dump(mode="json", by_alias=True) if entry.answer else None,
                    "patches": [p.model_dump(mode="json", by_alias=True) for p in entry.patches],
                }
                for entry in snapshot.lineage
            ],
            "effectiveClaims": [
                claim.model_dump(mode="json", by_alias=True)
                for claim in snapshot.effective_claims
            ],
            "metadata": snapshot.metadata.model_dump(mode="json", by_alias=True),
        },
    }
    return json.dumps(payload, ensure_ascii=False)
