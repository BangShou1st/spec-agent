"""Deterministic fake model client for tests and offline development.

Returns exactly the canonical fake outputs shared with the Java-side fake
inference gateway (see ``contracts/fixtures/fake-model-*.json``), so both
language paths produce identical deterministic decisions. Never selected in
normal product configuration.
"""

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
    '"What is the most important outcome?","options":[{"label":"Clarify the primary goal"}],'
    '"allowFreeAnswer":true},"sourceRefs":[]}}'
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
        content = {
            "STATE_UPDATE": STATE_UPDATE_OUTPUT,
            "DECISION": DECISION_OUTPUT,
        }.get(call_type)
        if content is None:
            raise ModelClientError(f"unsupported call type: {call_type}")
        return Completion(content=content, finish_reason="stop")
