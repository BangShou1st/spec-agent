"""Regenerate strict GA JSON Schemas and shared golden fixtures."""
from copy import deepcopy
import json
from pathlib import Path

from spec_agent_brain.global_assistant.contracts import (
    CapabilityRequest, CapabilityResponse, ExecutionRequest, ExecutionEvent, ModelRequest, ModelResponse, ModelStreamEvent,
)

ROOT = Path(__file__).resolve().parents[2] / "contracts" / "global-assistant"


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


for cls, name in [(ModelRequest, "ga-model-request"), (ModelResponse, "ga-model-response"),
                  (ExecutionRequest, "ga-execution"), (CapabilityRequest, "ga-capability-invocation"),
                  (CapabilityResponse, "ga-capability-result"), (ExecutionEvent, "ga-execution-event"),
                  (ModelStreamEvent, "ga-model-stream-event")]:
    write(ROOT / f"{name}.schema.json", cls.model_json_schema(by_alias=True))

identity = "00000000-0000-4000-8000-000000000001"
request = {"protocolVersion": "ga-model-inference.v1", "runId": identity, "executionEpoch": 1,
           "leaseId": identity, "callId": identity, "callType": "AGENT", "modelBindingId": identity,
           "messages": [{"role": "system", "content": "Use approved tools"},
                        {"role": "user", "content": "查找项目"},
                        {"role": "assistant", "content": "", "toolCalls": [
                            {"id": "native-1", "name": "project_search", "arguments": {"query": "项目"}}]},
                        {"role": "tool", "content": "{\"projects\":[]}", "toolCallId": "native-1"}],
           "tools": [{"name": "project_search", "description": "Find projects", "parameters": {
               "type": "object", "properties": {"query": {"type": "string"}},
               "required": ["query"], "additionalProperties": False}}],
           "toolChoice": "auto", "maxOutputTokens": 2048, "stream": False}
ModelRequest.model_validate_json(json.dumps(request))
write(ROOT / "fixtures" / "ga-model-request-valid.json", request)
invalid = {}
v = deepcopy(request); v["providerUrl"] = "https://untrusted.example"; invalid["provider-field"] = v
v = deepcopy(request); v["protocolVersion"] = "model-inference.v1"; invalid["old-version"] = v
v = deepcopy(request); v["executionEpoch"] = 0; invalid["epoch"] = v
v = deepcopy(request); v["messages"][-1]["toolCallId"] = "other"; invalid["orphan-result"] = v
v = deepcopy(request); v["messages"].pop(); invalid["unfinished-call"] = v
v = deepcopy(request); v["messages"][0]["role"] = "developer"; invalid["role"] = v
v = deepcopy(request); v["messages"][1]["toolCalls"] = request["messages"][2]["toolCalls"]; invalid["role-calls"] = v
v = deepcopy(request); v["messages"].append(request["messages"][-1]); invalid["duplicate-result"] = v
v = deepcopy(request); v["callType"] = "SUMMARY"; invalid["summary-tools"] = v
v = deepcopy(request); v["tools"].append(request["tools"][0]); invalid["duplicate-name"] = v
v = deepcopy(request); v["maxOutputTokens"] = "2048"; invalid["coerced-number"] = v
v = deepcopy(request); v["maxOutputTokens"] = 1.5; invalid["fractional-number"] = v
v = deepcopy(request); v["tools"] = None; invalid["null-tools"] = v
for label, value in invalid.items():
    write(ROOT / "fixtures" / f"ga-model-request-invalid-{label}.json", value)

response = {"protocolVersion": "ga-model-inference.v1", "content": "", "toolCalls": [
    {"id": "native-1", "name": "project_search", "arguments": {"query": "项目"}}],
    "finishReason": "tool_calls", "usage": {"promptTokens": 10, "completionTokens": 4}}
write(ROOT / "fixtures" / "ga-model-response-valid.json", response)
v = deepcopy(response); v["finishReason"] = "length"; write(ROOT / "fixtures" / "ga-model-response-invalid-truncated.json", v)
v = deepcopy(response); v["finishReason"] = "stop"; write(ROOT / "fixtures" / "ga-model-response-invalid-finish.json", v)
v = deepcopy(response); v["usage"]["promptTokens"] = -1; write(ROOT / "fixtures" / "ga-model-response-invalid-usage.json", v)
v = deepcopy(response); v["toolCalls"].append(response["toolCalls"][0]); write(ROOT / "fixtures" / "ga-model-response-invalid-duplicate.json", v)
