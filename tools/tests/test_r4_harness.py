"""R4 harness unit tests — TDD Unit 7.

Pins pipeline order (extract -> parse -> C1 -> C2 -> C3), per-rep
classification, manifest provenance completeness (no secrets), gate
math (calibration, G11 flip, G12 frozen set), and the frozen sampling
profile. No provider calls here.
"""
import json

from semantic_planning_diagnostic_r4 import (
    FROZEN_REGRESSION_28,
    SAMPLING_PROFILE,
    build_manifest,
    calibration_gate,
    classify_rep,
    extract_content,
    flip_rate,
)
from semantic_planning.calibration import get_case


def envelope(content: str) -> str:
    return json.dumps({"choices": [{"message": {"content": content}}]})


def test_extract_valid_envelope():
    body, err = extract_content(envelope("{\"a\": 1}"))
    assert err is None and body == "{\"a\": 1}"


def test_extract_garbage_is_c1():
    cls = classify_rep("not-json-envelope", get_case("CAL-U1")["model_input"])
    assert cls["class"] == "C1"


def test_extract_empty_content_is_c1():
    cls = classify_rep(envelope("   "), get_case("CAL-U1")["model_input"])
    assert cls["class"] == "C1"


def test_c1_stops_c2():
    bad = envelope(json.dumps({"version": "planning-state.v2"}))
    cls = classify_rep(bad, get_case("CAL-U1")["model_input"])
    assert cls["class"] == "C1"
    assert cls.get("c2") in (None, [])
    assert "outcome" not in cls or cls["outcome"] in (
        "CONTRACT_VIOLATION_C1",)


def test_c2_stops_mapping():
    state = dict(get_case("CAL-U1")["ideal_state"])
    state["goalType"] = "PRODUCE_DIRECT_RESPONSE"
    cls = classify_rep(envelope(json.dumps(state)),
                        get_case("CAL-U1")["model_input"])
    assert cls["class"] == "C2"
    assert cls["outcome"] == "CONTRACT_VIOLATION_C2"
    assert any("GOAL_MISMATCH" in v for v in cls["violations"])


def test_c3_clean_maps():
    case = get_case("CAL-D1")
    cls = classify_rep(envelope(json.dumps(case["ideal_state"])),
                        case["model_input"])
    assert cls["class"] == "C3"
    assert cls["outcome"] == "RESPOND_TO_USER"


def test_manifest_provenance_complete_no_secrets():
    m = build_manifest(head="abc123", dirty=False, oracle_digest="o" * 64,
                       replay_digest="r" * 64, counts={"cases": 8, "reps": 3})
    for key in ["head", "dirty", "experiment", "parent_lineage",
                "oracle_path", "oracle_digest", "replay_digest",
                "prompt_hash", "schema_hash", "reason_vocab_hash",
                "evidence_vocab_hash", "mapping_version", "mapping_hash",
                "reference_goal_version", "reference_goal_hash",
                "sampling_profile", "endpoint", "model", "user_agent",
                "transport", "temperature", "top_p", "seed_note"]:
        assert key in m, key
    blob = json.dumps(m).lower()
    assert "bearer" not in blob
    assert "secret" not in blob
    assert "api_key" not in blob and "apikey" not in blob
    assert m["experiment"] == "SEMANTIC_PLANNING_DIAGNOSTIC_R4"
    assert m["sampling_profile"]["temperature"] == 0


def test_sampling_profile_frozen():
    assert SAMPLING_PROFILE["temperature"] == 0
    assert SAMPLING_PROFILE["top_p"] == 1
    assert SAMPLING_PROFILE["seed"] == "accepted-enforcement-unverifiable"
    assert SAMPLING_PROFILE["max_tokens"] == 800
    assert SAMPLING_PROFILE["response_format"] == "json_object"
    assert SAMPLING_PROFILE["stream"] is False
    assert SAMPLING_PROFILE["transport"] == "DIRECT"


def test_calibration_gate_math():
    recs = []
    for i in range(8):
        recs.append({"case": "C%d" % i, "passed": i < 7})
    assert calibration_gate(recs) == {"passed": 7, "total": 8,
                                      "gate": True}
    recs[6]["passed"] = False
    assert calibration_gate(recs)["gate"] is False


def test_flip_rate_formula():
    assert flip_rate([[True, True, True], [True, False, True]]) == 0.5
    assert flip_rate([[True, True, True]]) == 0.0
    assert flip_rate([]) is None


def test_g12_frozen_set():
    assert len(FROZEN_REGRESSION_28) == 28
    ids = ["%s %s/%s r%s" % t for t in FROZEN_REGRESSION_28]
    assert not any("E07-resolved" in i for i in ids)
    assert not any("E22" in i for i in ids)
    assert not any(i.split()[1].startswith("E10/") for i in ids)
    assert any("E17/unconfirmed" in i for i in ids)
