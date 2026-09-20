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
2a. sections 按以下固定目录组织（用于整理成可交付开发的文档）；某一节确无内容时省略该节，不要输出空节，也不要发明目录之外的章节标题：
    1)「背景与目标」：为什么做这件事，要达成的目标与成功标准。
    2)「范围」：包含什么、明确不包含什么（非目标）。
    3)「功能需求」：按需求点分条陈述，每条写清行为与预期结果。
    4)「非功能需求与约束」：性能、安全、兼容、技术或业务约束。
    5)「验收标准」：可验证的验收条目，与功能需求一一对应。
    6)「风险与假设」：已识别的风险和目前依赖的假设。
    content 中可以用 markdown 列表分条，条目以「- 」开头。
3. 每个 section 都必须给出 sourceRefs，且只能引用输入中 allowedSourceRefs 列出的引用；绝不编造任何 id 或引用。
4. 没有依据的内容不得写入 section；不确定的事项放进 unresolvedItems（纯文本列表），它们会成为交付文档中的「未决问题」。
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
