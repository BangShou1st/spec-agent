"""Offline V4 contracts and the real Brain boundary with a scripted local client."""
import json
from pathlib import Path
import pytest
from pydantic import ValidationError
from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.contracts.decisions import validate_v3_response_for_request
from spec_agent_brain.decision import handle_decision, ActionIneligibleBrainError
from spec_agent_brain.model_client import Completion

FIXTURE = Path(__file__).resolve().parents[2] / "contracts/fixtures/agent-input-v4-wait.json"

def raw():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))

class Client:
    def __init__(self, payload):
        self.payload, self.calls, self.messages = payload, 0, None

    def complete(self, **kwargs):
        self.calls += 1
        self.messages = kwargs["messages"]
        return Completion(content=json.dumps({"observation": {"known": [], "unknowns": [], "conflicts": [], "risks": []},
            "action": {"actionFamily": "WAIT", "payload": self.payload, "sourceRefs": []}}), finish_reason="stop")

def test_v4_wait_binds_to_one_projected_invocation_and_returns_v4_without_extra_calls():
    request = parse_request_envelope(raw())
    ref = str(request.pending_dependencies.dependencies[0].invocation_id)
    client = Client({"dependencyRef": ref})
    response = handle_decision(request, client)
    assert response.protocol_version == "agent-decision.v4"
    assert response.action_proposal.payload == {"dependencyRef": ref}
    validate_v3_response_for_request(request, response)
    assert client.calls == 1
    control = json.loads(client.messages[-1].content)
    assert control["pendingDependencies"]["runId"] == str(request.run_id)
    assert control["waitPayloadSchema"]["properties"]["dependencyRef"]["enum"] == [ref]

@pytest.mark.parametrize("payload", [{}, {"dependencyRef": "invented"}, {"dependencyRef": None},
    {"dependencyRef": ["invented"]}, {"dependencyRef": "11111111-2222-3333-4444-555555555555", "projectId": "foreign"}])
def test_invalid_wait_payload_fails_closed(payload):
    with pytest.raises(ActionIneligibleBrainError):
        handle_decision(parse_request_envelope(raw()), Client(payload))

@pytest.mark.parametrize("version", ["agent-input.v2", "agent-input.v3"])
def test_existing_versions_reject_v4_projection(version):
    value = raw(); value["protocolVersion"] = version
    with pytest.raises(ValidationError):
        parse_request_envelope(value)
    value["pendingDependencies"] = None
    with pytest.raises(ValidationError):
        parse_request_envelope(value)

@pytest.mark.parametrize("field", ["runId", "snapshotId", "projectId", "routeId", "anchorNodeId", "contextHash"])
def test_cross_scope_projection_fails_before_model_call(field):
    value = raw(); value["pendingDependencies"][field] = "b"*64 if field == "contextHash" else "99999999-2222-3333-4444-555555555555"
    with pytest.raises(ValidationError, match="scope"):
        parse_request_envelope(value)

def test_duplicate_dependency_and_nonterminal_completed_result_are_rejected():
    value = raw(); value["pendingDependencies"]["dependencies"] *= 2
    with pytest.raises(ValidationError, match="duplicate"):
        parse_request_envelope(value)
    value = raw(); value["pendingDependencies"]["completedResults"] = [{"invocationId": "fixture", "capabilityId": "probe", "status": "RUNNING"}]
    with pytest.raises(ValidationError, match="settled"):
        parse_request_envelope(value)

def test_v4_response_cannot_be_downgraded_to_v3():
    request = parse_request_envelope(raw())
    response = handle_decision(request, Client({"dependencyRef": str(request.pending_dependencies.dependencies[0].invocation_id)}))
    downgraded = response.model_copy(update={"protocol_version": "agent-decision.v3"})
    with pytest.raises(ValueError, match="version"):
        validate_v3_response_for_request(request, downgraded)

def test_independent_response_validator_rejects_tampered_dependency_selection():
    request = parse_request_envelope(raw())
    response = handle_decision(request, Client({"dependencyRef": str(request.pending_dependencies.dependencies[0].invocation_id)}))
    tampered = response.model_copy(update={"action_proposal": response.action_proposal.model_copy(
        update={"payload": {"dependencyRef": "foreign"}})})
    with pytest.raises(ValueError, match="dependencyRef"):
        validate_v3_response_for_request(request, tampered)
