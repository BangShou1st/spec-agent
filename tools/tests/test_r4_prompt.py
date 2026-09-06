"""R4 system prompt tests — TDD Unit 5.

Pins the 15 required content items and the prohibitions (no benchmark
hints, no scenario names, no family names as guidance, no observation:
prefix, no removed goal values, no case UUIDs).
"""
import re

from semantic_planning.prompt_v2 import SYSTEM_PROMPT_R4, prompt_hash

ALLOWED_LINE = "ALLOWED_EVIDENCE_PREFIXES:"
EIGHT = ["node:", "answer:", "patch:", "context:", "route:", "claim:",
         "capability:", "event:"]


def test_goal_rules_present():
    for token in ["G1", "G2", "G3", "G4", "G5", "UNDERSTAND_USER_INTENT",
                  "RESOLVE_USER_CHOICE", "PRODUCE_DIRECT_RESPONSE",
                  "GATHER_EXTERNAL_EVIDENCE",
                  "WAIT_FOR_RUNTIME_DEPENDENCY"]:
        assert token in SYSTEM_PROMPT_R4


def test_flag_definitions_present():
    for token in ["userInputRequired", "externalStepRequired",
                  "directResponseSufficient",
                  "newDurableKnowledgePresent", "gapType",
                  "capabilityAssessment", "capabilityId"]:
        assert token in SYSTEM_PROMPT_R4


def test_invariants_present():
    assert "u=true" in SYSTEM_PROMPT_R4
    assert "d=false" in SYSTEM_PROMPT_R4
    assert "REQUIRED_NOW" in SYSTEM_PROMPT_R4


def test_risk_and_auth_rules_present():
    for token in ["readOnly", "sideEffectClass", "capabilityResults",
                  "CONFIRMED", "MISSING"]:
        assert token in SYSTEM_PROMPT_R4
    assert "Silence is never authorization" in SYSTEM_PROMPT_R4


def test_e10_invariant_present():
    assert "ANSWER_SUBMITTED" in SYSTEM_PROMPT_R4
    assert "ANSWER_UNDERSTOOD" in SYSTEM_PROMPT_R4
    assert "GOAL_SATISFIED" in SYSTEM_PROMPT_R4


def test_novelty_rule_present():
    assert "NOT new" in SYSTEM_PROMPT_R4 or \
        "is NOT new" in SYSTEM_PROMPT_R4


def test_allowed_prefixes_exactly_eight():
    lines = [ln for ln in SYSTEM_PROMPT_R4.splitlines()
             if ln.startswith(ALLOWED_LINE)]
    assert len(lines) == 1
    for prefix in EIGHT:
        assert prefix in lines[0]


def test_observation_forbidden_not_allowed():
    assert "observation:" in SYSTEM_PROMPT_R4  # prohibition sentence
    lines = [ln for ln in SYSTEM_PROMPT_R4.splitlines()
             if ln.startswith(ALLOWED_LINE)]
    assert "observation:" not in lines[0]
    assert "FORBIDDEN" in SYSTEM_PROMPT_R4


def test_structure_only_example():
    assert "STRUCTURE ONLY" in SYSTEM_PROMPT_R4
    assert "planning-state.v2" in SYSTEM_PROMPT_R4


def test_no_free_form_reasoning():
    assert "no free-form" in SYSTEM_PROMPT_R4.lower() or \
        "no prose" in SYSTEM_PROMPT_R4.lower()


def test_banned_benchmark_hints():
    for token in ["REQUEST_USER_INPUT", "INVOKE_CAPABILITY",
                  "RESPOND_TO_USER", "CREATE_NODE", "E10", "E17",
                  "expected action", "expected label", "benchmark"]:
        assert token not in SYSTEM_PROMPT_R4, token


def test_banned_removed_goals():
    assert "EXECUTE_AUTHORIZED_ACTION" not in SYSTEM_PROMPT_R4
    assert "CAPTURE_DURABLE_KNOWLEDGE" not in SYSTEM_PROMPT_R4


def test_no_gate_gaming_language():
    for token in ["gate", "pass the evaluation", "score", "improve the"]:
        assert token not in SYSTEM_PROMPT_R4.lower(), token


def test_no_uuids():
    assert not re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-", SYSTEM_PROMPT_R4)


def test_prompt_hash_stable_hex():
    h1, h2 = prompt_hash(), prompt_hash()
    assert h1 == h2
    assert re.fullmatch(r"[0-9a-f]{64}", h1)
