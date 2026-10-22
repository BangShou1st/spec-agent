"""Offline source contract: candidate JSON labels versus the Java executable mirror.

This checks frozen declarations, not Java execution or human semantic correctness.
It deliberately fails closed when the declaration layout cannot be recognized.
"""
import json
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
CORPUS = json.loads((ROOT / "tools/p3b/p3b_scenarios.json").read_text(encoding="utf-8"))
JAVA = (ROOT / "backend/src/test/java/com/specagent/eval/P3BCorpus.java").read_text(
    encoding="utf-8")
SCENARIOS = CORPUS["scenarios"]


def declaration(scenario_id):
    """Read a literal scenario declaration; unsupported syntax is a test failure."""
    start = JAVA.index(f'return scenario("{scenario_id}"')
    end = JAVA.index("));", JAVA.index("new P3BOracle(", start)) + 3
    block = JAVA[start:end]
    actions = re.search(r'expect\(Set\.of\(([^)]*)\),\s*Set\.of\(([^)]*)\)', block)
    oracle = re.search(
        r'new P3BOracle\("(C\d+)",\s*GateExpectation\.(\w+),\s*'
        r'Set\.of\(([^)]*)\),\s*(true|false),\s*(null|"[^"]+")', block)
    assert actions and oracle, f"Unrecognized Java declaration: {scenario_id}"
    quoted = lambda text: set(re.findall(r'"([^"]+)"', text))
    return {
        "acceptable": quoted(actions[1]), "forbidden": quoted(actions[2]),
        "category": oracle[1], "gate": oracle[2], "reasons": quoted(oracle[3]),
        "known_gap": oracle[4] == "true",
        "continuation": None if oracle[5] == "null" else oracle[5].strip('"'),
        "seed": int(re.search(r'variants\((\d+)L\)', block)[1]),
    }


def test_all_eighteen_categories_are_present_once_in_both_sources():
    expected = {f"P3B-C{number:02d}" for number in range(1, 19)}
    ids = [item["id"] for item in SCENARIOS]
    java_ids = re.findall(r'return scenario\("(P3B-C\d+)"', JAVA)
    assert len(ids) == len(set(ids)) == 18
    assert len(java_ids) == len(set(java_ids)) == 18
    assert set(ids) == set(java_ids) == expected
    assert {item["category"] for item in SCENARIOS} == set(CORPUS["capabilityCategories"])


@pytest.mark.parametrize("scenario", SCENARIOS, ids=lambda item: item["id"])
def test_action_sets_and_oracle_match_the_json_candidate_labels(scenario):
    mirror = declaration(scenario["id"])
    assert mirror["category"] == scenario["category"]
    assert mirror["acceptable"] == set(scenario["acceptableActions"])
    assert mirror["forbidden"] == {entry["action"] for entry in scenario["forbiddenActions"]}
    expected_gate = ("NO_DETERMINISTIC_SURFACE"
                     if scenario["deterministicSurface"] == "NONE_SEMANTIC_ONLY"
                     else scenario["expectedGateVerdict"])
    assert mirror["gate"] == expected_gate
    assert mirror["reasons"] == set(scenario.get("expectedVetoReasonCodes", []))
    assert mirror["known_gap"] == scenario.get("knownEligibilityGap", False)
    if mirror["continuation"]:
        assert mirror["continuation"] in scenario["expectedNextCycle"]
    assert scenario["labelReview"] == "PENDING_HUMAN_REVIEW"
    assert scenario["labelProvenance"] == "AI-authored candidate label"


def test_semantic_surfaces_and_external_wait_are_not_scored_as_gate_successes():
    semantic = {item["category"] for item in SCENARIOS
                if item["deterministicSurface"] == "NONE_SEMANTIC_ONLY"}
    assert semantic == {"C03", "C07", "C13", "C15"}
    gap = [item for item in SCENARIOS if item.get("knownEligibilityGap")]
    assert [item["category"] for item in gap] == ["C16"]
    assert gap[0]["acceptableActions"] == ["WAIT"]
    assert gap[0]["expectedGateVerdict"] == "VETO"
    assert gap[0]["expectedVetoReasonCodes"] == ["NO_PENDING_DEPENDENCY"]
    # 36 scripted attempts: 8 semantic-only, 2 known-gap, 26 other gate surfaces.
    assert sum(item["deterministicSurface"] != "NONE_SEMANTIC_ONLY"
               for item in SCENARIOS) * 2 == 28


def test_base_and_holdout_have_distinct_seeds_and_holdout_usage():
    seeds = [declaration(item["id"])["seed"] for item in SCENARIOS]
    assert len({seed + offset for seed in seeds for offset in (0, 1)}) == 36
    variants = JAVA[JAVA.index("private static List<VariantSpec> variants(long seed)"):]
    assert 'VariantSpec.base("base", seed)' in variants
    assert 'VariantSpec.builder("holdout", seed + 1)' in variants
    assert ".paraphraseIndex(1)" in variants
    assert ".shuffleIrrelevantContext(true)" in variants
    assert ".usage(VariantSpec.Usage.HOLDOUT)" in variants
    # These declarations prove split identity, not untouched experimental holdout.
