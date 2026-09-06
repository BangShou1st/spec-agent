"""8 synthetic calibration cases (wire-compatible, non-benchmark).

All IDs are uuid5-derived from case names; all texts carry [CAL:...]
markers. Authorization records live in snapshot.capabilityResults[] and
grounding content in confirmed claims: real wire positions only, never
observation:. Each case ships its design-intended ideal_state (C1/C2
clean, mapping to expected_mapping) so fixture/design coherence is
testable without calling any provider.
"""
from __future__ import annotations

import uuid

NAMESPACE_TAG = "spec-agent-r4-cal-"
MAPPABLE = ["REQUEST_USER_INPUT", "INVOKE_CAPABILITY", "RESPOND_TO_USER",
            "CREATE_NODE"]


def mkid(*parts: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, NAMESPACE_TAG + "-".join(parts)))


def _claim(status, confidence, text):
    return {"kind": "calibration", "text": text, "status": status,
            "confidence": confidence, "sourceNodeId": None,
            "sourceAnswerId": None}


def _cap(cid, read_only, cls):
    return {"id": cid, "version": "1",
            "description": "[CAL] synthetic descriptor " + cid,
            "readOnly": read_only, "sideEffectClass": cls}


def _input(cid, event_text, lineage, effective, caps, results=(),
           autonomy="ADVISOR", kind="ANSWER_SUBMITTED"):
    sid = mkid(cid, "snapshot")
    rid = mkid(cid, "route")
    anchor = lineage[0]["node"]["id"] if lineage else mkid(cid, "anchor")
    return {
        "eligibleFamilies": list(MAPPABLE),
        "event": {"kind": kind, "anchorNodeId": anchor,
                  "selectedOptionId": None, "freeText": event_text},
        "observation": {},
        "snapshot": {
            "snapshotId": sid,
            "contextHash": "cal-" + cid.lower(),
            "routeId": rid,
            "anchorNodeId": anchor,
            "routeContext": {"routeId": rid, "tipNodeId": anchor,
                             "label": "[CAL] route"},
            "lineage": lineage,
            "effectiveClaims": list(effective),
            "metadata": {"projectTitle": "[CAL] " + cid},
            "availableCapabilities": list(caps),
            "capabilityResults": list(results),
            "relations": [],
            "relatedNodes": [],
            "autonomy": {"mode": autonomy},
        },
    }


def _entry(cid, answer_text, patch_claims, node_text):
    nid, aid, pid = mkid(cid, "node"), mkid(cid, "answer"), mkid(cid, "patch")
    entry = {"node": {"id": nid, "body": {"text": node_text, "options": [],
                                          "acceptsFreeText": True},
                       "kind": "INTERACTION"}}
    entry["answer"] = {"id": aid, "nodeId": nid, "selectedOptionId": None,
                       "freeText": answer_text} if answer_text else None
    entry["patches"] = [{"id": pid, "claims": list(patch_claims)}] \
        if patch_claims is not None else []
    return entry, nid, aid, pid


def _u_true(gap, code, refs):
    return {"value": True, "gapType": gap, "reasonCodes": [code],
            "evidenceRefs": list(refs)}


def _u_false(refs):
    return {"value": False, "gapType": None,
            "reasonCodes": ["USER_INPUT_SUFFICIENT"],
            "evidenceRefs": list(refs)}


def _e_false(code, refs):
    return {"value": False, "capabilityAssessment": None,
            "reasonCodes": [code], "evidenceRefs": list(refs)}


def _d_false(code, refs):
    return {"value": False, "reasonCodes": [code],
            "evidenceRefs": list(refs)}


def _n_false(code, refs):
    return {"value": False, "reasonCodes": [code],
            "evidenceRefs": list(refs)}


def _state(goal, u, e, d, n):
    return {"version": "planning-state.v2", "goalType": goal,
            "userInputRequired": u, "externalStepRequired": e,
            "directResponseSufficient": d,
            "newDurableKnowledgePresent": n}


