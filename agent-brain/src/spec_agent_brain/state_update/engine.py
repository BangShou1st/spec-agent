"""STATE_UPDATE engine: one model call, strict parse, runtime-stamped envelope."""

import json

from ..contracts.decisions import (
    AgentV2ResponseEnvelope,
    ModelStateUpdateOutput,
    StateUpdateResult,
    UsageView,
)
from ..contracts.inputs import AgentV2RequestEnvelope
from ..contracts.protocol import DECISION_PROTOCOL_VERSION
from ..model_client import ChatMessage, ModelClient
from ..prompts import state_update as state_update_prompt


class BrainContractError(RuntimeError):
    """Raised when a model output violates the brain's own output contract."""


def handle_state_update(request: AgentV2RequestEnvelope, client: ModelClient) -> AgentV2ResponseEnvelope:
    if request.decision_budget.max_model_calls < 1:
        raise BrainContractError("decision budget does not allow any model call")

    completion = client.complete(
        run_id=str(request.run_id),
        call_type="STATE_UPDATE",
        messages=[
            ChatMessage(role="system", content=state_update_prompt.SYSTEM_PROMPT),
            ChatMessage(role="user", content=state_update_prompt.render_user_prompt(request)),
        ],
    )
    output = _parse_model_output(completion.content)

    return AgentV2ResponseEnvelope(
        protocol_version=DECISION_PROTOCOL_VERSION,
        run_id=request.run_id,
        state_update=StateUpdateResult(claims=output.claims),
        usage=UsageView(model_calls=1, prompt_hashes=[]),
    )


def _parse_model_output(content: str) -> ModelStateUpdateOutput:
    try:
        raw = json.loads(content)
    except json.JSONDecodeError as exc:
        raise BrainContractError("model output is not valid JSON") from exc
    try:
        return ModelStateUpdateOutput.model_validate(raw)
    except Exception as exc:  # pydantic ValidationError -> typed brain failure
        raise BrainContractError(f"model output violates the STATE_UPDATE contract: {exc}") from exc
