"""Executable evidence of the current WAIT boundary; no async lifecycle is simulated."""
import json
from pathlib import Path
import pytest
from pydantic import ValidationError
from spec_agent_brain.contracts.inputs import parse_request_envelope, CapabilityResultView
from spec_agent_brain.decision import handle_decision, ActionIneligibleBrainError
from spec_agent_brain.model_client import Completion

FIXTURES = Path(__file__).resolve().parents[2] / "contracts/fixtures"


@pytest.mark.parametrize("status", ["SUCCEEDED", "FAILED"])
def test_deferred_completion_is_consumable_as_an_existing_observation(status):
    raw = json.loads((FIXTURES / "deferred-capability-completed-observation.json").read_text(encoding="utf-8"))
    raw["status"] = status
    result = CapabilityResultView.model_validate(raw)
    assert result.model_dump(mode="json", by_alias=True) == raw


@pytest.mark.parametrize("fixture", ["agent-input-valid.json", "agent-input-v3-valid.json"])
def test_host_pending_registration_cannot_be_smuggled_into_completed_wire_observations(fixture):
    raw = json.loads((FIXTURES / fixture).read_text(encoding="utf-8"))
    pending = json.loads((FIXTURES / "deferred-capability-completed-observation.json").read_text(encoding="utf-8"))
    pending["status"] = "PENDING"
    raw["snapshot"]["capabilityResults"] = [pending]
    with pytest.raises(ValidationError, match="Host-owned"):
        parse_request_envelope(raw)


@pytest.mark.parametrize("fixture", ["agent-input-valid.json", "agent-input-v3-valid.json"])
def test_existing_wire_cannot_silently_acquire_pending_dependency_semantics(fixture):
    raw = json.loads((FIXTURES / fixture).read_text(encoding="utf-8"))
    raw["snapshot"]["pendingDependencies"] = []
    with pytest.raises(ValidationError):
        parse_request_envelope(raw)


@pytest.mark.parametrize("enforced", [False, True])
def test_wait_mask_is_enforced_only_for_v3_without_extra_calls(enforced):
    fixture = "agent-input-v3-valid.json" if enforced else "agent-input-valid.json"
    req = parse_request_envelope(json.loads((FIXTURES / fixture).read_text(encoding="utf-8")))

    class Client:
        calls = 0

        def complete(self, *args, **kwargs):
            self.calls += 1
            return Completion(content=json.dumps({"observation": {"known": [], "unknowns": [], "conflicts": [], "risks": []},
                "action": {"actionFamily": "WAIT", "payload": {}, "sourceRefs": []}}), finish_reason="stop")

    client = Client()
    if enforced:
        with pytest.raises(ActionIneligibleBrainError):
            handle_decision(req, client)
    else:
        assert handle_decision(req, client).action_proposal.action_family == "WAIT"
    assert client.calls == 1
