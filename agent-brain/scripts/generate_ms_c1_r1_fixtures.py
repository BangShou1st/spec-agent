"""Generate real Python-serialized v2 model request fixtures for Java replay."""

import json
import sys
from pathlib import Path
from uuid import UUID, uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from spec_agent_brain.global_assistant.contracts import ModelRequest, ToolCall, ToolDefinition


def main(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    run_id, lease, binding, first_call, second_call = uuid4(), uuid4(), uuid4(), uuid4(), uuid4()
    identity = {
        "runId": run_id, "executionEpoch": 1, "leaseId": lease,
        "modelBindingId": binding,
    }
    tools = [ToolDefinition(name="probe_echo", description="echo",
                            parameters={"type": "object"})]
    first = ModelRequest(
        protocolVersion="ga-model-inference.v2", **identity,
        callId=first_call, callType="AGENT",
        messages=[{"role": "user", "content": "probe"}],
        tools=tools, toolChoice="required", maxOutputTokens=256, stream=False)
    ref = "gacr-00000000-0000-4000-8000-000000000042"
    second = ModelRequest(
        protocolVersion="ga-model-inference.v2", **identity,
        callId=second_call, callType="AGENT",
        messages=[
            {"role": "user", "content": "probe"},
            {"role": "assistant", "content": "",
             "toolCalls": [{"id": "fixture-call", "name": "probe_echo",
                            "arguments": {"text": "probe ok"}}],
             "continuationRef": ref},
            {"role": "tool", "content": "{\"status\":\"SUCCEEDED\"}",
             "toolCallId": "fixture-call"}],
        tools=tools, toolChoice="auto", maxOutputTokens=256, stream=False)
    (out_dir / "python-first-request.json").write_text(
        first.model_dump_json(by_alias=True) + "\n", encoding="utf-8")
    (out_dir / "python-second-request.json").write_text(
        second.model_dump_json(by_alias=True) + "\n", encoding="utf-8")
    (out_dir / "identity.json").write_text(json.dumps({
        "runId": str(run_id), "leaseId": str(lease), "bindingId": str(binding),
        "firstCallId": str(first_call), "secondCallId": str(second_call),
        "ref": ref}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/ms-c1-r1-fixtures"))
