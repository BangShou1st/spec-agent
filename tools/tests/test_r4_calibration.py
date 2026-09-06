"""Calibration fixture tests — TDD Unit 6.

Pins: 8 synthetic non-benchmark cases, wire-legal shapes, no benchmark
identity leakage, reference-goal coherence, and ideal-state mapping.
"""
import re
import uuid

from semantic_planning.calibration import CALIBRATION_CASES, get_case, mkid
from semantic_planning.mapping_v2 import derive_outcome
from semantic_planning.planning_v2 import resolvable_refs, validate_c1
from semantic_planning.reference_goal import derive_reference_goal_type
from semantic_planning.validation_v2 import check_c2

UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                     r"[0-9a-f]{4}-[0-9a-f]{12}")

EXPECTED_IDS = ["CAL-U1", "CAL-U2", "CAL-U3", "CAL-E1", "CAL-E2",
                "CAL-D1", "CAL-N1", "CAL-N2"]


def test_eight_cases_exact_ids():
    assert [c["id"] for c in CALIBRATION_CASES] == EXPECTED_IDS


def test_wire_shape_legal():
    for case in CALIBRATION_CASES:
        mi = case["model_input"]
        assert set(mi) >= {"eligibleFamilies", "event", "snapshot"}, case["id"]
        snap = mi["snapshot"]
        for key in ["snapshotId", "lineage", "effectiveClaims",
                    "availableCapabilities", "capabilityResults",
                    "autonomy"]:
            assert key in snap, (case["id"], key)
        assert mi["event"]["kind"] in ["ANSWER_SUBMITTED", "CONTINUE"], \
            case["id"]


def test_no_benchmark_identity_leak():
    blob = chr(10).join(c["id"] + str(c["model_input"]) for c in CALIBRATION_CASES)
    assert "[E" not in blob
    assert "observation:" not in blob
    assert "e01-answer" not in blob and "e17-answer" not in blob


def test_cal_markers_and_uuid5_ids():
    for case in CALIBRATION_CASES:
        assert "[CAL:" in str(case["model_input"]), case["id"]
        sid = case["model_input"]["snapshot"]["snapshotId"]
        assert UUID_RE.fullmatch(sid), case["id"]
        expect = mkid(case["id"], "snapshot")
        assert sid == expect, case["id"]


def test_eligible_families_cover_mappable():
    want = {"REQUEST_USER_INPUT", "INVOKE_CAPABILITY", "RESPOND_TO_USER",
            "CREATE_NODE"}
    for case in CALIBRATION_CASES:
        assert want <= set(case["model_input"]["eligibleFamilies"]), \
            case["id"]


def test_reference_goals_declared_match_derivation():
    for case in CALIBRATION_CASES:
        assert derive_reference_goal_type(case["model_input"]) == \
            case["reference_goal"], case["id"]


def test_cal_e1_carries_approval_record():
    mi = get_case("CAL-E1")["model_input"]
    results = mi["snapshot"]["capabilityResults"]
    assert results, "CAL-E1 must carry an approval record"
    assert any(r.get("provenance") or r.get("content")
               for r in results)
    caps = {c["id"] for c in mi["snapshot"]["availableCapabilities"]}
    assert any(r.get("capability_id") in caps for r in results)


def test_ideal_states_validate_and_map():
    for case in CALIBRATION_CASES:
        if case["id"] == "CAL-N2":
            continue
        state = case["ideal_state"]
        mi = case["model_input"]
        _, c1 = validate_c1(state, mi)
        assert c1 is None, (case["id"], c1)
        c2 = check_c2(state, mi)
        assert c2 == [], (case["id"], c2)
        out, _ = derive_outcome(state, mi["eligibleFamilies"])
        assert out == case["expected_mapping"], (case["id"], out)


def test_cal_n2_ideal_has_n_false():
    case = get_case("CAL-N2")
    assert case["ideal_state"]["newDurableKnowledgePresent"]["value"] is \
        False
    out, _ = derive_outcome(case["ideal_state"],
                            case["model_input"]["eligibleFamilies"])
    assert out != "CREATE_NODE"


def test_expected_mapping_summary():
    want = {"CAL-U1": "REQUEST_USER_INPUT", "CAL-U2": "REQUEST_USER_INPUT",
            "CAL-U3": "REQUEST_USER_INPUT", "CAL-E1": "INVOKE_CAPABILITY",
            "CAL-E2": "INVOKE_CAPABILITY", "CAL-D1": "RESPOND_TO_USER",
            "CAL-N1": "CREATE_NODE"}
    for cid, mapping in want.items():
        assert get_case(cid)["expected_mapping"] == mapping
