import json

import httpx
import pytest
from fastapi.testclient import TestClient

from spec_agent_brain.app import create_app
from spec_agent_brain.config import Settings
from spec_agent_brain.global_assistant.contracts import ExecutionEvent
from spec_agent_brain.wire import canonical_hash
from test_global_assistant_checkpoints import TestHostStorage
from test_global_assistant_langchain import execution, response, ScriptBroker


def test_execution_endpoint_runs_actual_framework_and_claims_json_identity(monkeypatch):
    import spec_agent_brain.global_assistant.execution as endpoint
    e = execution()
    body = e.model_dump(by_alias=True, mode="json")
    claims = []
    class ClaimClient:
        def __init__(self, **kwargs):
            assert kwargs["trust_env"] is False and kwargs["follow_redirects"] is False
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def stream(self, method, url, **kwargs):
            claims.append(kwargs["json"])
            assert kwargs["headers"]["X-Spec-Agent-Internal-Token"] == "test-token"
            from contextlib import nullcontext
            return nullcontext(httpx.Response(200, json={"checkpointId": None}))
    class Checkpoints(TestHostStorage):
        def __init__(self, *args): super().__init__()
        def __call__(self, operation, envelope): return self.rpc(operation, envelope)
        def close(self): pass
    class Capabilities:
        def __init__(self, *args): pass
        def __call__(self, request): pytest.fail("Unexpected business action")
        def close(self): pass
    class Broker(ScriptBroker):
        def __init__(self, *args): super().__init__([response(content="完成")])
        def stream(self, request, sink):
            result = self.complete(request)
            sink("TEXT_DELTA", {"callId": str(request.call_id), "text": "完"})
            sink("TEXT_DELTA", {"callId": str(request.call_id), "text": "成"})
            return result
        def close(self): pass
    monkeypatch.setattr(endpoint.httpx, "Client", ClaimClient)
    monkeypatch.setattr(endpoint, "CheckpointRpc", Checkpoints)
    monkeypatch.setattr(endpoint, "CapabilityRpc", Capabilities)
    monkeypatch.setattr(endpoint, "NativeBroker", Broker)
    client = TestClient(create_app(Settings("test-token", "broker",
                          "http://127.0.0.1:8080/internal/v1/model-inference", 30)))
    result = client.post("/internal/v1/global-assistant/executions", json=body,
                         headers={"X-Spec-Agent-Internal-Token": "test-token"})
    assert result.status_code == 200
    events = [ExecutionEvent.model_validate_json(line) for line in result.text.splitlines()]
    assert [event.type for event in events] == ["STATUS", "TEXT_DELTA", "TEXT_DELTA", "COMPLETED"]
    assert [event.sequence for event in events] == [1, 2, 3, 4]
    assert events[-1].payload.text == "完成"
    assert claims[0]["executionRequestHash"] == canonical_hash(body)


def test_execution_auth_and_fake_mode_fail_closed():
    body = execution().model_dump(by_alias=True, mode="json")
    client = TestClient(create_app(Settings("test-token", "fake", "http://localhost/internal/v1/model-inference", 30)))
    assert client.post("/internal/v1/global-assistant/executions", json=body).status_code == 401
    assert client.post("/internal/v1/global-assistant/executions", json=body,
                       headers={"X-Spec-Agent-Internal-Token": "test-token"}).status_code == 503


def test_cancel_scope_is_strict_and_duplicate_json_keys_are_rejected():
    from uuid import uuid4
    client = TestClient(create_app(Settings("test-token", "broker", "http://localhost/internal/v1/model-inference", 30)))
    path = f"/internal/v1/global-assistant/executions/{uuid4()}/cancel"
    headers = {"X-Spec-Agent-Internal-Token": "test-token"}
    lease = str(uuid4())
    valid = json.dumps({"executionEpoch": 1, "leaseId": lease})
    assert client.post(path, content=valid).status_code == 401
    assert client.post(path, content=valid, headers=headers).status_code == 200
    duplicate = '{"executionEpoch":1,"executionEpoch":2,"leaseId":"' + lease + '"}'
    assert client.post(path, content=duplicate, headers=headers).status_code == 422
    assert client.post(path, json={"executionEpoch": "1", "leaseId": lease}, headers=headers).status_code == 422


@pytest.mark.parametrize("change", [{"type": "COMPLETED", "payload": {"stage": "EXECUTING"}},
                                    {"type": "FAILED", "payload": {"errorCode": "PRIVATE_PROVIDER_DUMP"}},
                                    {"sequence": "1"}, {"leaseId": "caller-defined"}])
def test_execution_events_reject_mismatched_unbounded_or_unknown_fields(change):
    from uuid import uuid4
    value = {"protocolVersion": "ga-execution-event.v1", "runId": str(uuid4()), "executionEpoch": 1,
             "eventId": str(uuid4()), "sequence": 1, "type": "STATUS", "payload": {"stage": "EXECUTING"}}
    value.update(change)
    with pytest.raises(ValueError): ExecutionEvent.model_validate_json(json.dumps(value))
