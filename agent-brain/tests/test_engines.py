"""Engine tests: deterministic fake path, strict model-output parsing, and
source-ref containment before a response ever leaves the brain."""

import json
from pathlib import Path

import httpx
import pytest

from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.decision import BrainContractError, handle_decision
from spec_agent_brain.model_client import ChatMessage, Completion, FakeModelClient, ModelClientError
from spec_agent_brain.model_client import fake as fake_model
from spec_agent_brain.model_client.broker_client import BrokerModelClient
from spec_agent_brain.state_update import handle_state_update

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
RUN_ID = "22222222-2222-2222-2222-222222222222"


def _request():
    payload = json.loads(
        (FIXTURES_DIR / "agent-input-valid.json").read_text(encoding="utf-8"))
    return parse_request_envelope(payload)


class ScriptedClient:
    def __init__(self, content: str):
        self._content = content

    def complete(self, run_id, call_type, messages, max_output_tokens=2048) -> Completion:
        return Completion(content=self._content, finish_reason="stop")


def test_state_update_fake_path_is_deterministic():
    request = _request()
    response = handle_state_update(request, FakeModelClient())
    assert str(response.run_id) == RUN_ID
    assert response.state_update.claims[0].status == "confirmed"
    assert response.usage.model_calls == 1


def test_decision_fake_path_stamps_runtime_owned_base_context():
    request = _request()
    response = handle_decision(request, FakeModelClient())
    proposal = response.action_proposal
    assert proposal.action_family == "REQUEST_USER_INPUT"
    assert str(proposal.base_context_snapshot_id) == str(request.snapshot.snapshot_id)
    assert proposal.base_context_hash == request.snapshot.context_hash


def test_decision_model_inventing_source_ref_is_rejected():
    request = _request()
    output = json.loads(fake_model.DECISION_OUTPUT)
    output["action"]["sourceRefs"] = ["node:00000000-0000-0000-0000-000000000000"]
    with pytest.raises(BrainContractError):
        handle_decision(request, ScriptedClient(json.dumps(output)))


def test_decision_model_output_with_unknown_field_is_rejected():
    request = _request()
    output = json.loads(fake_model.DECISION_OUTPUT)
    output["mystery"] = True
    with pytest.raises(BrainContractError):
        handle_decision(request, ScriptedClient(json.dumps(output)))


def test_decision_model_output_with_unknown_action_family_is_rejected():
    request = _request()
    output = json.loads(fake_model.DECISION_OUTPUT)
    output["action"]["actionFamily"] = "MARK_RISK"
    with pytest.raises(BrainContractError):
        handle_decision(request, ScriptedClient(json.dumps(output)))


def test_non_json_model_output_is_rejected():
    request = _request()
    with pytest.raises(BrainContractError):
        handle_decision(request, ScriptedClient("not json at all"))


def test_broker_client_sends_internal_token_and_no_api_key():
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["token"] = request.headers.get("X-Spec-Agent-Internal-Token")
        captured["body"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(200, json={
            "protocolVersion": "model-inference.v1",
            "content": "{}", "finishReason": "stop",
            "usage": {"promptTokens": 1, "completionTokens": 1},
        })

    client = BrokerModelClient(
        "http://broker.invalid/internal/v1/model-inference", "shared-secret",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)))
    completion = client.complete(
        RUN_ID, "DECISION",
        [ChatMessage(role="system", content="s"), ChatMessage(role="user", content="u")])

    assert captured["token"] == "shared-secret"
    assert captured["body"]["callType"] == "DECISION"
    assert "apiKey" not in json.dumps(captured["body"])
    assert completion.finish_reason == "stop"


def test_broker_client_error_status_raises_typed_error_without_retry():
    calls = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        return httpx.Response(502, json={"error": "provider"})

    client = BrokerModelClient(
        "http://broker.invalid/internal/v1/model-inference", "shared-secret",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)))
    with pytest.raises(ModelClientError):
        client.complete(RUN_ID, "STATE_UPDATE", [ChatMessage(role="user", content="u")])
    assert calls["count"] == 1  # no hidden retry
