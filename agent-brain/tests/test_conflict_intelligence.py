import json
from pathlib import Path

import pytest

from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.decision import BrainContractError, handle_decision
from spec_agent_brain.model_client import Completion
from spec_agent_brain.prompts.state_update import (
    SYSTEM_PROMPT as STATE_UPDATE_SYSTEM_PROMPT,
    render_user_prompt as render_state_update_prompt,
)


FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"


class ScriptedClient:
    def __init__(self, output: dict):
        self._content = json.dumps(output, ensure_ascii=False)

    def complete(self, run_id, call_type, messages, max_output_tokens=2048) -> Completion:
        return Completion(content=self._content, finish_reason="stop")


class SequencedClient:
    """Returns one scripted output per call and records the messages it saw.

    The last output repeats if the engine asks for more calls than scripted, so
    a test that forbids a second call still fails for the right reason.
    """

    def __init__(self, outputs: list):
        self._outputs = [
            item if isinstance(item, str) else json.dumps(item, ensure_ascii=False)
            for item in outputs
        ]
        self.calls: list[list] = []

    def complete(self, run_id, call_type, messages, max_output_tokens=2048) -> Completion:
        self.calls.append(list(messages))
        index = min(len(self.calls) - 1, len(self._outputs) - 1)
        return Completion(content=self._outputs[index], finish_reason="stop")


def _request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8")
    )
    return parse_request_envelope(payload)


def _request_with_unresolved_conflict():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8")
    )
    payload["snapshot"]["effectiveClaims"].append({
        "kind": "conflict",
        "text": "要求一次性交付全部功能，与仅有一名兼职开发者的约束互斥。",
        "status": "unresolved",
        "confidence": 0.95,
        "sourceNodeId": None,
        "sourceAnswerId": None,
    })
    return parse_request_envelope(payload)


def _with_budget(request, max_model_calls: int):
    payload = request.model_dump(mode="json", by_alias=True)
    payload["decisionBudget"] = {"maxModelCalls": max_model_calls}
    return parse_request_envelope(payload)


def _decision_output(action_family: str, payload: dict, conflicts=None):
    return {
        "observation": {
            "known": [],
            "unknowns": [],
            "conflicts": conflicts or [],
            "risks": [],
        },
        "action": {
            "actionFamily": action_family,
            "payload": payload,
            "sourceRefs": [],
            "anchorRefs": [],
        },
    }


def test_state_update_prompt_exposes_effective_claims_for_cross_claim_check():
    request = _request()
    payload = json.loads(render_state_update_prompt(request))

    assert payload["snapshot"]["effectiveClaims"] == [
        {
            "kind": "goal",
            "text": "用户希望减少因邮件沟通导致的需求遗漏。",
            "status": "confirmed",
            "confidence": 0.9,
            "sourceNodeId": "33333333-3333-3333-3333-333333333333",
            "sourceAnswerId": "99999999-9999-9999-9999-999999999999",
        }
    ]
    assert "conflict" in STATE_UPDATE_SYSTEM_PROMPT
    assert "互斥" in STATE_UPDATE_SYSTEM_PROMPT


def test_unresolved_conflict_accepts_any_action_once_reported():
    # Slice 4: the action-family whitelist is removed. Once the conflict is
    # faithfully reported, WAIT is as acceptable as any other family —
    # execution safety belongs to the Java Runtime gates.
    request = _request_with_unresolved_conflict()
    output = _decision_output(
        "WAIT",
        {},
        conflicts=["交付范围与开发资源约束互斥。"],
    )

    response = handle_decision(request, ScriptedClient(output))
    assert response.action_proposal.action_family == "WAIT"


def test_unresolved_conflict_requires_observation_conflict():
    request = _request_with_unresolved_conflict()
    output = _decision_output(
        "REQUEST_USER_INPUT",
        {
            "kind": "INTERACTION",
            "questionText": "优先缩小范围还是增加开发资源？",
            "purpose": "解决当前互斥约束。",
            "options": [{"label": "缩小范围"}, {"label": "增加资源"}],
            "allowFreeAnswer": True,
        },
    )

    with pytest.raises(BrainContractError, match="observation.conflicts"):
        handle_decision(request, ScriptedClient(output))


def test_conflict_surfacing_gets_one_bounded_repair():
    request = _request_with_unresolved_conflict()
    first = _decision_output("WAIT", {})
    second = _decision_output(
        "REQUEST_USER_INPUT",
        {
            "kind": "INTERACTION",
            "questionText": "优先缩小范围还是增加开发资源？",
            "purpose": "解决当前互斥约束。",
            "options": [{"label": "缩小范围"}, {"label": "增加资源"}],
            "allowFreeAnswer": True,
        },
        conflicts=["要求一次性交付全部功能，与仅有一名兼职开发者的约束互斥。"],
    )
    client = SequencedClient([first, second])

    response = handle_decision(request, client)

    assert response.action_proposal.action_family == "REQUEST_USER_INPUT"
    # 预算是 2 次调用；补救调用必须如实计入用量，不能被隐藏。
    assert response.usage.model_calls == 2
    assert len(client.calls) == 2
    repair = client.calls[1][-1]
    assert repair.role == "user"
    assert "observation.conflicts" in repair.content
    # 纠正指令必须带上冲突原文，模型只需照抄而不必重新推断。
    assert "与仅有一名兼职开发者的约束互斥" in repair.content


def test_no_repair_when_budget_funds_only_one_call():
    request = _with_budget(_request_with_unresolved_conflict(), 1)
    client = SequencedClient([_decision_output("WAIT", {})])

    with pytest.raises(BrainContractError, match="observation.conflicts"):
        handle_decision(request, client)
    assert len(client.calls) == 1


def test_other_contract_violations_still_fail_on_the_first_output():
    # 输出不是 JSON 与「忘记写冲突」是两回事，不得触发补救调用。
    request = _request_with_unresolved_conflict()
    client = SequencedClient(["not json at all"])

    with pytest.raises(BrainContractError, match="not valid JSON"):
        handle_decision(request, client)
    assert len(client.calls) == 1


def test_unresolved_conflict_allows_request_user_input():
    request = _request_with_unresolved_conflict()
    output = _decision_output(
        "REQUEST_USER_INPUT",
        {
            "kind": "INTERACTION",
            "questionText": "优先缩小范围还是增加开发资源？",
            "purpose": "解决当前互斥约束。",
            "options": [{"label": "缩小范围"}, {"label": "增加资源"}],
            "allowFreeAnswer": True,
        },
        conflicts=["交付范围与开发资源约束互斥。"],
    )

    response = handle_decision(request, ScriptedClient(output))
    assert response.action_proposal.action_family == "REQUEST_USER_INPUT"


def test_unresolved_conflict_allows_explicit_decision_node():
    request = _request_with_unresolved_conflict()
    output = _decision_output(
        "CREATE_NODE",
        {
            "kind": "KNOWLEDGE",
            "subtype": "DECISION",
            "content": {
                "text": "决定先交付核心流程，非核心功能延后，以匹配当前开发资源。"
            },
        },
        conflicts=["交付范围与开发资源约束互斥。"],
    )

    response = handle_decision(request, ScriptedClient(output))
    assert response.action_proposal.action_family == "CREATE_NODE"
    assert response.action_proposal.payload["subtype"] == "DECISION"
