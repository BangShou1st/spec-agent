"""G1-G5 reference goal derivation tests — TDD Unit 2.

The reference function reads ONLY pre-flag observables. Each rule gets at
least one case; order-independence, fallback, unresolved priority, and the
capabilityResults path are pinned.
"""
import pytest

from semantic_planning.reference_goal import derive_reference_goal_type


def snap(claims=(), patches=(), results=()):
    return {
        "snapshot": {
            "effectiveClaims": list(claims),
            "lineage": [{"node": {"id": "n1"}, "patches": list(patches)}],
            "capabilityResults": list(results),
        }
    }


def claim(status, conf=0.5):
    return {"status": status, "confidence": conf, "text": "[CAL:x]"}


def patch_claims(*claims):
    return {"id": "p1", "claims": list(claims)}


def test_g1_empty_everything_understand():
    assert derive_reference_goal_type(snap()) == "UNDERSTAND_USER_INTENT"


def test_g1_empty_effective_but_patch_empty_list():
    assert derive_reference_goal_type(
        snap(claims=[], patches=[{"id": "p1", "claims": []}])) == \
        "UNDERSTAND_USER_INTENT"


def test_g2_unresolved_in_effective():
    assert derive_reference_goal_type(
        snap(claims=[claim("unresolved", 0.2)])) == "RESOLVE_USER_CHOICE"


def test_g2_unresolved_only_in_patch():
    assert derive_reference_goal_type(
        snap(patches=[patch_claims(claim("unresolved", 0.9))])) == \
        "RESOLVE_USER_CHOICE"


def test_g2_unresolved_beats_confirmed():
    assert derive_reference_goal_type(snap(
        claims=[claim("confirmed", 1.0), claim("unresolved", 0.3)])) == \
        "RESOLVE_USER_CHOICE"


def test_g2_unresolved_beats_results():
    assert derive_reference_goal_type(snap(
        claims=[claim("unresolved", 0.4)],
        results=[{"invocation_id": "i1"}])) == "RESOLVE_USER_CHOICE"


def test_g3_results_path():
    assert derive_reference_goal_type(snap(
        claims=[claim("confirmed", 0.8)],
        results=[{"invocation_id": "i1"}])) == "GATHER_EXTERNAL_EVIDENCE"


def test_g4_confirmed_direct_path():
    assert derive_reference_goal_type(
        snap(claims=[claim("confirmed", 0.9)])) == "PRODUCE_DIRECT_RESPONSE"


def test_g4_confirmed_at_threshold():
    assert derive_reference_goal_type(
        snap(claims=[claim("confirmed", 0.5)])) == "PRODUCE_DIRECT_RESPONSE"


def test_g5_fallback_assumed_only():
    assert derive_reference_goal_type(
        snap(claims=[claim("assumed", 0.3)])) == "UNDERSTAND_USER_INTENT"


def test_g5_fallback_low_conf_confirmed():
    assert derive_reference_goal_type(
        snap(claims=[claim("confirmed", 0.4)])) == "UNDERSTAND_USER_INTENT"


def test_g5_fallback_rejected_only():
    assert derive_reference_goal_type(
        snap(claims=[claim("rejected", 0.9)])) == "UNDERSTAND_USER_INTENT"


def test_order_independent_single_match():
    import itertools
    got = set()
    got.add(derive_reference_goal_type(
        snap(claims=[claim("confirmed", 0.8)])))
    got.add(derive_reference_goal_type(
        snap(claims=[claim("confirmed", 0.8)], results=[])))
    assert got == {"PRODUCE_DIRECT_RESPONSE"}


def test_ignores_model_flags():
    mi = snap(claims=[claim("confirmed", 0.9)])
    mi["modelFlags"] = {"userInputRequired": True}
    mi["reasonCodes"] = ["INTENT_GAP"]
    mi["expected"] = ["REQUEST_USER_INPUT"]
    assert derive_reference_goal_type(mi) == "PRODUCE_DIRECT_RESPONSE"


def test_snake_case_snapshot_accepted():
    mi = {"snapshot": {"effective_claims": [claim("confirmed", 0.7)],
                       "lineage": [],
                       "capability_results": []}}
    assert derive_reference_goal_type(mi) == "PRODUCE_DIRECT_RESPONSE"


def test_missing_snapshot_falls_back():
    assert derive_reference_goal_type({}) == "UNDERSTAND_USER_INTENT"
    assert derive_reference_goal_type({"snapshot": None}) == \
        "UNDERSTAND_USER_INTENT"
