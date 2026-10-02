import hashlib
import httpx
import pytest

from spec_agent_brain.global_assistant.checkpoints import SafeCheckpointCodec
from spec_agent_brain.global_assistant.host_rpc import CheckpointRpc, HostRpcError


@pytest.mark.parametrize("status,body", [(401, b"private credential"), (302, b"redirect"),
                                           (200, b'{"protocolVersion":"ga-checkpoint.v1","version":1,"version":2,"result":null}'),
                                           (200, b'{"protocolVersion":"ga-checkpoint.v1","version":-1,"result":null}')])
def test_rpc_rejects_auth_redirect_and_invalid_wire_without_echoing_body(status, body):
    attempts = []
    def handler(request):
        attempts.append(request)
        assert request.headers["X-Spec-Agent-Internal-Token"] == "test-token"
        return httpx.Response(status, content=body)
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        rpc = CheckpointRpc("http://localhost/internal/v1/global-assistant/checkpoints", "test-token", client=client)
        with pytest.raises(HostRpcError) as error:
            rpc("GET", {})
        assert "private" not in str(error.value)
        assert len(attempts) == 1


def test_checkpoint_codec_rejects_duplicate_json_keys_and_numeric_overflow():
    codec = SafeCheckpointCodec()
    for payload in ['{"tag":"dict","value":{"x":1,"x":2}}', '1e999']:
        envelope = codec.dumps(None)
        envelope.update(payload=payload, payloadHash=hashlib.sha256(payload.encode()).hexdigest())
        with pytest.raises(ValueError):
            codec.loads(envelope)


@pytest.mark.parametrize("url", ["http://user:secret@localhost/internal/v1/global-assistant/checkpoints",
                                 "http://localhost/arbitrary", "http://localhost/internal/v1/global-assistant/checkpoints?sql=x"])
def test_rpc_only_accepts_configured_checkpoint_endpoint(url):
    with pytest.raises(ValueError):
        CheckpointRpc(url, "test-token")
