"""R4 C2 deterministic cross-check tests — TDD Unit 3.

Covers Architecture Section 11 checks 1-8. C2 never judges novelty or
whether cited claims truly ground arguments; it rejects only
machine-verifiable contradictions.
"""
import copy

from semantic_planning.validation_v2 import check_c2, project_risk

SID = "11111111-1111-1111-1111-111111111111"
NID = "22222222-2222-2222-2222-222222222222"
AID = "33333333-3333-3333-3333-333333333333"
PID = "44444444-4444-4444-4444-444444444444"
RID = "55555555-5555-5555-5555-555555555555"

RO_CAP = {"id": "cal.read-only", "readOnly": True, "sideEffectClass": "NONE"}
IRR_CAP = {"id": "cal.irreversible", "readOnly": False,
           "sideEffectClass": "EXTERNAL_IRREVERSIBLE"}
LOC_CAP = {"id": "cal.local", "readOnly": False,
           "sideEffectClass": "LOCAL_DURABLE"}


def make_input(claims=(), patches=(), caps=(RO_CAP,), results=(),
               autonomy="ADVISOR"):
    eff = list(claims)
    lineage = [{"node": {"id": NID}, "answer": {"id": AID},
                "patches": list(patches)}]
    return {
        "eligibleFamilies": ["REQUEST_USER_INPUT", "INVOKE_CAPABILITY",
                             "RESPOND_TO_USER", "CREATE_NODE"],
        "event": {"kind": "ANSWER_SUBMITTED", "anchorNodeId": NID},
        "observation": {},
        "snapshot": {
            "snapshotId": SID, "routeId": RID,
            "routeContext": {"routeId": RID},
            "lineage": lineage,
            "effectiveClaims": eff,
            "availableCapabilities": list(caps),
            "capabilityResults": list(results),
            "autonomy": {"mode": autonomy},
        },
    }


def unres(conf=0.5):
    return {"status": "unresolved", "confidence": conf, "text": "[CAL:u]"}


def conf(conf=0.9):
    return {"status": "confirmed", "confidence": conf, "text": "[CAL:c]"}


def patch(*claims):
    return {"id": PID, "claims": list(claims)}


def u_block(value, gap="intent_gap", codes=("INTENT_GAP",), refs=None):
    if value:
        return {"value": True, "gapType": gap, "reasonCodes": list(codes),
                "evidenceRefs": refs or ["claim:effective/0"]}
    return {"value": False, "gapType": None,
            "reasonCodes": ["USER_INPUT_SUFFICIENT"],
            "evidenceRefs": refs or ["claim:effective/0"]}


def quiet_state(goal="RESOLVE_USER_CHOICE"):
    return {
        "version": "planning-state.v2", "goalType": goal,
        "userInputRequired": u_block(True),
        "externalStepRequired": {
            "value": False, "capabilityAssessment": None,
            "reasonCodes": ["SAFER_PATH_AVAILABLE"],
            "evidenceRefs": ["capability:cal.read-only"]},
        "directResponseSufficient": {
            "value": False, "reasonCodes": ["AWAITING_USER_INPUT"],
            "evidenceRefs": ["claim:effective/0"]},
        "newDurableKnowledgePresent": {
            "value": False, "reasonCodes": ["NOT_STANDALONE"],
            "evidenceRefs": ["patch:" + PID]},
    }


def test_clean_state_no_violations():
    mi = make_input(claims=[unres()], patches=[patch(unres())])
    assert check_c2(quiet_state("RESOLVE_USER_CHOICE"), mi) == []


def test_goal_mismatch():
    mi = make_input(claims=[unres()], patches=[patch(unres())])
    viol = check_c2(quiet_state("PRODUCE_DIRECT_RESPONSE"), mi)
    assert any(v.startswith("C2:GOAL_MISMATCH") for v in viol)


def test_mutex_u_d():
    mi = make_input(claims=[conf()], patches=[patch(conf())])
    s = quiet_state("PRODUCE_DIRECT_RESPONSE")
    s["userInputRequired"] = u_block(True)
    s["directResponseSufficient"] = {
        "value": True, "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
        "evidenceRefs": ["claim:effective/0"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:MUTEX_UD") for v in viol)


def test_mutex_u_e():
    mi = make_input(claims=[unres()], patches=[patch(unres())])
    s = quiet_state()
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.read-only", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["capability:cal.read-only"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:MUTEX_UE") for v in viol)


