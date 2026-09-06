"""R4 C1 (raw contract) validator tests — TDD Unit 1.

Covers Phase 7 Schema/C1 categories against the corrected spec
(Architecture Sections 10-11): canonical refs, gapType nullability,
reason polarity, observation: forbidden, assessment nullability.
"""
import copy

import pytest

from semantic_planning.planning_v2 import (
    EVIDENCE_PREFIXES,
    GOAL_TYPES,
    validate_c1,
)

SID = "11111111-1111-1111-1111-111111111111"
NID = "22222222-2222-2222-2222-222222222222"
AID = "33333333-3333-3333-3333-333333333333"
PID = "44444444-4444-4444-4444-444444444444"
RID = "55555555-5555-5555-5555-555555555555"


def make_input(**over):
    base = {
        "eligibleFamilies": ["REQUEST_USER_INPUT", "RESPOND_TO_USER"],
        "event": {"kind": "ANSWER_SUBMITTED", "anchorNodeId": NID,
                  "freeText": "[CAL:t1-answer]"},
        "observation": {},
        "snapshot": {
            "snapshotId": SID,
            "routeId": RID,
            "routeContext": {"routeId": RID},
            "lineage": [{
                "node": {"id": NID},
                "answer": {"id": AID},
                "patches": [{"id": PID, "claims": [
                    {"kind": "open_question", "text": "[CAL:t1-claim]",
                     "status": "unresolved", "confidence": 0.5}]}]},
            ],
            "effectiveClaims": [
                {"kind": "open_question", "text": "[CAL:t1-claim]",
                 "status": "unresolved", "confidence": 0.5}],
            "availableCapabilities": [
                {"id": "cal.read-only", "readOnly": True,
                 "sideEffectClass": "NONE"}],
            "capabilityResults": [],
            "autonomy": {"mode": "ADVISOR"},
        },
    }
    base.update(over)
    return base


def make_state(**over):
    base = {
        "version": "planning-state.v2",
        "goalType": "RESOLVE_USER_CHOICE",
        "userInputRequired": {
            "value": True, "gapType": "intent_gap",
            "reasonCodes": ["INTENT_GAP"],
            "evidenceRefs": ["claim:effective/0"]},
        "externalStepRequired": {
            "value": False, "capabilityAssessment": None,
            "reasonCodes": ["SAFER_PATH_AVAILABLE"],
            "evidenceRefs": ["capability:cal.read-only"]},
        "directResponseSufficient": {
            "value": False,
            "reasonCodes": ["AWAITING_USER_INPUT"],
            "evidenceRefs": ["claim:effective/0"]},
        "newDurableKnowledgePresent": {
            "value": False,
            "reasonCodes": ["NOT_STANDALONE"],
            "evidenceRefs": ["patch:" + PID]},
    }
    base.update(over)
    return copy.deepcopy(base)


def test_valid_minimal_state_passes():
    state, err = validate_c1(make_state(), make_input())
    assert err is None
    assert state["goalType"] == "RESOLVE_USER_CHOICE"


def test_wrong_version_rejected():
    _, err = validate_c1(make_state(version="planning-state.v1"),
                         make_input())
    assert err is not None and err.startswith("C1:")


