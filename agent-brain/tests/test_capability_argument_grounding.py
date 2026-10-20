"""Ground nested tool argument refs before crossing the Brain/Host boundary; no model calls."""
import json
from pathlib import Path
import pytest

from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.decision import handle_decision, UngroundedReferenceError
from spec_agent_brain.model_client import Completion


class ScriptedClient:
    def __init__(self, arguments):
        self.calls = 0
        self.content = json.dumps({"observation": {"known": [], "unknowns": [], "conflicts": [], "risks": []},
                                   "action": {"actionFamily": "INVOKE_CAPABILITY", "payload": {
                                       "capabilityId": "probe.read", "arguments": arguments}, "sourceRefs": []}})

    def complete(self, *args, **kwargs):
        self.calls += 1
        return Completion(content=self.content, finish_reason="stop")


def request():
    return parse_request_envelope(json.loads((Path(__file__).resolve().parents[2] /
                                             "contracts/fixtures/agent-input-valid.json").read_text(encoding="utf-8")))


@pytest.mark.parametrize("prefix", ["node:", "answer:", "patch:", "context:", "route:"])
@pytest.mark.parametrize("nested", [False, True])
def test_foreign_argument_ref_fails_without_retry_or_value_disclosure(prefix, nested):
    foreign = prefix + "11111111-2222-3333-4444-555555555555"
    arguments = {"source": foreign} if not nested else {"filters": [{"sources": [foreign]}]}
    client = ScriptedClient(arguments)
    with pytest.raises(UngroundedReferenceError) as caught:
        handle_decision(request(), client)
    assert client.calls == 1
    assert foreign not in str(caught.value)
    assert caught.value.diagnostic.field_path == "action.payload.arguments"
    assert caught.value.diagnostic.machine_code == "MODEL_OUTPUT_UNGROUNDED_REFERENCE"


def test_allowed_nested_refs_and_ordinary_values_remain_legal():
    req = request()
    client = ScriptedClient({"filters": [{"source": req.snapshot.allowed_source_refs[0]}, None, False, 0, "query"]})
    assert handle_decision(req, client).action_proposal.action_family == "INVOKE_CAPABILITY"
    assert client.calls == 1
