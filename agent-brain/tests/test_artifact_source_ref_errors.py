"""文件名:test_artifact_source_ref_errors.py

用途:验证 ARTIFACT_GENERATION 的 grounding 失败被分类成独立的异常类型。

越界引用是路由隔离门禁在拒绝跨路由引用——与模型响应损坏是不同的失败——
Runtime 必须能把两者区分开。完全没有引用的 section 仍然是普通的契约错误。
"""

import json
from pathlib import Path

import pytest

from spec_agent_brain.artifact import BrainContractError as ArtifactBrainContractError
from spec_agent_brain.artifact import UngroundedReferenceError, handle_artifact
from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.model_client import Completion
from spec_agent_brain.prompts import artifact as artifact_prompt

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
FOREIGN_REF = "answer:11111111-2222-3333-4444-555555555555"


class ScriptedClient:
    def __init__(self, content: str):
        self._content = content

    def complete(self, run_id, call_type, messages, max_output_tokens=2048) -> Completion:
        return Completion(content=self._content, finish_reason="stop")


def _request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))
    return parse_request_envelope(payload)


def _artifact_output(source_refs):
    return json.dumps({
        "artifactType": "spec_snapshot",
        "sections": [{"title": "Overview", "content": "内容", "sourceRefs": source_refs}],
        "unresolvedItems": [],
    }, ensure_ascii=False)


def test_section_citing_a_foreign_ref_reports_an_ungrounded_reference():
    with pytest.raises(UngroundedReferenceError):
        handle_artifact(_request(), ScriptedClient(_artifact_output([FOREIGN_REF])))


def test_ungrounded_reference_is_still_an_artifact_contract_error():
    assert issubclass(UngroundedReferenceError, ArtifactBrainContractError)


def test_section_without_references_stays_a_plain_contract_error():
    with pytest.raises(ArtifactBrainContractError) as raised:
        handle_artifact(_request(), ScriptedClient(_artifact_output([])))

    assert not isinstance(raised.value, UngroundedReferenceError)


def test_artifact_prompt_pins_the_allowed_refs_boundary():
    assert "只能引用输入中 allowedSourceRefs 列出的引用" in artifact_prompt.SYSTEM_PROMPT
    assert "其它路线、其它项目、以及本快照未列出的任何 id 都不在其中" in artifact_prompt.SYSTEM_PROMPT
    assert "找不到依据的结论应写进 unresolvedItems" in artifact_prompt.SYSTEM_PROMPT
