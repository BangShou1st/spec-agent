import json
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest

from spec_agent_brain.model_client.base import ChatMessage, ModelClientError
from spec_agent_brain.model_client.broker_client import BrokerModelClient


def test_missing_execution_grant_never_contacts_broker():
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={"content": "{}", "finishReason": "stop"})

    client = BrokerModelClient("http://broker.invalid", "service-secret",
                               http_client=httpx.Client(transport=httpx.MockTransport(handler)))
    with pytest.raises(ModelClientError, match="execution grant is required"):
        client.complete("run", "DECISION", [ChatMessage(role="user", content="prompt")])
    assert calls == []


def test_concurrent_request_clients_keep_their_own_grant_and_never_put_it_in_body():
    def invoke(index):
        grant = ("a" if index == 0 else "b") * 43

        def handler(request):
            body = json.loads(request.content)
            assert request.headers["X-Spec-Agent-Execution-Grant"] == grant
            assert request.headers["X-Spec-Agent-Internal-Token"] == "service-secret"
            assert body["runId"] == f"run-{index}"
            assert grant not in request.content.decode()
            return httpx.Response(200, json={"content": "{}", "finishReason": "stop"})

        with httpx.Client(transport=httpx.MockTransport(handler)) as http:
            client = BrokerModelClient("http://broker.invalid", "service-secret",
                                       http_client=http, execution_grant=grant)
            return client.complete(f"run-{index}", "DECISION", [ChatMessage(role="user", content="prompt")])

    with ThreadPoolExecutor(max_workers=2) as executor:
        assert all(result.finish_reason == "stop" for result in executor.map(invoke, [0, 1]))


def test_fastapi_request_forwards_only_its_own_execution_grant(monkeypatch, settings):
    import dataclasses
    from pathlib import Path
    from fastapi.testclient import TestClient
    import spec_agent_brain.app as module
    from spec_agent_brain.model_client.fake import FakeModelClient

    seen = []

    def handler(request):
        body = json.loads(request.content)
        seen.append(request.headers["X-Spec-Agent-Execution-Grant"])
        result = FakeModelClient().complete(body["runId"], body["callType"],
                    [ChatMessage(**message) for message in body["messages"]], body["maxOutputTokens"])
        return httpx.Response(200, json={"content": result.content, "finishReason": result.finish_reason})

    with httpx.Client(transport=httpx.MockTransport(handler)) as http:
        def factory(url, secret, timeout, execution_grant=None):
            return BrokerModelClient(url, secret, timeout, http_client=http, execution_grant=execution_grant)

        monkeypatch.setattr(module, "BrokerModelClient", factory)
        config = dataclasses.replace(settings, model_mode="broker", broker_url="http://broker.invalid")
        payload = json.loads((Path(__file__).resolve().parents[2] / "contracts/fixtures/agent-input-valid.json").read_text(encoding="utf-8"))
        with TestClient(module.create_app(config)) as client:
            assert client.get("/health").json()["workspaceRpcGrantVersions"] == ["workspace-rpc-grant.v1"]
            headers = {"X-Spec-Agent-Internal-Token": settings.internal_secret,
                       "X-Spec-Agent-Execution-Grant": "c" * 43}
            assert client.post("/v1/decisions", headers=headers, json=payload).status_code == 200
            assert client.post("/v1/decisions", headers={"X-Spec-Agent-Internal-Token": settings.internal_secret},
                               json=payload).status_code == 502
        assert seen == ["c" * 43]
