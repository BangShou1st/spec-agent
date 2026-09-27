"""文件名:test_app.py

用途:HTTP 接口层测试:health 端点、严格的请求校验、内部 token 鉴权。
"""

import copy
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from spec_agent_brain.app import create_app

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"


def _client(settings):
    return TestClient(create_app(settings))


def _request_payload() -> dict:
    return json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))


def _v3_request_payload() -> dict:
    return json.loads(
        (FIXTURES_DIR / "agent-input-v3-valid.json").read_text(encoding="utf-8"))


def test_health_reports_protocol_and_mode(settings):
    response = _client(settings).get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["protocolVersion"] == "agent-input.v2"
    assert body["modelMode"] == "fake"
    assert body["invocations"] == {
        "stateUpdates": 0,
        "decisions": 0,
        "lastStateUpdateRunId": None,
        "lastDecisionRunId": None,
    }


def test_health_reports_safe_python_invocation_evidence(settings):
    client = _client(settings)
    payload = _request_payload()
    headers = {"X-Spec-Agent-Internal-Token": "test-secret"}

    before = client.get("/health").json()["invocations"]
    assert before["stateUpdates"] == 0
    assert before["decisions"] == 0

    assert client.post("/v1/state-updates", json=payload, headers=headers).status_code == 200
    assert client.post("/v1/decisions", json=payload, headers=headers).status_code == 200

    after = client.get("/health").json()["invocations"]
    assert after["stateUpdates"] == 1
    assert after["decisions"] == 1
    assert after["lastStateUpdateRunId"] == payload["runId"]
    assert after["lastDecisionRunId"] == payload["runId"]


def test_decisions_endpoint_returns_valid_envelope_with_fake_model(settings):
    response = _client(settings).post(
        "/v1/decisions", json=_request_payload(),
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})
    assert response.status_code == 200
    body = response.json()
    assert body["protocolVersion"] == "agent-decision.v2"
    assert body["actionProposal"]["actionFamily"] == "REQUEST_USER_INPUT"
    # 信封必须回显 runtime 持有的 base context,绝不允许凭空编造一个。
    payload = _request_payload()
    assert body["actionProposal"]["baseContextSnapshotId"] == \
        payload["snapshot"]["snapshotId"]
    assert body["actionProposal"]["baseContextHash"] == payload["snapshot"]["contextHash"]


def test_decisions_endpoint_supports_strict_v3_eligibility_contract(settings):
    payload = _v3_request_payload()
    response = _client(settings).post(
        "/v1/decisions", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})

    assert response.status_code == 200
    body = response.json()
    assert body["protocolVersion"] == "agent-decision.v3"
    assert body["actionProposal"]["actionFamily"] == "REQUEST_USER_INPUT"
    assert body["selectedEligibilityVersion"] == "action-eligibility.v1"
    assert body["selectedEligibilityBasisHash"] == \
        payload["actionEligibility"]["basisHash"]


def test_v3_ineligible_model_selection_returns_typed_conflict(settings):
    payload = _v3_request_payload()
    payload["actionEligibility"]["eligibleFamilies"].remove("REQUEST_USER_INPUT")
    payload["actionEligibility"]["constraints"]["REQUEST_USER_INPUT"] = {
        "eligible": False,
        "reasonCodes": ["RESOLVED_BLOCKER"],
    }

    response = _client(settings).post(
        "/v1/decisions", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})

    assert response.status_code == 409
    assert response.json()["detail"] == "ACTION_INELIGIBLE"


def test_state_updates_endpoint_returns_claims_with_fake_model(settings):
    response = _client(settings).post(
        "/v1/state-updates", json=_request_payload(),
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})
    assert response.status_code == 200
    body = response.json()
    assert body["stateUpdate"]["claims"][0]["kind"] == "goal"
    assert body["actionProposal"] is None


def test_wrong_internal_token_is_rejected(settings):
    response = _client(settings).post(
        "/v1/decisions", json=_request_payload(),
        headers={"X-Spec-Agent-Internal-Token": "wrong"})
    assert response.status_code == 401


def test_missing_internal_token_is_rejected(settings):
    response = _client(settings).post("/v1/decisions", json=_request_payload())
    assert response.status_code == 401


