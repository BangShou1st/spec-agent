"""Explicit real-provider tool/followup and durable saver test against a test host.

The tool result is synthetic and read-only; this does not qualify capability
dispatch, product events or production recovery.
"""
import json
import os
import sys
from uuid import uuid4

from langchain_core.messages import AIMessage, ToolMessage
from langgraph.checkpoint.base import empty_checkpoint
from spec_agent_brain.global_assistant.checkpoints import HostCheckpointSaver
from spec_agent_brain.global_assistant.contracts import ExecutionRequest, ModelRequest
from spec_agent_brain.global_assistant.host_rpc import CheckpointRpc
from spec_agent_brain.global_assistant.model_adapter import NativeBroker
from spec_agent_brain.global_assistant.schema import normalize_schema


def main():
    execution = ExecutionRequest.model_validate_json(sys.stdin.buffer.read(262145))
    base = os.environ["SPEC_AGENT_GA_TEST_HOST"]
    secret = os.environ["SPEC_AGENT_GA_TEST_INTERNAL_TOKEN"]
    checkpoints = CheckpointRpc(base + "/internal/v1/global-assistant/checkpoints", secret)
    broker = NativeBroker(base + "/internal/v1/global-assistant/model-inference", secret)
    try:
        scope = {"runId": execution.run_id, "executionEpoch": execution.execution_epoch,
                 "leaseId": execution.lease_id, "modelBindingId": execution.model_binding_id}
        descriptor = execution.capabilities[0]
        tools = [{"name": descriptor.name, "description": descriptor.description,
                  "parameters": normalize_schema(descriptor.input_schema)}]
        messages = [{"role": "system", "content": "Qualification: call project_search exactly once with query GA_NATIVE_QUALIFICATION. Then report the tool result token verbatim. Do not invent a result."},
                    {"role": "user", "content": "Search the qualification project."}]
        def request(choice):
            return ModelRequest(protocolVersion="ga-model-inference.v1", **scope, callId=uuid4(), callType="AGENT",
                                messages=messages, tools=tools, toolChoice=choice, maxOutputTokens=1024, stream=False)
        first = broker.complete(request("required"))
        assert len(first.tool_calls) == 1
        call = first.tool_calls[0]
        assert call.name == "project_search" and call.arguments == {"query": "GA_NATIVE_QUALIFICATION"}
        token = "QUALIFICATION_RESULT_" + str(uuid4())
        messages.extend([{"role": "assistant", "content": first.content, "toolCalls": [call.model_dump(by_alias=True)]},
                         {"role": "tool", "content": json.dumps({"result": token}), "toolCallId": call.id}])
        followup = broker.complete(request("none"))
        assert not followup.tool_calls and token in followup.content
        saver = HostCheckpointSaver(execution, checkpoints)
        config = {"configurable": {"thread_id": str(execution.thread_id)}}
        saver.get_tuple(config)
        checkpoint = empty_checkpoint()
        checkpoint["channel_values"] = {"messages": [
            AIMessage(content=first.content, tool_calls=[{"name": call.name, "args": call.arguments, "id": call.id, "type": "tool_call"}]),
            ToolMessage(content=json.dumps({"result": token}), tool_call_id=call.id),
            AIMessage(content=followup.content)]}
        saved = saver.put(config, checkpoint, {"source": "input", "step": 0, "parents": {}}, {})
        restored = HostCheckpointSaver(execution, checkpoints).get_tuple(saved)
        assert token in restored.checkpoint["channel_values"]["messages"][-1].content
        print(json.dumps({"status": "PASS", "providerCalls": 2, "checkpointRestored": True,
                          "scope": "authenticated model/checkpoint RPC with real provider; synthetic tool result"}))
    finally:
        broker.close()
        checkpoints.close()


if __name__ == "__main__":
    main()
