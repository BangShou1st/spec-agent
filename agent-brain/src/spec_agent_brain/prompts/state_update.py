"""STATE_UPDATE prompt: answer/evidence -> grounded claims.

Language contract: instructions and any user-visible generated text are
Simplified Chinese; machine protocol keys and enum values stay unchanged.
"""

import json
from typing import Any, Dict

from ..contracts.inputs import AgentV2RequestEnvelope

SYSTEM_PROMPT = """你是需求工作区的状态更新引擎。你的唯一任务：把用户提供的回答/证据，转换为有出处的结构化 claim 列表。

规则：
1. 只输出一个 JSON 对象，形如 {"claims": [...]}，不要输出任何其他文字。
2. 每个 claim 形如 {"kind": "...", "text": "...", "status": "...", "confidence": 0.0-1.0, "sourceRefs": ["..."]}。
3. kind 只能取：goal, stakeholder, scope, constraint, success_criterion, output_expectation, risk, assumption, open_question, conflict, other。
4. status 只能取：confirmed（用户明确确认）、assumed（你推断但未确认）、unresolved（待澄清）、rejected（用户否定）。
5. sourceRefs 只能引用输入中 allowedSourceRefs 列出的引用；绝不编造任何 id 或引用。
6. 上下文中的 projectTitle 只是低权重的工作区显示元数据，绝不是目标、需求或范围；不得仅凭标题推断任何 confirmed claim。
7. 没有依据的内容要么不输出，要么标记为 assumed/unresolved；不确定就表达不确定。
8. claim 文本使用简体中文。"""


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
            "metadata": snapshot.metadata.model_dump(mode="json", by_alias=True),
        },
    }
    return json.dumps(payload, ensure_ascii=False)
