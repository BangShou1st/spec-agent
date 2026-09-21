"""DECISION engine: reflection + planning in one model call, then a
runtime-stamped action proposal envelope.

The brain stamps base context identity from its own trusted request (never
from model output) and pre-checks source refs against the snapshot's allowed
refs; Java re-validates everything fail-closed anyway.
"""

import json
import logging
import uuid
from typing import Any

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


logger = logging.getLogger("spec_agent_brain")

# The one documented model deviation the parse layer normalizes: the model
# occasionally hoists this field out of ``action`` to the top level of its own
# JSON object, where the contract has no such field.
TOP_LEVEL_SOURCE_REFS = "sourceRefs"


class BrainContractError(RuntimeError):
    """Raised when a model output violates the brain's own output contract."""


class UngroundedReferenceError(BrainContractError):
    """The output cited a ref outside the frozen snapshot's allowed refs.

    A distinct type because the Runtime reports it separately: an
    out-of-range citation is the route/branch grounding gate doing its job,
    not a malformed model response, and collapsing the two into one opaque
    failure hides which side failed.
    """


class AmbiguousSourceRefsError(BrainContractError):
    """The output carried two *different* sourceRefs lists.

    The parse layer resolves only provably equivalent shapes. Two different
    lists have no defined merge semantics, so they are rejected instead of
    being silently overwritten or unioned.
    """


class ConflictSurfacingError(BrainContractError):
    """The output omitted observation.conflicts while unresolved conflicts exist.

    Kept distinct from the other contract failures because it is the one
    violation a second, explicitly-instructed call can actually repair: the
    unresolved claim stays in the snapshot, so without a repair every later
    decision on that route fails identically and the project becomes
    permanently unanswerable.
    """


class ActionIneligibleBrainError(BrainContractError):
    """The model selected a family outside the Runtime-owned V3 mask."""


CONFLICT_REPAIR_INSTRUCTION = (
    "上一次输出违反了输出契约：snapshot.effectiveClaims 中存在 kind=conflict 且 "
    "status=unresolved 的 claim，但 observation.conflicts 为空。未解决冲突如下：\n"
    "{conflicts}\n"
    "请重新输出完整的决策 JSON（保持 JSON 格式与全部必需字段），"
    "并在 observation.conflicts 中至少逐条列出上述冲突，"
    "同时按规则 9 让本周期的主动作直接推进冲突解决。只输出 JSON。"
)


def handle_decision(
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope,
        client: ModelClient) -> AgentV2ResponseEnvelope | AgentV3ResponseEnvelope:
    if request.decision_budget.max_model_calls < 1:
        raise BrainContractError("decision budget does not allow any model call")

    user_prompt = _render_model_input(request)
    messages = [
        ChatMessage(role="system", content=decision_prompt.SYSTEM_PROMPT),
        ChatMessage(role="user", content=user_prompt),
    ]
    completion = client.complete(
        run_id=str(request.run_id),
        call_type="DECISION",
        messages=messages,
    )
    model_calls = 1
    try:
        output = _validate_output(completion.content, request)
    except ConflictSurfacingError:
        # One bounded repair, and only when the Runtime-declared budget funds a
        # second call. Nothing else is retried: every other violation still
        # fails closed on the first output, exactly as before.
        if request.decision_budget.max_model_calls < 2:
            raise
        repair = CONFLICT_REPAIR_INSTRUCTION.format(
            conflicts="\n".join("- " + text for text in _unresolved_conflict_texts(request)))
        completion = client.complete(
            run_id=str(request.run_id),
            call_type="DECISION",
            messages=messages + [ChatMessage(role="user", content=repair)],
        )
        model_calls = 2
        output = _validate_output(completion.content, request)

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
        # Honest accounting: the repair call is reported, never hidden. Java
        # rejects the response when this exceeds the declared budget.
        usage=UsageView(model_calls=model_calls, prompt_hashes=[]),
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


def _validate_output(
        content: str,
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope) -> ModelDecisionOutput:
    output = _parse_model_output(content)
    _check_source_refs(output, request)
    _check_conflict_action(output, request)
    if isinstance(request, AgentV3RequestEnvelope):
        _check_eligibility_action(output, request)
    return output


def _unresolved_conflict_texts(
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope) -> list[str]:
    return [claim.text for claim in request.snapshot.effective_claims
            if claim.kind == "conflict" and claim.status == "unresolved"]


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
    raw = _normalize_stray_source_refs(raw)
    try:
        return ModelDecisionOutput.model_validate(raw)
    except Exception as exc:  # pydantic ValidationError -> typed brain failure
        raise BrainContractError(
            "model output violates the DECISION contract: "
            f"{exc} [{_output_layout(content)}]") from exc


