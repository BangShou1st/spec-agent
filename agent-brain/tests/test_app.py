"""HTTP surface tests: health, strict request validation, internal token."""

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
