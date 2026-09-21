"""Parse-layer compatibility for the one documented DECISION field deviation.

The model occasionally hoists ``sourceRefs`` out of ``action`` to the top level
of its JSON object. These tests pin the exact compatibility boundary:

- a lone *legal* top-level list is relocated, but only when ``action`` omits the
  field entirely (key presence, not truthiness, decides "omitted");
- two identical legal lists collapse to one;
- two different legal lists — including an empty action list against a non-empty
  top level — are rejected, never overwritten, merged or unioned;
- an ``action.sourceRefs`` that is present but illegal (``null``, ``false``,
  ``0``, ``""``, a non-string array, an object) is rejected and is *never*
  repaired by the top-level copy;
- anything unknown still fails closed, and a relocated list is validated exactly
  like a well-placed one;
- every diagnostic stays bounded: no unbounded model output reaches the log or
  the typed error detail.
"""

import json
from pathlib import Path

import pytest

from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.decision import (
    AmbiguousSourceRefsError,
    BrainContractError,
    UngroundedReferenceError,
    handle_decision,
)
from spec_agent_brain.model_client import Completion

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"

ALLOWED_REF = "answer:99999999-9999-9999-9999-999999999999"
OTHER_ALLOWED_REF = "node:33333333-3333-3333-3333-333333333333"
FOREIGN_REF = "answer:11111111-2222-3333-4444-555555555555"

UNSET = "unset"


class ScriptedClient:
    """Returns one canned completion; the brain owns all parsing."""

    def __init__(self, content: str):
        self._content = content

    def complete(self, run_id, call_type, messages, max_output_tokens=2048) -> Completion:
        return Completion(content=self._content, finish_reason="stop")


def _request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))
    return parse_request_envelope(payload)


def _v3_request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-v3-valid.json").read_text(encoding="utf-8"))
    return parse_request_envelope(payload)


def _decision_output(action_refs=UNSET, top_level_refs=UNSET, **extra) -> str:
    """Builds a raw model output; ``unset`` means 'key absent'.

    ``action_refs=None`` therefore emits an explicit ``"sourceRefs": null``,
    which is a *present but illegal* value — distinct from an absent key.
    """
    action = {
        "actionFamily": "REQUEST_USER_INPUT",
        "payload": {
            "questionText": "最重要的成果是什么？",
            "purpose": "澄清主要目标。",
            "options": [{"label": "明确主要目标"}],
            "allowFreeAnswer": True,
        },
    }
    if action_refs is not UNSET:
        action["sourceRefs"] = action_refs
    raw = {
        "observation": {
            "known": ["用户澄清了主要成果。"],
            "unknowns": [],
            "conflicts": [],
            "risks": [],
        },
        "action": action,
    }
    if top_level_refs is not UNSET:
        raw["sourceRefs"] = top_level_refs
    raw.update(extra)
    return json.dumps(raw, ensure_ascii=False)


# --- shapes that are resolved ------------------------------------------------


def test_top_level_source_refs_duplicating_the_action_refs_is_dropped():
    content = _decision_output(action_refs=[ALLOWED_REF], top_level_refs=[ALLOWED_REF])

    response = handle_decision(_request(), ScriptedClient(content))

    assert response.action_proposal.source_refs == [ALLOWED_REF]


def test_identical_duplicate_is_dropped_even_with_a_longer_list():
    refs = [ALLOWED_REF, OTHER_ALLOWED_REF]
    content = _decision_output(action_refs=refs, top_level_refs=list(refs))

    response = handle_decision(_request(), ScriptedClient(content))

    assert response.action_proposal.source_refs == refs


def test_identical_empty_lists_drop_the_top_level_duplicate():
    content = _decision_output(action_refs=[], top_level_refs=[])

    response = handle_decision(_request(), ScriptedClient(content))

    assert response.action_proposal.source_refs == []


def test_lone_top_level_source_refs_is_relocated_into_the_action():
    content = _decision_output(top_level_refs=[ALLOWED_REF])

    response = handle_decision(_request(), ScriptedClient(content))

    # The relocation is effective: the refs are the action's own, and the
    # envelope carries them exactly once.
    assert response.action_proposal.source_refs == [ALLOWED_REF]


def test_relocated_refs_still_pass_the_v3_eligibility_evidence_check():
    request = _v3_request()
    allowed = request.snapshot.allowed_source_refs[0]
    content = _decision_output(top_level_refs=[allowed])

    response = handle_decision(request, ScriptedClient(content))

    assert response.action_proposal.source_refs == [allowed]
    assert response.eligibility_evidence_refs == [allowed]


def test_relocated_refs_outside_allowed_refs_are_still_rejected():
    content = _decision_output(top_level_refs=[FOREIGN_REF])

    with pytest.raises(UngroundedReferenceError):
        handle_decision(_request(), ScriptedClient(content))


# --- shapes that are rejected ------------------------------------------------