def test_mutex_u_n():
    mi = make_input(claims=[unres()], patches=[patch(unres())])
    s = quiet_state()
    s["newDurableKnowledgePresent"] = {
        "value": True, "reasonCodes": ["NOVEL_SEMANTIC_UNIT"],
        "evidenceRefs": ["claim:effective/0"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:MUTEX_UN") for v in viol)


def test_unknown_capability():
    mi = make_input()
    s = quiet_state("UNDERSTAND_USER_INTENT")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "ghost.cap", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["capability:ghost.cap"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:UNKNOWN_CAPABILITY") for v in viol)


def test_spliced_evidence():
    mi = make_input(caps=[RO_CAP, IRR_CAP])
    s = quiet_state("UNDERSTAND_USER_INTENT")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.read-only", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["capability:cal.read-only",
                         "capability:cal.irreversible"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:SPLICED_EVIDENCE") for v in viol)


def test_risk_mismatch():
    mi = make_input(caps=[IRR_CAP])
    s = quiet_state("UNDERSTAND_USER_INTENT")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.irreversible", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["capability:cal.irreversible"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:RISK_MISMATCH") for v in viol)


def test_project_risk_table():
    assert project_risk(RO_CAP) == "READ_ONLY"
    assert project_risk(LOC_CAP) == "LOCAL_DURABLE"
    assert project_risk(IRR_CAP) == "EXTERNAL_IRREVERSIBLE"
    assert project_risk({"readOnly": False,
                         "sideEffectClass": "SOMETHING_NEW"}) is None


def test_fake_confirmed_auth_unresolved_citation():
    # Cites an UNRESOLVED claim as authorization: target exists but the
    # status gate (confirmed conf>=0.5) fails -> FAKE_AUTH.
    mi = make_input(caps=[IRR_CAP], claims=[unres()],
                    patches=[patch(unres())])
    s = quiet_state("PRODUCE_DIRECT_RESPONSE")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.irreversible",
            "riskLevel": "EXTERNAL_IRREVERSIBLE",
            "argumentCompleteness": "GROUNDED",
            "authorizationStatus": "CONFIRMED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_ACTION_REQUIRED"],
        "evidenceRefs": ["capability:cal.irreversible",
                         "claim:effective/0"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:FAKE_AUTH") for v in viol)


def test_e_true_preferred_rejected():
    mi = make_input()
    s = quiet_state("UNDERSTAND_USER_INTENT")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.read-only", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "PREFERRED"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["capability:cal.read-only"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:NEEDS_REQUIRED_NOW") for v in viol)


def test_d_true_empty_claims_rejected():
    mi = make_input()
    s = quiet_state("UNDERSTAND_USER_INTENT")
    s["userInputRequired"] = u_block(False)
    s["directResponseSufficient"] = {
        "value": True, "reasonCodes": ["GOAL_ACHIEVED"],
        "evidenceRefs": ["context:" + SID]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:DIRECT_UNGROUNDED") for v in viol)


def test_d_true_without_citing_claim():
    mi = make_input(claims=[conf()], patches=[patch(conf())])
    s = quiet_state("PRODUCE_DIRECT_RESPONSE")
    s["userInputRequired"] = u_block(False, refs=["context:" + SID])
    s["directResponseSufficient"] = {
        "value": True, "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
        "evidenceRefs": ["context:" + SID]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:DIRECT_UNCITED") for v in viol)


def test_d_true_clean_passes():
    mi = make_input(claims=[conf()], patches=[patch(conf())])
    s = quiet_state("PRODUCE_DIRECT_RESPONSE")
    s["userInputRequired"] = u_block(False)
    s["directResponseSufficient"] = {
        "value": True, "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
        "evidenceRefs": ["claim:effective/0"]}
    assert check_c2(s, mi) == []


def test_readonly_e_true_clean_passes():
    mi = make_input()
    s = quiet_state("UNDERSTAND_USER_INTENT")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.read-only", "riskLevel": "READ_ONLY",
            "argumentCompleteness": "NOT_REQUIRED",
            "authorizationStatus": "NOT_REQUIRED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
        "evidenceRefs": ["capability:cal.read-only"]}
    assert check_c2(s, mi) == []


def test_novelty_not_judged_by_validator():
    mi = make_input(claims=[conf()], patches=[patch(conf())])
    s = quiet_state("PRODUCE_DIRECT_RESPONSE")
    s["userInputRequired"] = u_block(False)
    s["directResponseSufficient"] = {
        "value": False, "reasonCodes": ["AWAITING_USER_INPUT"],
        "evidenceRefs": ["claim:effective/0"]}
    s["newDurableKnowledgePresent"] = {
        "value": True, "reasonCodes": ["NOVEL_SEMANTIC_UNIT"],
        "evidenceRefs": ["claim:effective/0"]}
    assert check_c2(s, mi) == []


def test_wait_goal_requires_quiet_flags():
    mi = make_input()
    s = quiet_state("WAIT_FOR_RUNTIME_DEPENDENCY")
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:WAIT_NONQUIET") for v in viol)

def test_fake_confirmed_auth_silent():
    mi = make_input(caps=[IRR_CAP], claims=[conf()],
                    patches=[patch(conf())])
    s = quiet_state("PRODUCE_DIRECT_RESPONSE")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.irreversible",
            "riskLevel": "EXTERNAL_IRREVERSIBLE",
            "argumentCompleteness": "GROUNDED",
            "authorizationStatus": "CONFIRMED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_ACTION_REQUIRED"],
        "evidenceRefs": ["capability:cal.irreversible"]}
    viol = check_c2(s, mi)
    assert any(v.startswith("C2:FAKE_AUTH") for v in viol)


def test_auth_via_results_entry_passes():
    approval = {"invocation_id": "i-9", "capability_id": "cal.irreversible",
                "status": "succeeded",
                "content": {"approval": "user-approved [CAL:auth]"},
                "provenance": {"approvedBy": "user"},
                "source_refs": []}
    mi = make_input(caps=[IRR_CAP], claims=[conf(), conf()],
                    patches=[patch(conf())], results=[approval])
    s = quiet_state("GATHER_EXTERNAL_EVIDENCE")
    s["userInputRequired"] = u_block(False)
    s["externalStepRequired"] = {
        "value": True,
        "capabilityAssessment": {
            "capabilityId": "cal.irreversible",
            "riskLevel": "EXTERNAL_IRREVERSIBLE",
            "argumentCompleteness": "GROUNDED",
            "authorizationStatus": "CONFIRMED",
            "executionNecessity": "REQUIRED_NOW"},
        "reasonCodes": ["EXTERNAL_ACTION_REQUIRED"],
        "evidenceRefs": ["capability:cal.irreversible",
                         "claim:effective/0"]}
    assert check_c2(s, mi) == []
