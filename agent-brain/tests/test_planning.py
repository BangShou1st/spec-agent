"""Contract tests for the diagnostic planning-state.v1 schema."""

import json
from pathlib import Path

import pytest

from spec_agent_brain.contracts.planning import (
    PLANNING_STATE_VERSION,
    PlanningStateError,
    validate_planning_state,
)

FIXTURE = Path(__file__).resolve().parents[2] / "contracts" / "fixtures" / "planning-state-v1-valid.json"


def _flag(value, codes, refs):
    return {"value": value, "reasonCodes": codes, "evidenceRefs": refs}


def _valid_payload():
    return {
        "version": PLANNING_STATE_VERSION,
        "userInputRequired": _flag(True, ["NEED_MORE_INFO"], ["node:abc"]),
        "externalStepRequired": _flag(False, ["NO_EXTERNAL_NEED"], ["context:xyz"]),
        "directResponseSufficient": _flag(False, ["DIRECT_RESPONSE_NOT_SUFFICIENT"], ["answer:abc"]),
        "newDurableKnowledgePresent": _flag(False, ["NOTHING_DURABLE"], ["patch:abc"]),
    }


def test_golden_fixture_validates():
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
    state = validate_planning_state(payload)
    assert state.version == PLANNING_STATE_VERSION
    assert state.user_input_required.value is True


def test_valid_payload_accepted():
    state = validate_planning_state(_valid_payload())
    assert state.external_step_required.reason_codes == ["NO_EXTERNAL_NEED"]


def test_unknown_version_rejected():
    payload = _valid_payload()
    payload["version"] = "planning-state.v9"
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_missing_flag_rejected():
    payload = _valid_payload()
    del payload["directResponseSufficient"]
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_non_bool_value_rejected():
    payload = _valid_payload()
    payload["userInputRequired"]["value"] = ["yes"]
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_unknown_reason_code_rejected():
    payload = _valid_payload()
    payload["externalStepRequired"]["reasonCodes"] = ["MUST_CALL_TOOL_NOW"]
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_cross_flag_reason_code_rejected():
    payload = _valid_payload()
    payload["userInputRequired"]["reasonCodes"] = ["GOAL_SATISFIED"]
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_empty_reason_codes_rejected():
    payload = _valid_payload()
    payload["newDurableKnowledgePresent"]["reasonCodes"] = []
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_empty_evidence_refs_rejected():
    payload = _valid_payload()
    payload["userInputRequired"]["evidenceRefs"] = []
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_bad_evidence_ref_rejected():
    payload = _valid_payload()
    payload["userInputRequired"]["evidenceRefs"] = ["scenario:E10"]
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_expected_action_ref_rejected():
    payload = _valid_payload()
    payload["directResponseSufficient"]["evidenceRefs"] = ["expected:REQUEST_USER_INPUT"]
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_extra_field_rejected():
    payload = _valid_payload()
    payload["userInputRequired"]["rationale"] = "free-form reasoning"
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)


def test_duplicate_reason_codes_rejected():
    payload = _valid_payload()
    payload["externalStepRequired"]["reasonCodes"] = ["NO_EXTERNAL_NEED", "NO_EXTERNAL_NEED"]
    with pytest.raises(PlanningStateError):
        validate_planning_state(payload)
