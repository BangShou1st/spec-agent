"""planning-mapping.v2 tests — TDD Unit 4.

Input states are C1/C2-clean by construction (harness enforces).
Mapping never repairs: illegal combos raise instead of collapsing to
PLANNING_AMBIGUOUS (removed outcome). Pre-eligibility winner is reported
for G8 accounting.
"""
import pytest

from semantic_planning.mapping_v2 import MAPPING_VERSION, derive_outcome

ALL = ["REQUEST_USER_INPUT", "INVOKE_CAPABILITY", "RESPOND_TO_USER",
       "CREATE_NODE"]


def quiet(goal="RESOLVE_USER_CHOICE"):
    return {
        "version": "planning-state.v2", "goalType": goal,
        "userInputRequired": {
            "value": False, "gapType": None,
            "reasonCodes": ["USER_INPUT_SUFFICIENT"],
            "evidenceRefs": ["event:kind"]},
        "externalStepRequired": {
            "value": False, "capabilityAssessment": None,
            "reasonCodes": ["NO_EXTERNAL_NEED"],
            "evidenceRefs": ["event:kind"]},
        "directResponseSufficient": {
            "value": False, "reasonCodes": ["NO_GROUNDED_CONTENT"],
            "evidenceRefs": ["event:kind"]},
        "newDurableKnowledgePresent": {
            "value": False, "reasonCodes": ["NOTHING_DURABLE"],
            "evidenceRefs": ["event:kind"]},
    }


def test_request():
    s = quiet()
    s["userInputRequired"] = {
        "value": True, "gapType": "intent_gap",
        "reasonCodes": ["INTENT_GAP"], "evidenceRefs": ["event:kind"]}
    out, info = derive_outcome(s, ALL)
    assert out == "REQUEST_USER_INPUT"
    assert info["raw_winner"] == "REQUEST_USER_INPUT"
    assert info["eligible_ok"] is True


def test_invoke_requires_required_now_shape():
    s = quiet("GATHER_EXTERNAL_EVIDENCE")
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "c1", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["event:kind"]}
    out, _ = derive_outcome(s, ALL)
    assert out == "INVOKE_CAPABILITY"


def test_respond():
    s = quiet("PRODUCE_DIRECT_RESPONSE")
    s["directResponseSufficient"] = {
        "value": True, "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
        "evidenceRefs": ["event:kind"]}
    out, _ = derive_outcome(s, ALL)
    assert out == "RESPOND_TO_USER"


def test_create_only_when_primaries_quiet():
    s = quiet()
    s["newDurableKnowledgePresent"] = {
        "value": True, "reasonCodes": ["NOVEL_SEMANTIC_UNIT"],
        "evidenceRefs": ["event:kind"]}
    out, _ = derive_outcome(s, ALL)
    assert out == "CREATE_NODE"


def test_e_n_resolves_invoke():
    s = quiet("GATHER_EXTERNAL_EVIDENCE")
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "c1", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["event:kind"]}
    s["newDurableKnowledgePresent"] = {
        "value": True, "reasonCodes": ["NOVEL_SEMANTIC_UNIT"],
        "evidenceRefs": ["event:kind"]}
    out, _ = derive_outcome(s, ALL)
    assert out == "INVOKE_CAPABILITY"


def test_d_n_resolves_respond():
    s = quiet("PRODUCE_DIRECT_RESPONSE")
    s["directResponseSufficient"] = {
        "value": True, "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
        "evidenceRefs": ["event:kind"]}
    s["newDurableKnowledgePresent"] = {
        "value": True, "reasonCodes": ["NOVEL_SEMANTIC_UNIT"],
        "evidenceRefs": ["event:kind"]}
    out, _ = derive_outcome(s, ALL)
    assert out == "RESPOND_TO_USER"


def test_all_false_no_winner():
    out, info = derive_outcome(quiet(), ALL)
    assert out == "NO_WINNER"
    assert info["raw_winner"] is None


def test_wait_structural():
    out, info = derive_outcome(quiet("WAIT_FOR_RUNTIME_DEPENDENCY"), ALL)
    assert out == "NO_WINNER_STRUCTURAL"
    assert info["structural"] is True


def test_winner_outside_eligible_flagged_for_g8():
    s = quiet()
    s["newDurableKnowledgePresent"] = {
        "value": True, "reasonCodes": ["NOVEL_SEMANTIC_UNIT"],
        "evidenceRefs": ["event:kind"]}
    out, info = derive_outcome(s, ["REQUEST_USER_INPUT",
                                   "RESPOND_TO_USER"])
    assert out == "NO_WINNER"
    assert info["raw_winner"] == "CREATE_NODE"
    assert info["eligible_ok"] is False


def test_illegal_combo_raises_no_ambiguous():
    s = quiet()
    s["userInputRequired"] = {
        "value": True, "gapType": "intent_gap",
        "reasonCodes": ["INTENT_GAP"], "evidenceRefs": ["event:kind"]}
    s["directResponseSufficient"] = {
        "value": True, "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
        "evidenceRefs": ["event:kind"]}
    with pytest.raises(ValueError):
        derive_outcome(s, ALL)


def test_e_without_required_now_raises():
    s = quiet()
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "c1", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "PREFERRED"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["event:kind"]}
    with pytest.raises(ValueError):
        derive_outcome(s, ALL)


def test_mapping_version():
    assert MAPPING_VERSION == "planning-mapping.v2"
