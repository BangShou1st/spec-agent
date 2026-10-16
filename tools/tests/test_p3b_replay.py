"""文件名:test_p3b_replay.py

用途:P3-B 决策协议回放 harness(`tools/p3b/p3b_decision_replay.py`)的确定性测试。

锁定:
  * 每个 fixture 的分类、动作族与 payload 形状结论都必须与声明的期望一致,
    且**可重复**(同一输入两次得到同一结论);
  * Payload / protocol 的契约边界与 INFRA 严格分开:契约违约绝不落到 INFRA;
  * 已记录的脑侧缺口被如实钉住而不是被悄悄抹掉 —— Brain 的 payload 是通用
    map,刻意不做按动作族的形状校验,因此畸形 payload 会一路送到 Java,
    由 Java 的 fail-closed 契约拦截;
  * 唯一被允许的形态漂移仍可归一化(RP-09),而两份不同 refs 列表仍必须
    继续 fail-closed(RP-10)。
"""
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from p3b.p3b_decision_replay import (  # noqa: E402
    ACCEPTED,
    CONTRACT_AMBIGUOUS,
    CONTRACT_CONFLICT,
    CONTRACT_NON_JSON,
    CONTRACT_SCHEMA,
    CONTRACT_UNGROUNDED,
    INFRA,
    classify,
    payload_shape_valid,
    run_all,
)


def report():
    return run_all()


def record(report_payload, fixture_id):
    return next(r for r in report_payload["records"] if r["id"] == fixture_id)


def test_every_fixture_matches_its_declared_expectation():
    payload = report()
    assert payload["fixtureCount"] == 11
    assert payload["outcomeMatched"] == payload["fixtureCount"]
    assert payload["actionMatched"] == payload["fixtureCount"]
    assert payload["payloadMismatches"] == []


def test_replay_is_deterministic_across_runs():
    corpus = report()
    for fixture_id in (r["id"] for r in corpus["records"]):
        assert record(report(), fixture_id)["observedOutcome"] == \
            record(corpus, fixture_id)["observedOutcome"]


def test_contract_violations_never_masquerade_as_infra():
    payload = report()
    assert all(r["observedOutcome"] != INFRA for r in payload["records"])


def test_contract_violation_taxonomy_is_exhaustive_and_distinct():
    payload = report()
    observed = {r["class"]: r["observedOutcome"] for r in payload["records"]}
    assert observed["NON_JSON_OUTPUT"] == CONTRACT_NON_JSON
    assert observed["UNKNOWN_TOP_LEVEL_FIELD"] == CONTRACT_SCHEMA
    assert observed["UNKNOWN_ACTION_FAMILY"] == CONTRACT_SCHEMA
    assert observed["UNGROUNDED_SOURCE_REFERENCE"] == CONTRACT_UNGROUNDED
    assert observed["TWO_DIFFERENT_SOURCE_REF_LISTS"] == CONTRACT_AMBIGUOUS
    assert observed["CONFLICT_NOT_SURFACED"] == CONTRACT_CONFLICT


def test_provider_failure_is_the_only_path_into_infra():
    # INFRA 只有一条可达路径:模型客户端自身失败。契约分类绝不与之混同。
    replayed = classify({
        "id": "P3B-RP-LOCAL-INFRA",
        "modelOutput": "{}",
        "modelClientFailure": "inference broker timeout",
    })
    assert replayed["outcome"] == INFRA
    assert replayed["modelCalls"] == 1


def test_brain_accepts_malformed_per_family_payload_and_java_is_the_authority():
    """钉住已记录的脑侧缺口(本轮**不修复**)。

    Brain 的 ``ModelDecisionAction.payload`` 是有界的通用 map,刻意不做按动作族
    的形状校验,因此 ``questionText`` 为对象、缺失 ``allowFreeAnswer`` 这类畸形
    payload 会在 Brain 边界被**接受**,一路送到 Java;真实差异就定位在这里,
    由 Java 的 ``AgentBrainResponseValidator`` fail-closed 拦截(P3-A B05 的
    'questionText must be a string' 即此路径)。

    这条断言是**有意**记录下来的缺口,而不是通过率:一旦有人给 Brain 加上
    按族形状校验,这里就会失败并迫使基线被显式更新。
    """
    payload = report()
    malformed = record(payload, "P3B-RP-02")
    assert malformed["observedOutcome"] == ACCEPTED
    assert malformed["observedPayloadValid"] is False

    missing_boolean = record(payload, "P3B-RP-03")
    assert missing_boolean["observedOutcome"] == ACCEPTED
    assert missing_boolean["observedPayloadValid"] is False


def test_missing_required_capability_argument_is_not_a_payload_shape_fault():
    """RP-04:传输形状合法(所以不能用形状违约解释),而声明必填参数缺失。

    这一条只能由 Java 的 eligibility 门禁确定性判定(本轮修复引入
    ``MISSING_REQUIRED_CAPABILITY_ARGUMENT``);Brain 既看不到能力描述符,
    也不该在 payload 形状层假装能判它。
    """
    replayed = record(report(), "P3B-RP-04")
    assert replayed["observedOutcome"] == ACCEPTED
    assert replayed["observedPayloadValid"] is True


def test_documented_normalization_shim_still_relocates_equivalent_refs():
    replayed = record(report(), "P3B-RP-09")
    assert replayed["observedOutcome"] == ACCEPTED
    assert replayed["observedActionFamily"] == "RESPOND_TO_USER"


def test_ambiguous_source_ref_lists_still_fail_closed():
    replayed = record(report(), "P3B-RP-10")
    assert replayed["observedOutcome"] == CONTRACT_AMBIGUOUS


def test_payload_shape_check_is_vacuous_when_no_proposal_was_produced():
    # 没有产出提案时 payload 有效性是 NOT_MEASURED(null),绝不能伪造成 false。
    assert payload_shape_valid(None, None) is None
    replayed = record(report(), "P3B-RP-05")
    assert replayed["observedPayloadValid"] is None