def _build():
    cases = []

    # CAL-U1: empty everything -> UNDERSTAND -> REQUEST.
    entry, nid, aid, pid = _entry(
        "CAL-U1", "[CAL:U1-answer]", [], "[CAL:U1-question]")
    mi = _input("CAL-U1", "[CAL:U1-answer]", [entry], [], [])
    cases.append({
        "id": "CAL-U1", "reference_goal": "UNDERSTAND_USER_INTENT",
        "expected_mapping": "REQUEST_USER_INPUT", "model_input": mi,
        "ideal_state": _state(
            "UNDERSTAND_USER_INTENT",
            _u_true("intent_gap", "INTENT_GAP", ["answer:" + aid]),
            _e_false("NO_EXTERNAL_NEED", ["event:kind"]),
            _d_false("NO_GROUNDED_CONTENT", ["answer:" + aid]),
            _n_false("NOT_DURABLE", ["event:kind"])),
    })

    # CAL-U2: unresolved + high-risk cap, no args/auth -> RESOLVE -> REQUEST.
    uc = _claim("unresolved", 0.2, "[CAL:U2-need-unclear]")
    entry, nid, aid, pid = _entry(
        "CAL-U2", "[CAL:U2-answer]", [dict(uc)], "[CAL:U2-question]")
    irr = "cal.external.irreversible"
    mi = _input("CAL-U2", "[CAL:U2-answer]", [entry], [dict(uc)],
                [_cap("cal.read-only", True, "NONE"),
                 _cap(irr, False, "EXTERNAL_IRREVERSIBLE")])
    cases.append({
        "id": "CAL-U2", "reference_goal": "RESOLVE_USER_CHOICE",
        "expected_mapping": "REQUEST_USER_INPUT", "model_input": mi,
        "ideal_state": _state(
            "RESOLVE_USER_CHOICE",
            _u_true("intent_gap", "INTENT_GAP", ["claim:effective/0"]),
            _e_false("SAFER_PATH_AVAILABLE", ["capability:" + irr]),
            _d_false("AWAITING_USER_INPUT", ["claim:effective/0"]),
            _n_false("NOT_STANDALONE", ["claim:effective/0"])),
    })

    # CAL-U3: conflicting unresolved claims -> RESOLVE -> REQUEST.
    c1 = _claim("unresolved", 0.4, "[CAL:U3-option-alpha]")
    c2 = _claim("unresolved", 0.4, "[CAL:U3-option-beta]")
    entry, nid, aid, pid = _entry(
        "CAL-U3", "[CAL:U3-answer]", [dict(c1), dict(c2)],
        "[CAL:U3-question]")
    mi = _input("CAL-U3", "[CAL:U3-answer]", [entry],
                [dict(c1), dict(c2)], [_cap("cal.read-only", True, "NONE")])
    cases.append({
        "id": "CAL-U3", "reference_goal": "RESOLVE_USER_CHOICE",
        "expected_mapping": "REQUEST_USER_INPUT", "model_input": mi,
        "ideal_state": _state(
            "RESOLVE_USER_CHOICE",
            _u_true("choice_gap", "CHOICE_GAP",
                    ["claim:effective/0", "claim:effective/1"]),
            _e_false("CAPABILITY_RELEVANT_NOT_REQUIRED",
                     ["capability:cal.read-only"]),
            _d_false("AWAITING_USER_INPUT", ["claim:effective/0"]),
            _n_false("NOT_STANDALONE", ["claim:effective/0"])),
    })

    # CAL-E1: irreversible positive with approval record -> GATHER -> INVOKE.
    g1 = _claim("confirmed", 0.9, "[CAL:E1-arg-host-grounded]")
    g2 = _claim("confirmed", 0.9, "[CAL:E1-arg-window-grounded]")
    auth = _claim("confirmed", 0.9, "[CAL:E1-user-authorized-actuator]")
    entry, nid, aid, pid = _entry(
        "CAL-E1", "[CAL:E1-answer]", [dict(g1)], "[CAL:E1-question]")
    cap_id = "cal.actuator.external"
    approval = {"invocation_id": mkid("CAL-E1", "approval"),
                "capability_id": cap_id, "status": "succeeded",
                "content": {"approval": "[CAL:E1-user-approved]"},
                "provenance": {"approvedBy": "[CAL:E1-user]"},
                "source_refs": []}
    mi = _input("CAL-E1", "[CAL:E1-answer]", [entry],
                [dict(g1), dict(g2), dict(auth)],
                [_cap("cal.read-only", True, "NONE"),
                 _cap(cap_id, False, "EXTERNAL_IRREVERSIBLE")],
                results=[approval])
    cases.append({
        "id": "CAL-E1", "reference_goal": "GATHER_EXTERNAL_EVIDENCE",
        "expected_mapping": "INVOKE_CAPABILITY", "model_input": mi,
        "ideal_state": _state(
            "GATHER_EXTERNAL_EVIDENCE",
            _u_false(["claim:effective/0"]),
            {"value": True,
             "capabilityAssessment": {
                 "capabilityId": cap_id,
                 "riskLevel": "EXTERNAL_IRREVERSIBLE",
                 "argumentCompleteness": "GROUNDED",
                 "authorizationStatus": "CONFIRMED",
                 "executionNecessity": "REQUIRED_NOW"},
             "reasonCodes": ["EXTERNAL_ACTION_REQUIRED"],
             "evidenceRefs": ["capability:" + cap_id,
                              "claim:effective/0", "claim:effective/2"]},
            _d_false("AWAITING_EXTERNAL_RESULT",
                     ["capability:" + cap_id]),
            _n_false("REDUNDANT_WITH_EXISTING",
                     ["claim:effective/0"])),
    })

    # CAL-E2: read-only positive; asking cannot help -> RESOLVE but u=false.
    uq = _claim("unresolved", 0.3, "[CAL:E2-missing-upstream-status]")
    kc = _claim("confirmed", 0.8, "[CAL:E2-user-answered-twice-identical]")
    entry, nid, aid, pid = _entry(
        "CAL-E2", "[CAL:E2-answer]", [dict(uq)], "[CAL:E2-question]")
    ro = "cal.upstream.read-only"
    mi = _input("CAL-E2", "[CAL:E2-answer]", [entry],
                [dict(uq), dict(kc)], [_cap(ro, True, "NONE")])
    cases.append({
        "id": "CAL-E2", "reference_goal": "RESOLVE_USER_CHOICE",
        "expected_mapping": "INVOKE_CAPABILITY", "model_input": mi,
        "ideal_state": _state(
            "RESOLVE_USER_CHOICE",
            _u_false(["claim:effective/0"]),
            {"value": True,
             "capabilityAssessment": {
                 "capabilityId": ro, "riskLevel": "READ_ONLY",
                 "argumentCompleteness": "NOT_REQUIRED",
                 "authorizationStatus": "NOT_REQUIRED",
                 "executionNecessity": "REQUIRED_NOW"},
             "reasonCodes": ["EXTERNAL_EVIDENCE_REQUIRED"],
             "evidenceRefs": ["capability:" + ro]},
            _d_false("AWAITING_EXTERNAL_RESULT",
                     ["capability:" + ro]),
            _n_false("NOT_DURABLE", ["claim:effective/0"])),
    })

    # CAL-D1: confirmed grounded content, nothing pending -> RESPOND.
    d1 = _claim("confirmed", 0.9, "[CAL:D1-grounded-answer-content]")
    entry, nid, aid, pid = _entry(
        "CAL-D1", "[CAL:D1-answer]", [dict(d1)], "[CAL:D1-question]")
    mi = _input("CAL-D1", "[CAL:D1-answer]", [entry], [dict(d1)],
                [_cap("cal.read-only", True, "NONE")])
    cases.append({
        "id": "CAL-D1", "reference_goal": "PRODUCE_DIRECT_RESPONSE",
        "expected_mapping": "RESPOND_TO_USER", "model_input": mi,
        "ideal_state": _state(
            "PRODUCE_DIRECT_RESPONSE",
            _u_false(["claim:effective/0"]),
            _e_false("NO_EXTERNAL_NEED", ["event:kind"]),
            {"value": True,
             "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
             "evidenceRefs": ["claim:effective/0"]},
            _n_false("REDUNDANT_WITH_EXISTING",
                     ["claim:effective/0"])),
    })

    # CAL-N1: genuinely novel durable constraint; no primary need -> CREATE.
    n1 = _claim("confirmed", 0.9, "[CAL:N1-retention-ninety-days]")
    entry, nid, aid, pid = _entry(
        "CAL-N1", "[CAL:N1-answer]", [dict(n1)], "[CAL:N1-question]")
    mi = _input("CAL-N1", "[CAL:N1-answer]", [entry], [dict(n1)],
                [_cap("cal.read-only", True, "NONE")], kind="CONTINUE")
    cases.append({
        "id": "CAL-N1", "reference_goal": "PRODUCE_DIRECT_RESPONSE",
        "expected_mapping": "CREATE_NODE", "model_input": mi,
        "ideal_state": _state(
            "PRODUCE_DIRECT_RESPONSE",
            _u_false(["claim:effective/0"]),
            _e_false("NO_EXTERNAL_NEED", ["event:kind"]),
            # No response owed on this continuation: the durable fact is
            # the news. AWAITING_* codes assume a pending need; the vocab
            # gap is documented, polarity and resolution are what C1 checks.
            _d_false("NO_GROUNDED_CONTENT", ["event:kind"]),
            {"value": True, "reasonCodes": ["NOVEL_SEMANTIC_UNIT"],
             "evidenceRefs": ["claim:effective/0"]}),
    })

    # CAL-N2: patch merely restates the confirmed claim -> n=false.
    r0 = _claim("confirmed", 0.9, "[CAL:N2-decided-threshold]")
    r1 = _claim("confirmed", 0.9, "[CAL:N2-decided-threshold-restated]")
    entry, nid, aid, pid = _entry(
        "CAL-N2", "[CAL:N2-answer]", [dict(r1)], "[CAL:N2-question]")
    mi = _input("CAL-N2", "[CAL:N2-answer]", [entry], [dict(r0)],
                [_cap("cal.read-only", True, "NONE")])
    cases.append({
        "id": "CAL-N2", "reference_goal": "PRODUCE_DIRECT_RESPONSE",
        "expected_mapping": "RESPOND_TO_USER", "model_input": mi,
        "ideal_state": _state(
            "PRODUCE_DIRECT_RESPONSE",
            _u_false(["claim:effective/0"]),
            _e_false("NO_EXTERNAL_NEED", ["event:kind"]),
            {"value": True,
             "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
             "evidenceRefs": ["claim:effective/0"]},
            _n_false("REPHRASING", ["claim:patch/" + pid + "/0"])),
    })
    return cases


CALIBRATION_CASES = _build()


def get_case(case_id: str) -> dict:
    for case in CALIBRATION_CASES:
        if case["id"] == case_id:
            return case
    raise KeyError("unknown calibration case: " + case_id)