def test_unknown_field_in_request_is_rejected(settings):
    payload = _request_payload()
    payload["mysteryField"] = True
    response = _client(settings).post(
        "/v1/decisions", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})
    assert response.status_code == 422


def test_unknown_protocol_version_is_rejected(settings):
    payload = _request_payload()
    payload["protocolVersion"] = "agent-input.v3"
    response = _client(settings).post(
        "/v1/state-updates", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})
    assert response.status_code == 422


def test_no_provider_key_material_anywhere_in_responses(settings):
    client = _client(settings)
    for path in ("/health",):
        body = client.get(path).text
        assert "sk-" not in body
        assert "apiKey" not in body
    for path in ("/v1/state-updates", "/v1/decisions"):
        body = client.post(
            path, json=_request_payload(),
            headers={"X-Spec-Agent-Internal-Token": "test-secret"}).text
        assert "sk-" not in body
        assert "apiKey" not in body


def test_routeless_node_query_decision_endpoint_accepts_fake_model(settings):
    """HTTP 边界必须接受无路由的 NODE_QUERY,而不是返回 422。

    这证明 FastAPI 请求解析器、严格的 Pydantic 信封以及 fake-model 的
    decision 路径对无路由的线上形态达成一致。Java 发送的就是这个 payload;
    这里若出现 contract_violation 就意味着真实的集成断裂,而不只是
    单元测试失败。
    """
    payload = _load_fixture("agent-input-routeless-node-query-valid.json")
    response = _client(settings).post(
        "/v1/decisions", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})

    assert response.status_code != 422, response.text
    assert response.status_code == 200, response.text
    body = response.json()
    # 响应必须遵循既有的 fake decision 契约,而不是 502。
    assert body["protocolVersion"] == "agent-decision.v2"
    assert body["actionProposal"] is not None
    assert body["actionProposal"]["baseContextSnapshotId"] == \
        payload["snapshot"]["snapshotId"]
    assert body["actionProposal"]["baseContextHash"] == \
        payload["snapshot"]["contextHash"]
    # 且必须回显可信的 run id,绝不允许伪造。
    assert body["runId"] == payload["runId"]


def test_non_node_query_with_null_route_id_is_422(settings):
    """真实 HTTP 边界必须在非 NODE_QUERY 事件上拒绝 route id 泄漏。

    收窄后的无路由契约只允许 NODE_QUERY 携带 null route id。试图泄漏
    null route id 的 ``STATE_UPDATE`` / ``ANSWER_SUBMITTED`` /
    ``CONTINUE`` / ``INITIAL`` 信封必须在 FastAPI 边缘变成 422
    contract_violation,绝不能被悄悄接受。
    """
    payload = copy.deepcopy(_request_payload())
    payload["snapshot"]["routeId"] = None
    payload["snapshot"]["routeContext"]["routeId"] = None
    response = _client(settings).post(
        "/v1/decisions", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})
    assert response.status_code == 422
    assert response.json()["detail"] == "contract_violation"


def test_decisions_endpoint_accepts_semantic_node_query_fixture(settings):
    """真实 HTTP 边界必须接受语义 NODE_QUERY fixture。

    fixture 携带受控的 1-hop relations 与带正文的 relatedNodes;若在
    FastAPI 边缘出现 contract_violation,断裂的将是生产环境的
    Java -> Python 请求链路,而不只是一个单元测试。
    """
    payload = _load_fixture("agent-input-node-query-semantic-context-valid.json")
    response = _client(settings).post(
        "/v1/decisions", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})

    assert response.status_code != 422, response.text
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["protocolVersion"] == "agent-decision.v2"
    assert body["actionProposal"] is not None
    # 响应必须精确回显该语义查询的 base context。
    assert body["actionProposal"]["baseContextSnapshotId"] == \
        payload["snapshot"]["snapshotId"]
    assert body["actionProposal"]["baseContextHash"] == payload["snapshot"]["contextHash"]
    assert body["runId"] == payload["runId"]


def _load_fixture(name: str) -> dict:
    return json.loads((FIXTURES_DIR / name).read_text(encoding="utf-8"))