def _normalize_stray_source_refs(raw: Any) -> Any:
    """Compatibility shim for exactly one documented DECISION deviation.

    The model sometimes emits ``sourceRefs`` as a *top-level* field of its JSON
    object instead of inside ``action``. Exactly two shapes are resolved, and
    both are provably equivalent to the model having used the documented layout:

    - the action *omits* the field and the top level carries a legal ref list:
      the value is relocated into ``action.sourceRefs``, the field's only
      defined home;
    - both places carry the *same* legal ref list: the top-level copy is a pure
      duplicate and is dropped.

    Which shape applies is decided by key *presence*, never by truthiness: an
    action that already defines the field is never treated as if it had omitted
    it, so a present-but-illegal value (``null``, ``false``, ``0``, ``""``, a
    non-string array) is never repaired by the top-level copy.

    Everything else fails closed exactly as before:

    - two *different* legal lists — including an empty action list against a
      non-empty top level — have no defined merge semantics (neither
      overwriting, nor unioning, nor preferring the longer one is acceptable) and
      are rejected;
    - an action field that is present but not a legal ref list is left untouched
      for the strict contract to reject;
    - any other unknown field is still rejected by the strict contract, and a
      relocated list is not trusted — it still runs through the full schema, the
      allowed-refs whitelist and every action/eligibility check downstream.

    No ref is ever invented, dropped or rewritten, and unknown keys are never
    stripped. If equivalence cannot be shown, the brain keeps rejecting.
    """
    if not isinstance(raw, dict) or TOP_LEVEL_SOURCE_REFS not in raw:
        return raw
    stray = raw[TOP_LEVEL_SOURCE_REFS]
    action = raw.get("action")
    if not _is_source_ref_list(stray) or not isinstance(action, dict):
        # Not the documented shape: leave it for the strict contract to reject.
        return raw
    if TOP_LEVEL_SOURCE_REFS in action:
        action_refs = action[TOP_LEVEL_SOURCE_REFS]
        if _is_source_ref_list(action_refs):
            if list(action_refs) == list(stray):
                raw.pop(TOP_LEVEL_SOURCE_REFS)
                logger.info(
                    "DECISION dropped duplicate top-level sourceRefs (%d refs)", len(stray))
                return raw
            raise AmbiguousSourceRefsError(
                "model output carries two different sourceRefs lists: "
                f"action={_bounded_refs(action_refs)} topLevel={_bounded_refs(stray)}")
        # The action defines the field with something that is not a ref list.
        # That is an illegal value, not a missing one: never patch it with the
        # top-level copy — the strict contract rejects it as it always did.
        return raw
    action[TOP_LEVEL_SOURCE_REFS] = list(stray)
    raw.pop(TOP_LEVEL_SOURCE_REFS)
    logger.info(
        "DECISION relocated top-level sourceRefs into action (%d refs)", len(stray))
    return raw


def _is_source_ref_list(value: Any) -> bool:
    """True only for a JSON array of strings — the declared ``List[str]`` shape."""
    return isinstance(value, list) and all(isinstance(item, str) for item in value)


def _output_layout(content: str) -> str:
    """Bounded layout diagnostic for a rejected DECISION output.

    Records only the key layout and the two sourceRefs lists — enough to prove
    how the model misplaced the field and to reproduce the rejection offline —
    never the rest of the model output, so user content does not leak into logs.
    """
    try:
        raw = json.loads(content)
    except json.JSONDecodeError:
        return "layout=<not json>"
    if not isinstance(raw, dict):
        return f"layout=<{type(raw).__name__}>"
    action = raw.get("action")
    action_refs = action.get(TOP_LEVEL_SOURCE_REFS) if isinstance(action, dict) else None
    return ("layout=keys(" + ",".join(sorted(str(key) for key in raw.keys())) + ")"
            + " topLevelSourceRefs=" + _bounded_refs(raw.get(TOP_LEVEL_SOURCE_REFS))
            + " actionSourceRefs=" + _bounded_refs(action_refs))


# Diagnostics render model-emitted refs, which are model output: bound both the
# number of entries and the characters, so a malformed output cannot stream an
# unbounded payload into the log line or the typed error detail.
REF_ENTRY_MAX_CHARS = 120
REF_RENDER_MAX_CHARS = 400


def _bounded_refs(value: Any) -> str:
    """Bounded rendering of a refs value: at most five entries, capped length."""
    if value is None:
        return "<absent>"
    if not isinstance(value, list):
        return f"<{type(value).__name__}>"
    head = json.dumps(
        [_truncate(str(item), REF_ENTRY_MAX_CHARS) for item in value[:5]],
        ensure_ascii=False)
    if len(value) > 5:
        head = f"{head}+{len(value) - 5}"
    return _truncate(head, REF_RENDER_MAX_CHARS)


def _truncate(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[:limit] + "..."


def _check_source_refs(output: ModelDecisionOutput, request: AgentV2RequestEnvelope) -> None:
    allowed = set(request.snapshot.allowed_source_refs)
    for ref in output.action.source_refs:
        if ref not in allowed:
            raise UngroundedReferenceError(
                f"model referenced a source outside the allowed snapshot refs: {ref}")


def _check_eligibility_action(output: ModelDecisionOutput,
                              request: AgentV3RequestEnvelope) -> None:
    if output.action.action_family not in request.action_eligibility.eligible_families:
        raise ActionIneligibleBrainError(
            "model selected an action outside the Runtime eligibility mask")


def _check_conflict_action(output: ModelDecisionOutput,
                           request: AgentV2RequestEnvelope) -> None:
    """Fail closed when unresolved requirement conflicts would go unsurfaced.

    NODE_QUERY is a read-only contextual conversation and must stay usable even
    when the workspace has unresolved conflicts. For normal planning cycles the
    conflict must still be surfaced faithfully in observation.conflicts — but
    the ACTION choice is never restricted here (Slice 4): a read-only
    capability invocation is as acceptable as asking the user. Execution
    safety belongs to the Java Runtime policy/stale/permission gates, never
    to this contract check.
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
        raise ConflictSurfacingError(
            "unresolved conflict requires a non-empty observation.conflicts")
