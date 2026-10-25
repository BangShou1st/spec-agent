"""文件名:test_engine_contract_diagnostics.py

用途:验证三个引擎(STATE_UPDATE / DECISION / ARTIFACT_GENERATION)的
结构化失败诊断拥有一致的形状、机器码与脱敏边界。

核心不变量:
- 所有可确定成因的模型输出失败都携带 ``ContractDiagnostic``,经 app 层
  以 camelCase 透传到 HTTP 错误体的 ``contractDiagnostic`` 字段;
- 异常消息、诊断字段与响应体都绝不携带模型输出的值(未知字段名、
  section 标题、ref 字符串、pydantic 原文);
- 无诊断可给的失败(传输错误)保留 ``diagnosticStatus=UNKNOWN``,不为
  消灭 UNKNOWN 伪造诊断。
"""

import json
import uuid as uuid_module
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from spec_agent_brain.app import create_app
from spec_agent_brain.artifact import BrainContractError as ArtifactBrainContractError
from spec_agent_brain.artifact import (
    UngroundedReferenceError,
    handle_artifact,
)
from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.decision import ModelOutputContractError, handle_decision
from spec_agent_brain.model_client import Completion, ModelClientError
from spec_agent_brain.state_update import BrainContractError, handle_state_update

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
FOREIGN_REF = "answer:11111111-2222-3333-4444-555555555555"
SECRET_MODEL_TEXT = "SECRET-MODEL-OUTPUT-must-not-escape"


class ScriptedClient:
    """按脚本返回补全内容;脚本可以是多个以支持多轮修复调用。"""

    def __init__(self, *contents: str):
        self._contents = list(contents)

    def complete(self, run_id, call_type, messages, max_output_tokens=2048) -> Completion:
        return Completion(content=self._contents.pop(0), finish_reason="stop")


def _request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))
    return parse_request_envelope(payload)


def _assert_shared_diagnostic_shape(diagnostic):
    """三个引擎的诊断必须共享同一形状与边界语义。"""
    assert diagnostic.boundary == "PYTHON_BRAIN"
    assert diagnostic.java_wire_validator_entered is False
    assert diagnostic.executor_reached is False
    uuid_module.UUID(diagnostic.diagnostic_id)  # 必须是合法 UUID,不抛异常即通过
    assert diagnostic.machine_code  # 机器码非空,供 Java 白名单消费


# --- STATE_UPDATE -----------------------------------------------------------


def test_state_update_invalid_json_carries_contract_diagnostic():
    with pytest.raises(BrainContractError) as raised:
        handle_state_update(_request(), ScriptedClient("not json at all"))

    diagnostic = raised.value.diagnostic
    assert diagnostic.machine_code == "MODEL_OUTPUT_INVALID_JSON"
    assert diagnostic.stage == "brain.model_output_parse"
    assert diagnostic.validator_id == "json.loads"
    assert diagnostic.source_exception_type == "JSONDecodeError"
    _assert_shared_diagnostic_shape(diagnostic)


def test_state_update_schema_violation_carries_safe_field_path():
    poisoned = json.dumps({"claims": [
        {"kind": SECRET_MODEL_TEXT, "text": "t", "status": "confirmed"}]})

    with pytest.raises(BrainContractError) as raised:
        handle_state_update(_request(), ScriptedClient(poisoned))

    diagnostic = raised.value.diagnostic
    assert diagnostic.machine_code == "MODEL_OUTPUT_SCHEMA_INVALID"
    assert diagnostic.stage == "brain.model_output_validation"
    assert diagnostic.validator_id == "ModelStateUpdateOutput"
    assert diagnostic.field_path == "claims[0].kind"
    _assert_shared_diagnostic_shape(diagnostic)
    # 异常消息绝不回显模型选择的字段值。
    assert SECRET_MODEL_TEXT not in str(raised.value)
    assert SECRET_MODEL_TEXT not in repr(raised.value)


def test_state_update_unknown_model_key_never_enters_field_path():
    # 模型自选的未知键可能携带任意内容;fieldPath 必须整体放弃而不是截取。
    poisoned = json.dumps({"claims": [
        {"kind": "goal", "text": "t", "status": "confirmed"},
        {"kind": "goal", "text": "t", "status": "confirmed",
         SECRET_MODEL_TEXT: "x"}]})

    with pytest.raises(BrainContractError) as raised:
        handle_state_update(_request(), ScriptedClient(poisoned))

    assert raised.value.diagnostic.field_path is None
    assert SECRET_MODEL_TEXT not in str(raised.value)


# --- DECISION(共享契约模块回归) ---------------------------------------------


def test_decision_diagnostics_keep_existing_machine_codes():
    with pytest.raises(ModelOutputContractError) as raised:
        handle_decision(_request(), ScriptedClient("not json at all"))

    assert raised.value.diagnostic.machine_code == "MODEL_OUTPUT_INVALID_JSON"
    _assert_shared_diagnostic_shape(raised.value.diagnostic)


# --- ARTIFACT ---------------------------------------------------------------


def _artifact_output(sections) -> str:
    return json.dumps({"artifactType": "spec_snapshot", "sections": sections,
                       "unresolvedItems": []}, ensure_ascii=False)


