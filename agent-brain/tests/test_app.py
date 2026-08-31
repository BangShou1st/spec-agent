"""HTTP surface tests: health, strict request validation, internal token."""

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


def test_health_reports_protocol_and_mode(settings):
    response = _client(settings).get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["protocolVersion"] == "agent-input.v2"
    assert body["modelMode"] == "fake"


def test_decisions_endpoint_returns_valid_envelope_with_fake_model(settings):
    response = _client(settings).post(
        "/v1/decisions", json=_request_payload(),
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})
    assert response.status_code == 200
    body = response.json()
    assert body["protocolVersion"] == "agent-decision.v2"
    assert body["actionProposal"]["actionFamily"] == "REQUEST_USER_INPUT"
    # The envelope must echo the runtime-owned base context, never invent one.
    payload = _request_payload()
    assert body["actionProposal"]["baseContextSnapshotId"] == \
        payload["snapshot"]["snapshotId"]
    assert body["actionProposal"]["baseContextHash"] == payload["snapshot"]["contextHash"]


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
    """The HTTP boundary must accept a routeless NODE_QUERY without 422.

    This proves the FastAPI request parser, the strict Pydantic envelope, and
    the fake-model decision path all agree on the routeless wire shape. Java
    sends exactly this payload; a contract_violation here would mean a real
    integration break, not just a unit-test failure.
    """
    payload = _load_fixture("agent-input-routeless-node-query-valid.json")
    response = _client(settings).post(
        "/v1/decisions", json=payload,
        headers={"X-Spec-Agent-Internal-Token": "test-secret"})

    assert response.status_code != 422, response.text
    assert response.status_code == 200, response.text
    body = response.json()
    # The response must follow the existing fake decision contract, not 502.
    assert body["protocolVersion"] == "agent-decision.v2"
    assert body["actionProposal"] is not None
    assert body["actionProposal"]["baseContextSnapshotId"] == \
        payload["snapshot"]["snapshotId"]
    assert body["actionProposal"]["baseContextHash"] == \
        payload["snapshot"]["contextHash"]
    # And it must echo the trusted run id, never a fabricated one.
    assert body["runId"] == payload["runId"]


def test_non_node_query_with_null_route_id_is_422(settings):
    """The real HTTP boundary must reject route-id-bleed on non-NODE_QUERY.

    The narrow routeless contract only admits null route ids for NODE_QUERY.
    A ``STATE_UPDATE`` / ``ANSWER_SUBMITTED`` / ``CONTINUE`` / ``INITIAL``
    envelope that tries to leak a null route id must be turned into a 422
    contract_violation at the FastAPI edge, not silently accepted.
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
    """The real HTTP boundary must accept the semantic NODE_QUERY fixture.

    The fixture carries bounded 1-hop relations + relatedNodes with body
    content; a contract_violation at the FastAPI edge would break the
    production Java -> Python request path, not just a unit test.
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
    # The response must echo the semantic query's base context exactly.
    assert body["actionProposal"]["baseContextSnapshotId"] == \
        payload["snapshot"]["snapshotId"]
    assert body["actionProposal"]["baseContextHash"] == payload["snapshot"]["contextHash"]
    assert body["runId"] == payload["runId"]


def _load_fixture(name: str) -> dict:
    return json.loads((FIXTURES_DIR / name).read_text(encoding="utf-8"))