def test_unknown_top_field_rejected():
    s = make_state()
    s["extra"] = 1
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_missing_flag_rejected():
    s = make_state()
    del s["directResponseSufficient"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_bad_goal_enum_rejected():
    _, err = validate_c1(make_state(goalType="EXECUTE_AUTHORIZED_ACTION"),
                         make_input())
    assert err is not None and err.startswith("C1:")


def test_non_bool_value_rejected():
    s = make_state()
    s["userInputRequired"]["value"] = 1
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_duplicate_reason_rejected():
    s = make_state()
    s["directResponseSufficient"]["reasonCodes"] = [
        "AWAITING_USER_INPUT", "AWAITING_USER_INPUT"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_unknown_reason_rejected():
    s = make_state()
    s["directResponseSufficient"]["reasonCodes"] = ["GOAL_SATISFIED"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_true_code_on_false_flag_rejected():
    s = make_state()
    s["directResponseSufficient"]["reasonCodes"] = [
        "GROUNDED_RESPONSE_AVAILABLE"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_false_code_on_true_flag_rejected():
    s = make_state()
    s["userInputRequired"]["reasonCodes"] = ["USER_INPUT_SUFFICIENT"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_unresolving_claim_ref_rejected():
    s = make_state()
    s["userInputRequired"]["evidenceRefs"] = ["claim:effective/7"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_observation_prefix_forbidden():
    assert "observation:" not in EVIDENCE_PREFIXES
    s = make_state()
    s["userInputRequired"]["evidenceRefs"] = ["observation:authorization"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_free_text_evidence_rejected():
    s = make_state()
    s["userInputRequired"]["evidenceRefs"] = ["the answer text"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_empty_evidence_rejected():
    s = make_state()
    s["userInputRequired"]["evidenceRefs"] = []
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_gaptype_null_when_false():
    s = make_state()
    s["userInputRequired"] = {
        "value": False, "gapType": "intent_gap",
        "reasonCodes": ["USER_INPUT_SUFFICIENT"],
        "evidenceRefs": ["claim:effective/0"]}
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_gaptype_required_when_true():
    s = make_state()
    s["userInputRequired"]["gapType"] = None
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_gaptype_none_string_rejected():
    s = make_state()
    s["userInputRequired"] = {
        "value": False, "gapType": "none",
        "reasonCodes": ["USER_INPUT_SUFFICIENT"],
        "evidenceRefs": ["claim:effective/0"]}
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_e_true_assessment_missing_rejected():
    s = make_state()
    s["externalStepRequired"] = {
        "value": True, "capabilityAssessment": None,
        "reasonCodes": ["EXTERNAL_ACTION_REQUIRED"],
        "evidenceRefs": ["capability:cal.read-only"]}
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_e_false_assessment_present_rejected():
    s = make_state()
    s["externalStepRequired"]["capabilityAssessment"] = {
        "capabilityId": "cal.read-only", "riskLevel": "READ_ONLY",
        "argumentCompleteness": "NOT_REQUIRED",
        "authorizationStatus": "NOT_REQUIRED",
        "executionNecessity": "REQUIRED_NOW"}
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_e_true_full_assessment_passes_c1():
    s = make_state()
    s["goalType"] = "UNDERSTAND_USER_INTENT"
    s["userInputRequired"] = {
        "value": False, "gapType": None,
        "reasonCodes": ["USER_INPUT_SUFFICIENT"],
        "evidenceRefs": ["claim:effective/0"]}
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.read-only", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["capability:cal.read-only"]}
    s["directResponseSufficient"]["evidenceRefs"] = [
        "capability:cal.read-only"]
    state, err = validate_c1(s, make_input())
    assert err is None
    assert state["externalStepRequired"]["value"] is True


def test_event_ref_resolves():
    s = make_state()
    s["directResponseSufficient"]["evidenceRefs"] = ["event:kind"]
    _, err = validate_c1(s, make_input())
    assert err is None


def test_event_ref_unknown_field_rejected():
    s = make_state()
    s["directResponseSufficient"]["evidenceRefs"] = ["event:authorization"]
    _, err = validate_c1(s, make_input())
    assert err is not None and err.startswith("C1:")


def test_non_dict_payload_rejected():
    _, err = validate_c1(["not", "a", "dict"], make_input())
    assert err is not None and err.startswith("C1:")


def test_goal_types_exact():
    assert set(GOAL_TYPES) == {
        "UNDERSTAND_USER_INTENT", "RESOLVE_USER_CHOICE",
        "PRODUCE_DIRECT_RESPONSE", "GATHER_EXTERNAL_EVIDENCE",
        "WAIT_FOR_RUNTIME_DEPENDENCY"}


def test_eight_prefixes_no_observation():
    assert set(EVIDENCE_PREFIXES) == {
        "node:", "answer:", "patch:", "context:", "route:", "claim:",
        "capability:", "event:"}
