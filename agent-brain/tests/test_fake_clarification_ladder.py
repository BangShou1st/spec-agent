"""Regression for the deterministic fake clarification ladder: the fake must
never repeat an already-answered lineage question, or the Java Runtime's
enforced RESOLVED_BLOCKER rule fails the run. Mirrors the Java-side
DeterministicFakeClarificationTest rung for rung."""

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

    # Zero answered fake questions: the canonical first question is kept, so
    # the golden decision fixture still matches.
    request = parse_request_envelope(_without_answers(base))
    response = handle_decision(request, FakeModelClient())
    assert response.action_proposal.payload["questionText"] == Q1

    # One answered (Q1): must advance to Q2, never repeat Q1.
    request = parse_request_envelope(_with_answered(base, [Q1]))
    response = handle_decision(request, FakeModelClient())
    assert response.action_proposal.payload["questionText"] == Q2

    # Two answered (Q1, Q2): must advance to Q3.
    request = parse_request_envelope(_with_answered(base, [Q1, Q2]))
    response = handle_decision(request, FakeModelClient())
    assert response.action_proposal.payload["questionText"] == Q3

    # Three answered (Q1-Q3): the fallback must stay clear of every one.
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
