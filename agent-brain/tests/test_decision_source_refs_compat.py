"""文件名:test_decision_source_refs_compat.py

用途:解析层对唯一一个已记录在案的 DECISION 字段偏差的兼容性测试。

模型偶尔会把 ``sourceRefs`` 从 ``action`` 里提升到自己 JSON 对象的顶层。
这些测试钉死确切的兼容边界:

- 顶层只有一份*合法*列表时会被搬移,但仅当 ``action`` 完全省略该字段
  (看键是否存在,而不是看值是否为真,来判定"省略");
- 两份相同的合法列表合并为一份;
- 两份不同的合法列表——包括 action 是空列表而顶层非空——被拒绝,绝不
  覆盖、合并或取并集;
- ``action.sourceRefs`` 存在但非法(``null``、``false``、``0``、``""``、
  非字符串数组、对象)时被拒绝,*绝不*被顶层副本修复;
- 任何未知内容仍然 fail-closed,搬移过来的列表与放对位置的列表接受
  完全相同的校验;
- 所有诊断保持有界:任何无界的模型输出都不会进入日志或有类型的错误
  detail。
"""

import json
from pathlib import Path

import pytest

from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.decision import (
    AmbiguousSourceRefsError,
    BrainContractError,
    UngroundedReferenceError,
    handle_decision,
)
from spec_agent_brain.model_client import Completion

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"

ALLOWED_REF = "answer:99999999-9999-9999-9999-999999999999"
OTHER_ALLOWED_REF = "node:33333333-3333-3333-3333-333333333333"
FOREIGN_REF = "answer:11111111-2222-3333-4444-555555555555"

UNSET = "unset"


class ScriptedClient:
    """返回一个预设的补全;所有解析都由 brain 负责。"""

    def __init__(self, content: str):
        self._content = content

    def complete(self, run_id, call_type, messages, max_output_tokens=2048) -> Completion:
        return Completion(content=self._content, finish_reason="stop")


def _request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))
    return parse_request_envelope(payload)


def _v3_request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-v3-valid.json").read_text(encoding="utf-8"))
    return parse_request_envelope(payload)


def _decision_output(action_refs=UNSET, top_level_refs=UNSET, **extra) -> str:
    """构造一份原始模型输出;``unset`` 表示"键不存在"。

    因此 ``action_refs=None`` 会输出显式的 ``"sourceRefs": null``——
    这是一个*存在但非法*的值,与键缺失是两回事。
    """
    action = {
        "actionFamily": "REQUEST_USER_INPUT",
        "payload": {
            "questionText": "最重要的成果是什么？",
            "purpose": "澄清主要目标。",
            "options": [{"label": "明确主要目标"}],
            "allowFreeAnswer": True,
        },
    }
    if action_refs is not UNSET:
        action["sourceRefs"] = action_refs
    raw = {
        "observation": {
            "known": ["用户澄清了主要成果。"],
            "unknowns": [],
            "conflicts": [],
            "risks": [],
        },
        "action": action,
    }
    if top_level_refs is not UNSET:
        raw["sourceRefs"] = top_level_refs
    raw.update(extra)
    return json.dumps(raw, ensure_ascii=False)


# --- 会被归一的形态 ---------------------------------------------------------


def test_top_level_source_refs_duplicating_the_action_refs_is_dropped():
    content = _decision_output(action_refs=[ALLOWED_REF], top_level_refs=[ALLOWED_REF])

    response = handle_decision(_request(), ScriptedClient(content))

    assert response.action_proposal.source_refs == [ALLOWED_REF]


def test_identical_duplicate_is_dropped_even_with_a_longer_list():
    refs = [ALLOWED_REF, OTHER_ALLOWED_REF]
    content = _decision_output(action_refs=refs, top_level_refs=list(refs))

    response = handle_decision(_request(), ScriptedClient(content))

    assert response.action_proposal.source_refs == refs


def test_identical_empty_lists_drop_the_top_level_duplicate():
    content = _decision_output(action_refs=[], top_level_refs=[])

    response = handle_decision(_request(), ScriptedClient(content))

    assert response.action_proposal.source_refs == []


def test_lone_top_level_source_refs_is_relocated_into_the_action():
    content = _decision_output(top_level_refs=[ALLOWED_REF])

    response = handle_decision(_request(), ScriptedClient(content))

    # 搬移生效:refs 变成 action 自己的,信封里恰好出现一次。
    assert response.action_proposal.source_refs == [ALLOWED_REF]


def test_relocated_refs_still_pass_the_v3_eligibility_evidence_check():
    request = _v3_request()
    allowed = request.snapshot.allowed_source_refs[0]
    content = _decision_output(top_level_refs=[allowed])

    response = handle_decision(request, ScriptedClient(content))

    assert response.action_proposal.source_refs == [allowed]
    assert response.eligibility_evidence_refs == [allowed]


def test_relocated_refs_outside_allowed_refs_are_still_rejected():
    content = _decision_output(top_level_refs=[FOREIGN_REF])

    with pytest.raises(UngroundedReferenceError):
        handle_decision(_request(), ScriptedClient(content))


# --- 会被拒绝的形态 ---------------------------------------------------------


