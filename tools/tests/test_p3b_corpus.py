"""文件名:test_p3b_corpus.py

用途:P3-B 人工可审语料(`tools/p3b/p3b_scenarios.json`)的结构与诚实性守卫。

它锁定任务书对每个样本要求的**全部记录字段**、可接受/禁止动作集合互斥、
16 个必备类别全部被覆盖、以及三条诚实性不变量:

  * 所有标签都是 `PENDING_HUMAN_REVIEW`(AI 候选标签,不冒充人工验收);
  * 有确定性判据的场景必须声明期望的门禁裁决;没有判据的场景必须显式声明
    `NONE_SEMANTIC_ONLY`,而不是假装可判;
  * 真实模型状态为 `BLOCKED`,并且在语料里写明原因。

同时守卫"人工可审语料"与"可执行镜像"(`backend` 里的 `P3BCorpus`)不会悄悄漂移:
两边必须使用同一组场景 id。
"""
import json
import re
from pathlib import Path

CORPUS = Path(__file__).resolve().parents[1] / "p3b" / "p3b_scenarios.json"

REQUIRED_SCENARIO_FIELDS = (
    "id", "category", "domain", "userIntent", "knownFacts", "blockers",
    "eligibleFamilies", "acceptableActions", "forbiddenActions",
    "payloadConditions", "expectedNextCycle", "labelProvenance", "labelReview",
    "deterministicSurface",
)

EXPECTED_CATEGORIES = {f"C{n:02d}" for n in range(1, 17)}

JAVA_MIRROR = (Path(__file__).resolve().parents[2] / "backend" / "src" / "test"
               / "java" / "com" / "specagent" / "eval" / "P3BCorpus.java")


def corpus():
    return json.loads(CORPUS.read_text(encoding="utf-8"))


def test_corpus_shape_and_label_honesty():
    payload = corpus()
    assert payload["version"] == "p3b-scenarios.v1"
    assert payload["humanReviewStatus"] == "PENDING_HUMAN_REVIEW"
    assert payload["developmentUse"] == "DEVELOPMENT_CALIBRATION"
    assert payload["realModelStatus"] == "BLOCKED"
    assert payload["realModelStatusReason"].strip() != ""


def test_every_scenario_carries_every_required_field():
    for scenario in corpus()["scenarios"]:
        missing = [field for field in REQUIRED_SCENARIO_FIELDS if field not in scenario]
        assert not missing, f"{scenario['id']} missing {missing}"
        assert scenario["labelReview"] == "PENDING_HUMAN_REVIEW", scenario["id"]
        assert scenario["labelProvenance"] == "AI-authored candidate label", scenario["id"]


def test_acceptable_and_forbidden_actions_never_overlap():
    for scenario in corpus()["scenarios"]:
        acceptable = set(scenario["acceptableActions"])
        forbidden = {entry["action"] for entry in scenario["forbiddenActions"]}
        assert not acceptable & forbidden, scenario["id"]
        assert all(entry["reason"].strip() for entry in scenario["forbiddenActions"]), \
            scenario["id"]


def test_all_sixteen_required_categories_are_covered():
    covered = {scenario["category"] for scenario in corpus()["scenarios"]}
    assert EXPECTED_CATEGORIES <= covered


def test_deterministic_surface_is_declared_and_never_faked():
    for scenario in corpus()["scenarios"]:
        surface = scenario["deterministicSurface"]
        assert surface in {"GATE_PASS_EXPECTED", "GATE_VETO_EXPECTED",
                           "NONE_SEMANTIC_ONLY",
                           "KNOWN_GAP_GATE_VETO_EXPECTED"}, scenario["id"]
        if surface == "NONE_SEMANTIC_ONLY":
            # 没有确定性判据的场景必须显式说明缺口,而不是留空。
            assert scenario.get("notes", "").strip() != "", scenario["id"]
            assert "expectedGateVerdict" not in scenario, scenario["id"]
        else:
            assert scenario["expectedGateVerdict"] in {"PASS", "VETO"}, scenario["id"]


def test_known_gaps_are_declared_as_known():
    known = [s for s in corpus()["scenarios"] if s.get("knownEligibilityGap")]
    assert known, "至少 WAIT 的表示缺口必须被显式记录"
    for scenario in known:
        assert scenario["deterministicSurface"] == "KNOWN_GAP_GATE_VETO_EXPECTED"
        assert scenario["notes"].strip() != ""


def test_human_reviewable_corpus_and_executable_mirror_share_the_same_ids():
    assert JAVA_MIRROR.is_file(), "可执行镜像必须存在"
    java_source = JAVA_MIRROR.read_text(encoding="utf-8")
    java_ids = set(re.findall(r'scenario\("(P3B-C\d+)"', java_source))
    corpus_ids = {scenario["id"] for scenario in corpus()["scenarios"]}
    assert java_ids == corpus_ids
