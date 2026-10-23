"""Opted-in R2 delivery probe: existing framework and Java RPCs, no provider key/client."""
import json
import os
import sys
from pathlib import Path
from langchain_core.messages import HumanMessage, ToolMessage
from spec_agent_brain.global_assistant.agent import build_agent
from spec_agent_brain.global_assistant.checkpoints import HostCheckpointSaver
from spec_agent_brain.global_assistant.contracts import ExecutionRequest, RunScope
from spec_agent_brain.global_assistant.host_rpc import CheckpointRpc, CapabilityRpc
from spec_agent_brain.global_assistant.model_adapter import BrokerChatModel, NativeBroker


def main():
    execution = ExecutionRequest.model_validate_json(sys.stdin.buffer.read(262145))
    output = Path(os.environ["SPEC_AGENT_R2_GA_OUTPUT"])
    if output.exists():
        raise RuntimeError("Existing first observation cannot be overwritten")
    base = os.environ["SPEC_AGENT_GA_TEST_HOST"]
    token = os.environ["SPEC_AGENT_GA_TEST_INTERNAL_TOKEN"]
    checkpoints = CheckpointRpc(base + "/internal/v1/global-assistant/checkpoints", token)
    broker = NativeBroker(base + "/internal/v1/global-assistant/model-inference", token)
    capabilities = CapabilityRpc(base + "/internal/v1/global-assistant/capabilities", token)
    try:
        saver = HostCheckpointSaver(execution, checkpoints)
        scope = RunScope(runId=execution.run_id, executionEpoch=execution.execution_epoch, leaseId=execution.lease_id)
        model = BrokerChatModel(scope=scope, model_binding_id=execution.model_binding_id, broker=broker, guard=lambda: None)
        graph = build_agent(execution, model, saver=saver, invoke=capabilities, guard=lambda: None)
        config = {"configurable": {"thread_id": str(execution.thread_id)}}
        result = graph.invoke({"messages": [HumanMessage(content=execution.content, id=str(execution.message_id))]}, config, durability="sync")
        messages = [{"type": m.type, "content": m.content, "toolCalls": getattr(m, "tool_calls", [])} for m in result["messages"]]
        observed = any(isinstance(m, ToolMessage) and json.loads(m.content).get("status") == "SUCCEEDED" for m in result["messages"])
        restored = HostCheckpointSaver(execution, checkpoints).get_tuple(config)
        replay = restored.checkpoint["channel_values"]["messages"][-1].content == result["messages"][-1].content
        evidence = {"messages": messages, "hostObservationSucceeded": observed, "checkpointRestored": replay}
        with output.open("x", encoding="utf-8") as stream:
            json.dump(evidence, stream, ensure_ascii=False, indent=2)
        assert observed and replay and result["messages"][-1].content.strip()
    finally:
        broker.close()
        checkpoints.close()
        capabilities.close()


if __name__ == "__main__":
    main()
