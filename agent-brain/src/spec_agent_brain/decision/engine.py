"""DECISION engine: reflection + planning in one model call, then a
runtime-stamped action proposal envelope.

The brain stamps base context identity from its own trusted request (never
from model output) and pre-checks source refs against the snapshot's allowed
refs; Java re-validates everything fail-closed anyway.
"""

import json
import uuid

from ..contracts.decisions import (
    ActionProposal,
    AgentV2ResponseEnvelope,
    ModelDecisionOutput,
    ObservationView,
    UsageView,
)
from ..contracts.inputs import AgentV2RequestEnvelope
from ..contracts.protocol import DECISION_PROTOCOL_VERSION
from ..model_client import ChatMessage, ModelClient
from ..prompts import decision as decision_prompt


class BrainContractError(RuntimeError):
    """Raised when a model output violates the brain's own output contract."""


def handle_decision(request: AgentV2RequestEnvelope, client: ModelClient) -> AgentV2ResponseEnvelope:
    if request.decision_budget.max_model_calls < 1:
        raise BrainContractError("decision budget does not allow any model call")

    completion = client.complete(
        run_id=str(request.run_id),
        call_type="DECISION",
        messages=[
            ChatMessage(role="system", content=decision_prompt.SYSTEM_PROMPT),
            ChatMessage(role="user", content=decision_prompt.render_user_prompt(request)),
        ],
    )
    output = _parse_model_output(completion.content)
    _check_source_refs(output, request)
    _check_conflict_action(output, request)

    return AgentV2ResponseEnvelope(
        protocol_version=DECISION_PROTOCOL_VERSION,
        run_id=request.run_id,
        observation=output.observation,
        action_proposal=ActionProposal(
            action_family=output.action.action_family,
            payload=output.action.payload,
            # Runtime-owned identity is stamped from the trusted request, so
            # the model can never fabricate or stale-stamp the base context.
            base_context_snapshot_id=request.snapshot.snapshot_id,
            base_context_hash=request.snapshot.context_hash,
            source_refs=output.action.source_refs,
            # proposalId is runtime-owned UUID; idempotencyKey derived from
            # the trusted run identity (one proposal per decision cycle).
            proposal_id=uuid.uuid4(),
            idempotency_key=str(request.run_id),
            anchor_refs=output.action.anchor_refs,
        ),
        usage=UsageView(model_calls=1, prompt_hashes=[]),
    )


def _parse_model_output(content: str) -> ModelDecisionOutput:
    try:
        raw = json.loads(content)
    except json.JSONDecodeError as exc:
        raise BrainContractError("model output is not valid JSON") from exc
    try:
        return ModelDecisionOutput.model_validate(raw)
    except Exception as exc:  # pydantic ValidationError -> typed brain failure
        raise BrainContractError(f"model output violates the DECISION contract: {exc}") from exc


def _check_source_refs(output: ModelDecisionOutput, request: AgentV2RequestEnvelope) -> None:
    allowed = set(request.snapshot.allowed_source_refs)
    for ref in output.action.source_refs:
        if ref not in allowed:
            raise BrainContractError(
                f"model referenced a source outside the allowed snapshot refs: {ref}")


def _check_conflict_action(output: ModelDecisionOutput,
                           request: AgentV2RequestEnvelope) -> None:
    """Fail closed when unresolved requirement conflicts would be bypassed.

    NODE_QUERY is a read-only contextual conversation and must stay usable even
    when the workspace has unresolved conflicts. For normal planning cycles,
    however, an unresolved conflict is a control-flow boundary: the model must
    surface it and either ask the user to resolve the trade-off or record an
    explicit DECISION node. The latter remains subject to Java policy/Advisor
    confirmation; this guard only prevents silent continuation/WAIT.
    """
    if request.event.kind == "NODE_QUERY":
        return

    unresolved = [
        claim for claim in request.snapshot.effective_claims
        if claim.kind == "conflict" and claim.status == "unresolved"
    ]
    if not unresolved:
        return

    if not output.observation.conflicts:
        raise BrainContractError(
            "unresolved conflict requires a non-empty observation.conflicts")

    family = output.action.action_family
    if family == "REQUEST_USER_INPUT":
        return

    if family == "CREATE_NODE":
        payload = output.action.payload
        if (payload.get("kind") == "KNOWLEDGE"
                and payload.get("subtype") == "DECISION"
                and isinstance(payload.get("content"), dict)
                and isinstance(payload["content"].get("text"), str)
                and payload["content"]["text"].strip()):
            return

    raise BrainContractError(
        "unresolved conflict requires REQUEST_USER_INPUT or CREATE_NODE/DECISION")