def test_artifact_invalid_json_carries_contract_diagnostic():
    with pytest.raises(ArtifactBrainContractError) as raised:
        handle_artifact(_request(), ScriptedClient("not json at all"))

    diagnostic = raised.value.diagnostic
    assert diagnostic.machine_code == "MODEL_OUTPUT_INVALID_JSON"
    assert diagnostic.stage == "brain.model_output_parse"
    assert diagnostic.validator_id == "json.loads"
    _assert_shared_diagnostic_shape(diagnostic)


def test_artifact_schema_violation_carries_safe_field_path():
    poisoned = json.dumps({"artifactType": "spec_snapshot",
                           "sections": [{"title": SECRET_MODEL_TEXT}],
                           "unresolvedItems": []})

    with pytest.raises(ArtifactBrainContractError) as raised:
        handle_artifact(_request(), ScriptedClient(poisoned))

    diagnostic = raised.value.diagnostic
    assert diagnostic.machine_code == "MODEL_OUTPUT_SCHEMA_INVALID"
    assert diagnostic.validator_id == "ModelArtifactOutput"
    _assert_shared_diagnostic_shape(diagnostic)
    assert SECRET_MODEL_TEXT not in str(raised.value)


def test_artifact_section_without_refs_reports_machine_code_and_hides_title():
    sections = [{"title": SECRET_MODEL_TEXT, "content": "内容", "sourceRefs": []}]

    with pytest.raises(ArtifactBrainContractError) as raised:
        handle_artifact(_request(), ScriptedClient(_artifact_output(sections)))

    diagnostic = raised.value.diagnostic
    assert diagnostic.machine_code == "MODEL_OUTPUT_SECTION_WITHOUT_SOURCE_REFS"
    assert diagnostic.stage == "brain.output_grounding"
    assert diagnostic.validator_id == "sectionSourceRefPresence"
    assert diagnostic.field_path == "sections[].sourceRefs"
    assert diagnostic.expected_type == "non-empty array"
    assert diagnostic.actual_type == "empty array"
    _assert_shared_diagnostic_shape(diagnostic)
    assert SECRET_MODEL_TEXT not in str(raised.value)


def test_artifact_ungrounded_ref_is_typed_and_hides_the_ref():
    sections = [{"title": "Overview", "content": "内容", "sourceRefs": [FOREIGN_REF]}]

    with pytest.raises(UngroundedReferenceError) as raised:
        handle_artifact(_request(), ScriptedClient(_artifact_output(sections)))

    diagnostic = raised.value.diagnostic
    assert diagnostic.machine_code == "MODEL_OUTPUT_UNGROUNDED_REFERENCE"
    assert diagnostic.stage == "brain.output_grounding"
    assert diagnostic.validator_id == "sectionSourceRefAllowlist"
    assert diagnostic.field_path == "sections[].sourceRefs"
    _assert_shared_diagnostic_shape(diagnostic)
    # 与 decision 引擎同款失败同款机器码:Java 白名单按
    # (UngroundedReferenceError, MODEL_OUTPUT_UNGROUNDED_REFERENCE) 提取。
    assert FOREIGN_REF not in str(raised.value)


# --- HTTP 边界 ---------------------------------------------------------------


def _post(settings, monkeypatch, path, model_script):
    monkeypatch.setattr("spec_agent_brain.app.FakeModelClient",
                        lambda: ScriptedClient(model_script))
    client = TestClient(create_app(settings))
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))
    return client.post(path, json=payload,
                       headers={"X-Spec-Agent-Internal-Token": "test-secret"})


def test_state_update_endpoint_surfaces_structured_diagnostic(settings, monkeypatch):
    response = _post(settings, monkeypatch, "/v1/state-updates", "not json at all")

    assert response.status_code == 502
    body = response.json()
    assert body["detail"] == "brain_failure:BrainContractError"
    assert body["contractDiagnostic"]["machineCode"] == "MODEL_OUTPUT_INVALID_JSON"
    assert body["contractDiagnostic"]["stage"] == "brain.model_output_parse"
    assert body["contractDiagnostic"]["boundary"] == "PYTHON_BRAIN"
    assert "diagnosticStatus" not in body


def test_artifact_endpoint_surfaces_structured_diagnostic(settings, monkeypatch):
    poisoned = _artifact_output(
        [{"title": SECRET_MODEL_TEXT, "content": "c", "sourceRefs": []}])
    response = _post(settings, monkeypatch, "/v1/artifacts", poisoned)

    assert response.status_code == 502
    body = response.json()
    assert body["detail"] == "brain_failure:ArtifactBrainContractError"
    assert body["contractDiagnostic"]["machineCode"] == \
        "MODEL_OUTPUT_SECTION_WITHOUT_SOURCE_REFS"
    assert SECRET_MODEL_TEXT not in response.text
    assert "diagnosticStatus" not in body


def test_transport_failure_keeps_unknown_diagnostic_status(settings, monkeypatch):
    """传输失败不是契约违约:保留 UNKNOWN,不伪造诊断结构。"""

    class UnreachableClient:
        def complete(self, *_args, **_kwargs):
            raise ModelClientError("inference broker unreachable")

    monkeypatch.setattr("spec_agent_brain.app.FakeModelClient", UnreachableClient)
    client = TestClient(create_app(settings))
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))

    response = client.post("/v1/state-updates", json=payload,
                           headers={"X-Spec-Agent-Internal-Token": "test-secret"})

    assert response.status_code == 502
    body = response.json()
    assert body["detail"] == "brain_failure:ModelClientError"
    assert body["diagnosticStatus"] == "UNKNOWN"
    assert "contractDiagnostic" not in body
