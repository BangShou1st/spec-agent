"""Deterministic fake model client for tests and offline development.

Returns exactly the canonical fake outputs shared with the Java-side fake
inference gateway (see ``contracts/fixtures/fake-model-*.json``), so both
language paths produce identical deterministic decisions. Never selected in
normal product configuration.
"""

import json
import re
import unicodedata
from typing import Sequence

from .base import ChatMessage, Completion, ModelClientError, require_call_type

# Must stay identical to contracts/fixtures/fake-model-state-update-output.json.
STATE_UPDATE_OUTPUT = (
    '{"claims":[{"kind":"goal","text":"The user clarified the main outcome.",'
    '"status":"confirmed","confidence":0.9,"sourceRefs":[]}]}'
)

# Must stay identical to contracts/fixtures/fake-model-decision-output.json.
DECISION_OUTPUT = (
    '{"observation":{"known":["The user clarified the main outcome."],'
    '"unknowns":["The user must confirm scope boundaries."],"conflicts":[],"risks":[]},'
    '"action":{"actionFamily":"REQUEST_USER_INPUT","payload":{"questionText":'
    '"What is the most important outcome?","purpose":"This clarifies the primary '
    'requirement goal.","options":[{"label":"Clarify the primary goal"}],'
    '"allowFreeAnswer":true},"sourceRefs":[]}}'
)

# Must stay identical to contracts/fixtures/fake-model-artifact-output.json.
# The ``{{CONTEXT_REF}}`` placeholder is substituted with the request's own
# context ref at completion time: the fake never invents ids, it reuses the
# trusted snapshot identity from its input prompt.
ARTIFACT_GENERATION_OUTPUT = (
    '{"artifactType":"spec_snapshot",'
    '"sections":[{"title":"Overview","content":"用户澄清了主要目标：明确最重要的成果。",'
    '"sourceRefs":["{{CONTEXT_REF}}"]},'
    '{"title":"Open Questions","content":"范围边界尚未确认，需要用户进一步澄清。",'
    '"sourceRefs":["{{CONTEXT_REF}}"]}],'
    '"unresolvedItems":["范围边界尚未确认。"]}'
)


# Deterministic clarification ladder shared with the Java-side
# LocalDeterministicDecisionEngine: the fake never repeats an already-answered
# lineage question, or the enforced RESOLVED_BLOCKER rule fails the run. The
# first rung keeps the canonical fake question so zero-answered behavior (and
# the golden fixture above) is unchanged.
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
    """Same normalization the Java eligibility gate enforces (NFKC + single
    spaces + lower): the fake compares with exactly this form."""
    if not isinstance(value, str):
        return ""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).strip()).lower()


def _answered_questions(messages: Sequence[ChatMessage]) -> set:
    """Collects normalized texts of lineage nodes that already carry an answer,
    read from the trusted snapshot inside the rendered prompt."""
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
    """Renders the canonical decision output for the next unanswered rung."""
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
    """Picks the snapshot's own context ref out of the rendered user prompt.

    The artifact contract requires every section to cite allowed source refs;
    the deterministic fake reuses the trusted context identity instead of
    inventing one.
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
    """Renders the canonical artifact output with its real context refs."""
    return ARTIFACT_GENERATION_OUTPUT.replace(
        "{{CONTEXT_REF}}", _context_ref_from_prompt(messages)
    )