def test_two_different_source_refs_lists_are_rejected_and_never_merged():
    content = _decision_output(action_refs=[ALLOWED_REF], top_level_refs=[OTHER_ALLOWED_REF])

    with pytest.raises(AmbiguousSourceRefsError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    message = str(raised.value)
    # Both lists are reported so the conflict is diagnosable, and neither the
    # union nor an overwrite is ever produced.
    assert ALLOWED_REF in message
    assert OTHER_ALLOWED_REF in message


def test_conflicting_lists_are_rejected_even_when_both_are_allowed():
    content = _decision_output(
        action_refs=[ALLOWED_REF, OTHER_ALLOWED_REF], top_level_refs=[ALLOWED_REF])

    with pytest.raises(AmbiguousSourceRefsError):
        handle_decision(_request(), ScriptedClient(content))


def test_empty_action_refs_against_non_empty_top_level_refs_is_rejected():
    # A present-but-empty action list is *not* an omitted field: the top-level
    # list must never overwrite it, so the output is rejected rather than
    # silently gaining refs the action did not carry.
    content = _decision_output(action_refs=[], top_level_refs=[ALLOWED_REF])

    with pytest.raises(AmbiguousSourceRefsError):
        handle_decision(_request(), ScriptedClient(content))


def test_non_empty_action_refs_against_empty_top_level_refs_is_rejected():
    content = _decision_output(action_refs=[ALLOWED_REF], top_level_refs=[])

    with pytest.raises(AmbiguousSourceRefsError):
        handle_decision(_request(), ScriptedClient(content))


@pytest.mark.parametrize(
    "illegal",
    [None, False, 0, "", {}, {"ref": ALLOWED_REF}],
    ids=["null", "false", "zero", "empty-string", "object", "ref-object"],
)
def test_present_but_illegal_action_refs_is_rejected_and_never_patched(illegal):
    # The field exists, so the action is not "omitted"; a falsy or
    # wrongly-typed value must not be treated as absent and back-filled from
    # the top level. It still fails closed, as a plain contract violation.
    content = _decision_output(action_refs=illegal, top_level_refs=[ALLOWED_REF])

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    assert not isinstance(raised.value, AmbiguousSourceRefsError)
    assert "sourceRefs" in str(raised.value)


@pytest.mark.parametrize(
    "illegal_list",
    [[1, 2], [ALLOWED_REF, 3], [None], [[]]],
    ids=["numbers", "mixed", "nulls", "nested"],
)
def test_action_refs_with_non_string_entries_is_rejected_and_never_patched(illegal_list):
    content = _decision_output(action_refs=illegal_list, top_level_refs=[ALLOWED_REF])

    with pytest.raises(BrainContractError):
        handle_decision(_request(), ScriptedClient(content))


def test_action_refs_with_non_string_entries_is_rejected_even_when_identical():
    # Identical byte-for-byte, but neither side is a legal ref list: the shim
    # resolves only legal shapes, so this still fails closed instead of being
    # accepted as a "duplicate".
    illegal = [1, 2]
    content = _decision_output(action_refs=illegal, top_level_refs=list(illegal))

    with pytest.raises(BrainContractError):
        handle_decision(_request(), ScriptedClient(content))


def test_unknown_top_level_field_is_still_rejected():
    content = _decision_output(action_refs=[ALLOWED_REF], mysteryField=True)

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    assert "mysteryField" in str(raised.value)


def test_non_list_top_level_source_refs_is_still_rejected():
    content = _decision_output(top_level_refs=ALLOWED_REF)

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    assert "sourceRefs" in str(raised.value)


def test_ungrounded_action_ref_is_typed_separately_from_a_malformed_output():
    content = _decision_output(action_refs=[FOREIGN_REF])

    with pytest.raises(UngroundedReferenceError):
        handle_decision(_request(), ScriptedClient(content))


# --- bounded diagnostics -----------------------------------------------------


def test_contract_failure_diagnostic_reports_layout_without_model_content():
    # A malformed *action* (unknown family) that also carries a stray top-level
    # list: the failure must never be repaired by this shim, and the diagnostic
    # must describe the layout only.
    content = json.dumps({
        "observation": {
            "known": ["敏感内容不应进入日志"],
            "unknowns": [], "conflicts": [], "risks": [],
        },
        "action": {"actionFamily": "NOT_A_FAMILY", "payload": {}},
        "sourceRefs": [ALLOWED_REF],
    }, ensure_ascii=False)

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    message = str(raised.value)
    assert "layout=keys(" in message
    assert "topLevelSourceRefs=" in message
    assert "actionSourceRefs=" in message
    # Bounded diagnostics: the model's own prose never leaks into the error.
    assert "敏感内容不应进入日志" not in message


def test_ambiguous_source_refs_diagnostic_is_length_bounded():
    # Refs are model output too: an oversized entry must be truncated rather
    # than streamed whole into the log line or the typed error detail.
    oversized = "r" * 8000
    content = _decision_output(
        action_refs=[oversized], top_level_refs=["s" * 8000])

    with pytest.raises(AmbiguousSourceRefsError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    message = str(raised.value)
    assert len(message) < 2000
    assert oversized not in message
    assert "..." in message
