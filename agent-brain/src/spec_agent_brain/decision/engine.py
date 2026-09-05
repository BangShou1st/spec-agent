"""DECISION engine: reflection + planning in one model call, then a
runtime-stamped action proposal envelope.

The brain stamps base context identity from its own trusted request (never
from model output) and pre-checks source refs against the snapshot's allowed
refs; Java re-validates everything fail-closed anyway.
"""

import json
import uuid

from ..diagnostics import semantic_diagnostics
from ..contracts.decisions import (
    ActionProposal,
    AgentV2ResponseEnvelope,
    AgentV3ResponseEnvelope,
    ModelDecisionOutput,
    ObservationView,
    UsageView,
)
from ..contracts.inputs import AgentV2RequestEnvelope, AgentV3RequestEnvelope
from ..contracts.protocol import DECISION_PROTOCOL_VERSION, DECISION_PROTOCOL_VERSION_V3
from ..model_client import ChatMessage, ModelClient
from ..prompts import decision as decision_prompt


class BrainContractError(RuntimeError):
    """Raised when a model output violates the brain's own output contract."""


class ActionIneligibleBrainError(BrainContractError):
    """The model selected a family outside the Runtime-owned V3 mask."""


def handle_decision(
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope,
        client: ModelClient) -> AgentV2ResponseEnvelope | AgentV3ResponseEnvelope:
    if request.decision_budget.max_model_calls < 1:
        raise BrainContractError("decision budget does not allow any model call")

    user_prompt = _render_model_input(request)
    completion = client.complete(
        run_id=str(request.run_id),
        call_type="DECISION",
        messages=[
            ChatMessage(role="system", content=decision_prompt.SYSTEM_PROMPT),
            ChatMessage(role="user", content=user_prompt),
        ],
    )
    output = _parse_model_output(completion.content)
    _check_source_refs(output, request)
    _check_conflict_action(output, request)

    if isinstance(request, AgentV3RequestEnvelope):
        _check_eligibility_action(output, request)

    response_type = (AgentV3ResponseEnvelope
                     if isinstance(request, AgentV3RequestEnvelope)
                     else AgentV2ResponseEnvelope)
    response_version = (DECISION_PROTOCOL_VERSION_V3
                        if isinstance(request, AgentV3RequestEnvelope)
                        else DECISION_PROTOCOL_VERSION)

    response_values = dict(
        protocol_version=response_version,
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
        diagnostics=semantic_diagnostics(
            decision_prompt.SYSTEM_PROMPT, user_prompt, "DECISION"),
    )
    if isinstance(request, AgentV3RequestEnvelope):
        response_values.update(
            selected_eligibility_version=request.action_eligibility.version,
            selected_eligibility_basis_hash=request.action_eligibility.basis_hash,
            eligibility_evidence_refs=output.action.source_refs,
        )
    return response_type(**response_values)


def _render_model_input(
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope) -> str:
    """Adds V3 Runtime control data without modifying Candidate C's prompt."""
    rendered = decision_prompt.render_user_prompt(request)
    if not isinstance(request, AgentV3RequestEnvelope):
        return rendered
    payload = json.loads(rendered)
    payload["actionEligibility"] = request.action_eligibility.model_dump(
        mode="json", by_alias=True)
    return json.dumps(payload, ensure_ascii=False)


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


def _check_eligibility_action(output: ModelDecisionOutput,
                              request: AgentV3RequestEnvelope) -> None:
    if output.action.action_family not in request.action_eligibility.eligible_families:
        raise ActionIneligibleBrainError(
            "model selected an action outside the Runtime eligibility mask")


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
