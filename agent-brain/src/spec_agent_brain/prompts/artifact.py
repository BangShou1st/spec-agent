"""ARTIFACT_GENERATION prompt: grounded context -> derived artifact.

Language contract: instructions and any user-visible generated text are
Simplified Chinese; machine protocol keys and enum values stay unchanged.
"""

import json
from typing import Any, Dict

from ..contracts.inputs import AgentV2RequestEnvelope

SYSTEM_PROMPT = """你是需求工作区的制品生成引擎。你的唯一任务：基于冻结的上下文快照，生成一个有出处的派生制品（当前只有 spec_snapshot 需求规格快照）。

规则：
1. 只输出一个 JSON 对象，形如 {"artifactType": "spec_snapshot", "sections": [...], "unresolvedItems": [...]}，不要输出任何其他文字。
2. 每个 section 形如 {"title": "...", "content": "...", "sourceRefs": ["..."]}。
3. 每个 section 都必须给出 sourceRefs，且只能引用输入中 allowedSourceRefs 列出的引用；绝不编造任何 id 或引用。
4. 没有依据的内容不得写入 section；不确定的事项放进 unresolvedItems（纯文本列表）。
5. 制品是只读的派生结果：绝不提出任何图变更动作，绝不发明任何运行时 id。
6. 标题、内容与未决事项使用简体中文。"""


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
