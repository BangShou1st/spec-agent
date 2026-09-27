"""文件名:fake.py

用途:用于测试与离线开发的确定性 fake 模型客户端。

返回与 Java 侧 fake 推理网关共享的标准 fake 输出(见
``contracts/fixtures/fake-model-*.json``),保证两种语言路径产生完全相同的
确定性决策。正常产品配置中永远不会选中它。
"""

import json
import re
import unicodedata
from typing import Sequence

from .base import ChatMessage, Completion, ModelClientError, require_call_type

# 必须与 contracts/fixtures/fake-model-state-update-output.json 完全一致。
STATE_UPDATE_OUTPUT = (
    '{"claims":[{"kind":"goal","text":"The user clarified the main outcome.",'
    '"status":"confirmed","confidence":0.9,"sourceRefs":[]}]}'
)

# 必须与 contracts/fixtures/fake-model-decision-output.json 完全一致。
DECISION_OUTPUT = (
    '{"observation":{"known":["The user clarified the main outcome."],'
    '"unknowns":["The user must confirm scope boundaries."],"conflicts":[],"risks":[]},'
    '"action":{"actionFamily":"REQUEST_USER_INPUT","payload":{"questionText":'
    '"What is the most important outcome?","purpose":"This clarifies the primary '
    'requirement goal.","options":[{"label":"Clarify the primary goal"}],'
    '"allowFreeAnswer":true},"sourceRefs":[]}}'
)

# 必须与 contracts/fixtures/fake-model-artifact-output.json 完全一致。
# ``{{CONTEXT_REF}}`` 占位符在补全时替换为请求自己的 context ref:
# fake 从不发明 id,而是复用输入 prompt 里可信的快照标识。
ARTIFACT_GENERATION_OUTPUT = (
    '{"artifactType":"spec_snapshot",'
    '"sections":[{"title":"Overview","content":"用户澄清了主要目标：明确最重要的成果。",'
    '"sourceRefs":["{{CONTEXT_REF}}"]},'
    '{"title":"Open Questions","content":"范围边界尚未确认，需要用户进一步澄清。",'
    '"sourceRefs":["{{CONTEXT_REF}}"]}],'
    '"unresolvedItems":["范围边界尚未确认。"]}'
)


# 与 Java 侧 LocalDeterministicDecisionEngine 共享的确定性澄清阶梯:
# fake 绝不重复一个已经有答案的 lineage 问题,否则强制执行的
# RESOLVED_BLOCKER 规则会让 run 失败。第一级保留标准的 fake 问题,使
# 零回答场景的行为(以及上面的 golden fixture)保持不变。
_FOLLOW_UP_LADDER = (
    ("What is the most important outcome?",
     "This clarifies the primary requirement goal.",
     "Clarify the primary goal"),
    ("What is the next most important outcome?",
     "This clarifies the next requirement goal.",
     "Clarify the next goal"),
    ("What scope boundaries must be confirmed?",
     "This confirms the scope boundaries.",
     "Confirm the scope boundaries"),
)

_FOLLOW_UP_PURPOSE = "This clarifies the remaining requirement details."
_FOLLOW_UP_OPTION_LABEL = "Clarify the remaining details."


def _normalize_question(value) -> str:
    """与 Java eligibility 门禁完全相同的归一化(NFKC + 单空格 + 转小写):
    fake 就是用这个形态做比较的。"""
    if not isinstance(value, str):
        return ""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).strip()).lower()


def _answered_questions(messages: Sequence[ChatMessage]) -> set:
    """从渲染后的 prompt 里的可信快照中,收集已带答案的 lineage 节点的
    归一化文本。"""
    answered = set()
    for message in reversed(messages):
        try:
            payload = json.loads(message.content)
        except (json.JSONDecodeError, AttributeError):
            continue
        if not isinstance(payload, dict):
            continue
        lineage = payload.get("snapshot", {}).get("lineage")
        if not isinstance(lineage, list):
            continue
        for entry in lineage:
            if not isinstance(entry, dict) or entry.get("answer") is None:
                continue
            node = entry.get("node") or {}
            text = (node.get("body") or {}).get("text")
            answered.add(_normalize_question(text))
        return answered
    return answered


def _decision_output(messages: Sequence[ChatMessage]) -> str:
    """为下一个尚未回答的阶梯渲染标准的 decision 输出。"""
    answered = _answered_questions(messages)
    for question, purpose, option_label in _FOLLOW_UP_LADDER:
        if _normalize_question(question) not in answered:
            return _render_decision(question, purpose, option_label)
    follow_up = len(answered) + 1
    while True:
        question = f"What else should be clarified next? (follow-up {follow_up})"
        if _normalize_question(question) not in answered:
            return _render_decision(question, _FOLLOW_UP_PURPOSE, _FOLLOW_UP_OPTION_LABEL)
        follow_up += 1


def _render_decision(question: str, purpose: str, option_label: str) -> str:
    return (
        '{"observation":{"known":["The user clarified the main outcome."],'
        '"unknowns":["The user must confirm scope boundaries."],"conflicts":[],"risks":[]},'
        '"action":{"actionFamily":"REQUEST_USER_INPUT","payload":{"questionText":'
        + json.dumps(question)
        + ',"purpose":'
        + json.dumps(purpose)
        + ',"options":[{"label":'
        + json.dumps(option_label)
        + '}],"allowFreeAnswer":true},"sourceRefs":[]}}'
    )


class FakeModelClient:
    def complete(
        self,
        run_id: str,
        call_type: str,
        messages: Sequence[ChatMessage],
        max_output_tokens: int = 2048,
    ) -> Completion:
        require_call_type(call_type)
        if call_type == "ARTIFACT_GENERATION":
            return Completion(
                content=_artifact_output(messages), finish_reason="stop"
            )
        if call_type == "DECISION":
            return Completion(content=_decision_output(messages), finish_reason="stop")
        if call_type == "STATE_UPDATE":
            return Completion(content=STATE_UPDATE_OUTPUT, finish_reason="stop")
        raise ModelClientError(f"unsupported call type: {call_type}")


def _context_ref_from_prompt(messages: Sequence[ChatMessage]) -> str:
    """从渲染后的 user prompt 中挑出快照自己的 context ref。

    工件契约要求每个 section 都引用允许的 source ref;确定性的 fake 复用
    可信的 context 标识,而不是发明一个。
    """
    for message in reversed(messages):
        try:
            payload = json.loads(message.content)
        except (json.JSONDecodeError, AttributeError):
            continue
        refs = (
            payload.get("snapshot", {}).get("allowedSourceRefs")
            if isinstance(payload, dict)
            else None
        )
        if refs:
            for ref in refs:
                if ref.startswith("context:"):
                    return ref
    raise ModelClientError("fake artifact output requires a context ref in the prompt")


def _artifact_output(messages: Sequence[ChatMessage]) -> str:
    """渲染带真实 context refs 的标准 artifact 输出。"""
    return ARTIFACT_GENERATION_OUTPUT.replace(
        "{{CONTEXT_REF}}", _context_ref_from_prompt(messages)
    )
