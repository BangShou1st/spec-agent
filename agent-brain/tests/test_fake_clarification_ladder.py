"""文件名:test_fake_clarification_ladder.py

用途:确定性 fake 澄清阶梯的回归测试:fake 绝不能重复一个已经有答案的
lineage 问题,否则 Java Runtime 强制执行的 RESOLVED_BLOCKER 规则会让
run 失败。与 Java 侧 DeterministicFakeClarificationTest 逐级对应。
"""

import copy
import json
import uuid
from pathlib import Path

from spec_agent_brain.decision import handle_decision
from spec_agent_brain.model_client import FakeModelClient

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"

Q1 = "What is the most important outcome?"
Q2 = "What is the next most important outcome?"
Q3 = "What scope boundaries must be confirmed?"


def test_fake_advances_past_answered_questions():
    from spec_agent_brain.contracts.inputs import parse_request_envelope

    base = json.loads((FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))

    # 零个已回答的 fake 问题:保留标准的第一问,保证 golden decision
    # fixture 仍然匹配。
    request = parse_request_envelope(_without_answers(base))
    response = handle_decision(request, FakeModelClient())
    assert response.action_proposal.payload["questionText"] == Q1

    # 已回答一问(Q1):必须推进到 Q2,绝不重复 Q1。
    request = parse_request_envelope(_with_answered(base, [Q1]))
    response = handle_decision(request, FakeModelClient())
    assert response.action_proposal.payload["questionText"] == Q2

    # 已回答两问(Q1、Q2):必须推进到 Q3。
    request = parse_request_envelope(_with_answered(base, [Q1, Q2]))
    response = handle_decision(request, FakeModelClient())
    assert response.action_proposal.payload["questionText"] == Q3

    # 三问全部已回答(Q1-Q3):回退问题必须避开所有已问过的。
    request = parse_request_envelope(_with_answered(base, [Q1, Q2, Q3]))
    response = handle_decision(request, FakeModelClient())
    question = response.action_proposal.payload["questionText"]
    assert _normalize(question) not in {_normalize(q) for q in (Q1, Q2, Q3)}


def _without_answers(payload):
    mutated = copy.deepcopy(payload)
    for entry in mutated["snapshot"]["lineage"]:
        entry["answer"] = None
    return mutated


def _with_answered(payload, questions):
    mutated = copy.deepcopy(payload)
    lineage = []
    for question in questions:
        node_id = str(uuid.uuid4())
        lineage.append({
            "node": {
                "id": node_id,
                "body": {"text": question, "options": [], "acceptsFreeText": True},
                "kind": "INTERACTION",
            },
            "answer": {
                "id": str(uuid.uuid4()),
                "nodeId": node_id,
                "selectedOptionId": None,
                "freeText": "an answer",
            },
            "patches": [],
        })
    mutated["snapshot"]["lineage"] = lineage
    anchor = lineage[-1]["node"]["id"] if lineage else mutated["snapshot"]["anchorNodeId"]
    mutated["snapshot"]["anchorNodeId"] = anchor
    mutated["event"] = {
        "kind": "ANSWER_SUBMITTED",
        "anchorNodeId": anchor,
        "selectedOptionId": None,
        "freeText": "an answer",
    }
    return mutated


def _normalize(value: str) -> str:
    import re
    import unicodedata
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).strip()).lower()
