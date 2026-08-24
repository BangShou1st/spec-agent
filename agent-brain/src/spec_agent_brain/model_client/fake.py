"""Deterministic fake model client for tests and offline development.

Returns exactly the canonical fake outputs shared with the Java-side fake
inference gateway (see ``contracts/fixtures/fake-model-*.json``), so both
language paths produce identical deterministic decisions. Never selected in
normal product configuration.
"""

import json
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
        content = {
            "STATE_UPDATE": STATE_UPDATE_OUTPUT,
            "DECISION": DECISION_OUTPUT,
        }.get(call_type)
        if content is None:
            raise ModelClientError(f"unsupported call type: {call_type}")
        return Completion(content=content, finish_reason="stop")


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
