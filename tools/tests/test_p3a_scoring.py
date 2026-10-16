"""文件名:test_p3a_scoring.py

用途:P3-A B 层 Python 计分函数(`tools/p3a/p3a_live_bc_evaluation.py::score_claims`)
的确定性单元测试。

覆盖上次代码审查确认的三类问题:
  * Concept coverage 与 Claim precision 必须分开,且不得用
    `matchedRequired / claims` 伪造 precision;
  * 同一 concept 的条件不得由多个互不相关的 claim 拼凑满足(anti-stitching);
  * 一个 claim 命中多个 concept 时不得被无约束重复计分。

以及既有的 forbidden / 未决确认边界(与语料 p3a_scenarios.json 的字段约定一致)。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from p3a.p3a_live_bc_evaluation import (  # noqa: E402
    concept_coverage,
    score_claims,
)


def claim(text, status="CONFIRMED", kind="fact", node="n1", answer="a1"):
    return {"kind": kind, "text": text, "status": status,
            "sourceNodeId": node, "sourceAnswerId": answer}


def scenario(required=None, forbidden=None, must_not_settle=None):
    return {
        "requiredConcepts": required or [],
        "forbiddenOvergeneralizations": forbidden or [],
        "mustNotSettle": must_not_settle or [],
    }


# 1. 一个 Claim 匹配多个概念：coverage 按 concept 计，claim 只被计一次。 ----
def test_one_claim_matching_several_concepts_counts_concepts_not_claims():
    s = scenario(required=[
        {"id": "response-time", "anyOf": [["响应时间"]]},
        {"id": "four-hours", "anyOf": [["四小时", "4小时"]]},
    ])
    result = score_claims(s, [claim("把售后维修响应时间缩短到四小时以内")])

    assert result["coveredConcepts"] == ["response-time", "four-hours"]
    assert result["conceptCoverage"] == 1.0
    # 该 claim 覆盖两个概念，但只能被计一次——防止无约束重复计分。
    assert result["claimsCoveringAnyConcept"] == 1
    assert result["claimCount"] == 1


# 2. 多个 Claim 重复描述同一概念：概念仍只算一次。 -------------------------
def test_redundant_claims_describing_same_concept_count_concept_once():
    s = scenario(required=[{"id": "offline", "anyOf": [["离线"]]}])
    result = score_claims(s, [
        claim("系统必须完全离线运行"),
        claim("部署要求是离线"),
    ])

    assert result["coveredConcepts"] == ["offline"]
    assert result["conceptCoverage"] == 1.0
    # 两条 claim 都命中，但概念只算一次；命中 claim 数为 2（透明记录）。
    assert result["claimsCoveringAnyConcept"] == 2


# 3. 两个无关 Claim 分别命中不同关键词：不得拼凑满足一个 concept。 -----------
def test_two_unrelated_claims_must_not_stitch_one_concept():
    s = scenario(required=[{"id": "after-sales-repair",
                            "anyOf": [["售后"], ["维修"]]}])
    # claim A 只含"售后"，claim B 只含"维修"——没有任何单条 claim 同时满足两组。
    result = score_claims(s, [claim("要提升售后体验"), claim("需要维修流程")])

    assert result["coveredConcepts"] == []
    assert result["missedRequired"] == ["after-sales-repair"]
    assert result["conceptCoverage"] == 0.0
    # 单条同时含"售后"和"维修"时才算覆盖。
    covered = score_claims(s, [claim("售后维修响应时间要缩短")])
    assert covered["conceptCoverage"] == 1.0


# 4. 正确语义改写：只要命中概念的关键词即可覆盖，不做字符串相等。 -----------
def test_paraphrase_is_accepted():
    s = scenario(required=[{"id": "four-hours", "anyOf": [["四小时", "4小时"]]}])
    result = score_claims(s, [claim("把响应时间压缩到 4 小时以内")])
    assert result["conceptCoverage"] == 1.0


# 5. 错误过度概括：命中 forbidden allOf 记为违规；显式否定则豁免。 -----------
def test_forbidden_overgeneralization_and_negation_exemption():
    s = scenario(forbidden=[{
        "id": "all-need-approval",
        "allOf": ["所有", "审批"],
        "unlessNegated": ["仅", "只有", "不"],
    }])
    flagged = score_claims(s, [claim("所有预约都需要审批")])
    assert len(flagged["forbiddenViolations"]) == 1

    # 显式限定语出现时不算过度概括（撤销/限定是正确行为）。
    exempt = score_claims(s, [claim("只有大型会议室预约需要审批")])
    assert exempt["forbiddenViolations"] == []


# 6. 未解决条件被错误确认：mustNotSettle 的 settling 模式 + CONFIRMED 才算。 --
def test_undecided_content_must_not_be_settled():
    s = scenario(must_not_settle=[{
        "id": "date-fixed",
        "patterns": ["上线日期已确定", "日期已确定"],
    }])
    settled = score_claims(s, [claim("上线日期已确定")])
    assert len(settled["unknownAssertedConfirmed"]) == 1

    # 同一主题但状态为 UNRESOLVED 时不得记作"把未知说成已知"。
    unresolved = score_claims(s, [claim("上线日期已确定", status="UNRESOLVED")])
    assert unresolved["unknownAssertedConfirmed"] == []


# 附加：precision 必须显式标记为未测量，不得伪造。 -------------------------
def test_claim_precision_is_not_fabricated():
    s = scenario(required=[{"id": "offline", "anyOf": [["离线"]]}])
    result = score_claims(s, [claim("系统离线运行"), claim("与需求无关的噪音")])
    assert result["claimPrecision"] is None
    assert result["claimPrecisionStatus"] == \
        "NOT_MEASURED_REQUIRES_HUMAN_CLAIM_LEVEL_LABELS"


def test_concept_coverage_reports_matched_claims():
    s = scenario(required=[{"id": "offline", "anyOf": [["离线"]]}])
    coverage = concept_coverage(s, [claim("离线运行"), claim("保持离线")])
    assert coverage["covered"] == ["offline"]
    assert coverage["coverage"] == 1.0
    assert coverage["matchedBy"] == {"offline": [0, 1]}