def test_two_different_source_refs_lists_are_rejected_and_never_merged():
    content = _decision_output(action_refs=[ALLOWED_REF], top_level_refs=[OTHER_ALLOWED_REF])

    with pytest.raises(AmbiguousSourceRefsError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    message = str(raised.value)
    # 两份列表都被上报,保证冲突可诊断;并且绝不产生并集或覆盖。
    assert ALLOWED_REF in message
    assert OTHER_ALLOWED_REF in message


def test_conflicting_lists_are_rejected_even_when_both_are_allowed():
    content = _decision_output(
        action_refs=[ALLOWED_REF, OTHER_ALLOWED_REF], top_level_refs=[ALLOWED_REF])

    with pytest.raises(AmbiguousSourceRefsError):
        handle_decision(_request(), ScriptedClient(content))


def test_empty_action_refs_against_non_empty_top_level_refs_is_rejected():
    # 一个存在但为空的 action 列表*不是*省略字段:顶层的列表绝不能覆盖它,
    # 所以直接拒绝输出,而不是让 action 悄悄获得它本来没有携带的 refs。
    content = _decision_output(action_refs=[], top_level_refs=[ALLOWED_REF])

    with pytest.raises(AmbiguousSourceRefsError):
        handle_decision(_request(), ScriptedClient(content))


def test_non_empty_action_refs_against_empty_top_level_refs_is_rejected():
    content = _decision_output(action_refs=[ALLOWED_REF], top_level_refs=[])

    with pytest.raises(AmbiguousSourceRefsError):
        handle_decision(_request(), ScriptedClient(content))


@pytest.mark.parametrize(
    "illegal",
    [None, False, 0, "", {}, {"ref": ALLOWED_REF}],
    ids=["null", "false", "zero", "empty-string", "object", "ref-object"],
)
def test_present_but_illegal_action_refs_is_rejected_and_never_patched(illegal):
    # 字段存在,所以 action 并没有"省略";一个 falsy 或类型错误的值不能
    # 被当成缺失然后从顶层回填。它仍然按普通契约违规 fail-closed。
    content = _decision_output(action_refs=illegal, top_level_refs=[ALLOWED_REF])

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    assert not isinstance(raised.value, AmbiguousSourceRefsError)
    assert "sourceRefs" in str(raised.value)


@pytest.mark.parametrize(
    "illegal_list",
    [[1, 2], [ALLOWED_REF, 3], [None], [[]]],
    ids=["numbers", "mixed", "nulls", "nested"],
)
def test_action_refs_with_non_string_entries_is_rejected_and_never_patched(illegal_list):
    content = _decision_output(action_refs=illegal_list, top_level_refs=[ALLOWED_REF])

    with pytest.raises(BrainContractError):
        handle_decision(_request(), ScriptedClient(content))


def test_action_refs_with_non_string_entries_is_rejected_even_when_identical():
    # 两侧逐字节相同,但都不是合法的 ref 列表:垫片只归一合法形态,所以
    # 这里仍然 fail-closed,而不是被当成"重复"接受。
    illegal = [1, 2]
    content = _decision_output(action_refs=illegal, top_level_refs=list(illegal))

    with pytest.raises(BrainContractError):
        handle_decision(_request(), ScriptedClient(content))


def test_unknown_top_level_field_is_still_rejected():
    content = _decision_output(action_refs=[ALLOWED_REF], mysteryField=True)

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    assert "mysteryField" in str(raised.value)


def test_non_list_top_level_source_refs_is_still_rejected():
    content = _decision_output(top_level_refs=ALLOWED_REF)

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    assert "sourceRefs" in str(raised.value)


def test_ungrounded_action_ref_is_typed_separately_from_a_malformed_output():
    content = _decision_output(action_refs=[FOREIGN_REF])

    with pytest.raises(UngroundedReferenceError):
        handle_decision(_request(), ScriptedClient(content))


# --- 有界诊断 ---------------------------------------------------------------


def test_contract_failure_diagnostic_reports_layout_without_model_content():
    # 一个畸形的 *action*(未知 family)同时携带一份游离的顶层列表:该失败
    # 绝不能被这个垫片修复,且诊断只能描述布局。
    content = json.dumps({
        "observation": {
            "known": ["敏感内容不应进入日志"],
            "unknowns": [], "conflicts": [], "risks": [],
        },
        "action": {"actionFamily": "NOT_A_FAMILY", "payload": {}},
        "sourceRefs": [ALLOWED_REF],
    }, ensure_ascii=False)

    with pytest.raises(BrainContractError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    message = str(raised.value)
    assert "layout=keys(" in message
    assert "topLevelSourceRefs=" in message
    assert "actionSourceRefs=" in message
    # 有界诊断:模型自己的文本绝不能泄漏进错误信息。
    assert "敏感内容不应进入日志" not in message


def test_ambiguous_source_refs_diagnostic_is_length_bounded():
    # refs 同样是模型输出:超长的条目必须被截断,而不是整段涌进日志行或
    # 有类型的错误 detail。
    oversized = "r" * 8000
    content = _decision_output(
        action_refs=[oversized], top_level_refs=["s" * 8000])

    with pytest.raises(AmbiguousSourceRefsError) as raised:
        handle_decision(_request(), ScriptedClient(content))

    message = str(raised.value)
    assert len(message) < 2000
    assert oversized not in message
    assert "..." in message
