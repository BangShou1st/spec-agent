"""Brain-side broker timeout classification.

A broker timeout must surface as a distinct error type from an ordinary
provider failure, so the Java engine can map it to a timeout code instead
of misclassifying it as a provider error.
"""

import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from spec_agent_brain.app import create_app
from spec_agent_brain.config import Settings
from spec_agent_brain.model_client import BrokerTimeoutError
from spec_agent_brain.model_client.base import ModelClientError

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"


def _request_payload():
    return json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))


def _settings():
    return Settings(
        internal_secret="test-secret",
        model_mode="broker",
        broker_url="http://broker.invalid/internal/v1/model-inference",
        broker_timeout_seconds=5.0,
    )


def test_broker_timeout_is_distinct_error_type():
    from spec_agent_brain.model_client.broker_client import BrokerModelClient

    class TimeoutClient:
        def post(self, url, json=None, headers=None):
            raise httpx.ReadTimeout("read timed out")

    client = BrokerModelClient(
        broker_url="http://broker.invalid/internal/v1/model-inference",
        internal_secret="test-secret",
        http_client=TimeoutClient(),
    )
    with pytest.raises(BrokerTimeoutError) as raised:
        client.complete("run-1", "STATE_UPDATE", [])
    assert isinstance(raised.value, ModelClientError)


def test_broker_connect_timeout_is_also_timeout():
    from spec_agent_brain.model_client.broker_client import BrokerModelClient

    class ConnectTimeoutClient:
        def post(self, url, json=None, headers=None):
            raise httpx.ConnectTimeout("connect timed out")

    client = BrokerModelClient(
        broker_url="http://broker.invalid/internal/v1/model-inference",
        internal_secret="test-secret",
        http_client=ConnectTimeoutClient(),
    )
    with pytest.raises(BrokerTimeoutError):
        client.complete("run-1", "STATE_UPDATE", [])


def test_broker_generic_http_error_stays_model_client_error():
    from spec_agent_brain.model_client.broker_client import BrokerModelClient

    class GenericErrorClient:
        def post(self, url, json=None, headers=None):
            raise httpx.HTTPError("generic http error")

    client = BrokerModelClient(
        broker_url="http://broker.invalid/internal/v1/model-inference",
        internal_secret="test-secret",
        http_client=GenericErrorClient(),
    )
    with pytest.raises(ModelClientError) as raised:
        client.complete("run-1", "STATE_UPDATE", [])
    assert not isinstance(raised.value, BrokerTimeoutError)


def test_http_boundary_reports_timeout_as_typed_502():
    from unittest.mock import patch
    from spec_agent_brain.model_client.broker_client import BrokerModelClient

    def fake_post(self, url, json=None, headers=None):
        raise httpx.ReadTimeout("read timed out")

    settings = _settings()
    client = TestClient(create_app(settings))
    payload = _request_payload()
    headers = {"X-Spec-Agent-Internal-Token": "test-secret"}

    with patch.object(BrokerModelClient, "complete", side_effect=BrokerTimeoutError("inference broker timeout: ReadTimeout")):
        response = client.post(
            "/v1/decisions", json=payload, headers=headers)

    assert response.status_code == 502
    body = response.json()
    assert body["detail"] == "brain_failure:BrokerTimeoutError"
