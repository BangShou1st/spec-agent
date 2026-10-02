"""Explicit test entry: real framework + authenticated Java model/checkpoint RPC.

Java's deterministic provider boundary in the matching integration test makes
this an integration check, not real-provider/product acceptance.
"""
import json
import os
import sys

from langchain_core.messages import HumanMessage, ToolMessage
from spec_agent_brain.global_assistant.agent import build_agent
from spec_agent_brain.global_assistant.checkpoints import HostCheckpointSaver
from spec_agent_brain.global_assistant.contracts import ExecutionRequest, RunScope
from spec_agent_brain.global_assistant.host_rpc import CheckpointRpc, CapabilityRpc
from spec_agent_brain.global_assistant.model_adapter import BrokerChatModel, NativeBroker


def main():
    execution = ExecutionRequest.model_validate_json(sys.stdin.buffer.read(262145))
    base = os.environ["SPEC_AGENT_GA_TEST_HOST"]
    secret = os.environ["SPEC_AGENT_GA_TEST_INTERNAL_TOKEN"]
    checkpoints = CheckpointRpc(base + "/internal/v1/global-assistant/checkpoints", secret)
    broker = NativeBroker(base + "/internal/v1/global-assistant/model-inference", secret)
    capabilities = CapabilityRpc(base + "/internal/v1/global-assistant/capabilities", secret)
    try:
        saver = HostCheckpointSaver(execution, checkpoints)
        scope = RunScope(runId=execution.run_id, executionEpoch=execution.execution_epoch, leaseId=execution.lease_id)
        model = BrokerChatModel(scope=scope, model_binding_id=execution.model_binding_id, broker=broker, guard=lambda: None)
        def unexpected_tool(request):
            raise AssertionError("Unexpected tool in model/checkpoint-only roundtrip")
        business = len(execution.capabilities) > 1 or os.environ.get("SPEC_AGENT_GA_TEST_CAPABILITIES") == "true"
        graph = build_agent(execution, model, saver=saver, invoke=capabilities if business else unexpected_tool, guard=lambda: None)
        config = {"configurable": {"thread_id": str(execution.thread_id)}}
        result = graph.invoke({"messages": [HumanMessage(content=execution.content, id=str(execution.message_id))]},
                              config, durability="sync")
        live = os.environ.get("SPEC_AGENT_GA_TEST_LIVE") == "true"
        if live:
            assert result["messages"][-1].content.strip()
            assert any(isinstance(message, ToolMessage) and json.loads(message.content).get("status") == "SUCCEEDED"
                       for message in result["messages"])
        else:
            assert result["messages"][-1].content == "rpc-complete"
        # A new saver instance proves this does not rely on Python in-memory state.
        restored = HostCheckpointSaver(execution, checkpoints).get_tuple(config)
        assert restored.checkpoint["channel_values"]["messages"][-1].content == result["messages"][-1].content
        assert list(saver.list(config, limit=2))
        print(json.dumps({"status": "PASS", "checkpointRestored": True,
                          "scope": "real-provider framework/model/checkpoint/business RPC" if live else
                          "model/checkpoint/capability RPC; deterministic provider" if business else
                          "model/checkpoint RPC; deterministic provider; no capability dispatch"}))
    finally:
        broker.close()
        checkpoints.close()
        capabilities.close()


if __name__ == "__main__":
    main()
