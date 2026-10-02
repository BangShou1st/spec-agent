import asyncio
import copy
import json
from uuid import uuid4

import pytest
from langchain_core.messages import AIMessage, HumanMessage

from spec_agent_brain.global_assistant.agent import build_agent
from spec_agent_brain.global_assistant.checkpoints import HostCheckpointSaver, SafeCheckpointCodec
from spec_agent_brain.global_assistant.contracts import RunScope
from spec_agent_brain.global_assistant.model_adapter import BrokerChatModel
from test_global_assistant_langchain import execution, response, ScriptBroker


class TestHostStorage:
    """Test-only host stand-in: exercises the saver RPC surface, not Java durability."""
    __test__ = False

    def __init__(self):
        self.rows = {}
        self.writes = {}
        self.version = 0
        self.calls = []
        self.codec = SafeCheckpointCodec()

    def rpc(self, operation, request):
        self.calls.append((operation, request))
        if operation in {"GET", "LIST"}:
            assert request["expectedVersion"] <= self.version
        else:
            assert request["expectedVersion"] == self.version
        assert request["namespace"] == "ga:langchain-ga.v1"
        result = None
        if operation == "PUT":
            self.rows[request["checkpointId"]] = {
                "checkpoint": request["checkpoint"], "metadata": request["metadata"],
                "parentCheckpointId": request["parentCheckpointId"],
                "pendingWrites": self.codec.dumps([]),
            }
            self.version += 1
        elif operation == "PUT_WRITES":
            for write in request["writes"]:
                key = (request["checkpointId"], request["taskId"], write["index"])
                self.writes[key] = (request["taskId"], write["channel"], self.codec.loads(write["value"]))
            self.version += 1
        elif operation == "GET":
            checkpoint_id = request["checkpointId"] or max(self.rows, default=None)
            if checkpoint_id in self.rows:
                result = copy.deepcopy(self.rows[checkpoint_id])
                result["pendingWrites"] = self.codec.dumps([v for k, v in self.writes.items() if k[0] == checkpoint_id])
        elif operation == "LIST":
            ids = sorted(self.rows, reverse=True)
            if request["before"]:
                ids = [k for k in ids if k < request["before"]]
            result = [copy.deepcopy(self.rows[k]) for k in ids
                      if all(self.codec.loads(self.rows[k]["metadata"]).get(key) == value
                             for key, value in request["filter"].items())][:request["limit"]]
        elif operation == "DELETE_THREAD":
            self.rows.clear()
            self.writes.clear()
            self.version += 1
        else:
            pytest.fail(f"unexpected operation {operation}")
        return {"protocolVersion": "ga-checkpoint.v1", "version": self.version, "result": result}


@pytest.mark.parametrize("asynchronous", [False, True])
def test_real_framework_can_persist_and_read_through_host_saver(asynchronous):
    e = execution()
    host = TestHostStorage()
    saver = HostCheckpointSaver(e, host.rpc)
    broker = ScriptBroker([response(content="已完成")])
    model = BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=1, leaseId=e.lease_id),
                            model_binding_id=e.model_binding_id, broker=broker, guard=lambda: None)
    graph = build_agent(e, model, saver=saver, invoke=lambda r: pytest.fail("unexpected tool"), guard=lambda: None)
    config = {"configurable": {"thread_id": str(e.thread_id)}}
    if asynchronous:
        result = asyncio.run(graph.ainvoke({"messages": [HumanMessage(content=e.content)]}, config, durability="sync"))
    else:
        result = graph.invoke({"messages": [HumanMessage(content=e.content)]}, config, durability="sync")
    assert result["messages"][-1].content == "已完成"
    assert host.rows and host.writes
    loaded = saver.get_tuple(config)
    assert loaded.checkpoint["channel_values"]["messages"][-1].content == "已完成"
    rows = list(saver.list(config, limit=2))
    assert len(rows) == 2
    assert len(list(saver.list(config, before=rows[0].config, limit=100))) == len(host.rows) - 1
    assert list(saver.list(config, filter={"source": "nonexistent"})) == []
    assert loaded.parent_config is not None
    saver.delete_thread(str(e.thread_id))
    assert not host.rows and not host.writes


def test_real_framework_tool_dispatch_send_roundtrips_through_closed_codec():
    from spec_agent_brain.global_assistant.contracts import ToolCall, CapabilityResponse
    e = execution()
    host = TestHostStorage()
    saver = HostCheckpointSaver(e, host.rpc)
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="project_search", arguments={"query": "项目"})]),
                           response(content="已找到")])
    model = BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=1, leaseId=e.lease_id),
                            model_binding_id=e.model_binding_id, broker=broker, guard=lambda: None)
    graph = build_agent(e, model, saver=saver, invoke=lambda r: CapabilityResponse(
        protocolVersion="ga-capability-result.v1", toolCallId=r.tool_call_id, status="SUCCEEDED", content={}), guard=lambda: None)
    config = {"configurable": {"thread_id": str(e.thread_id)}}
    result = graph.invoke({"messages": [HumanMessage(content=e.content)]}, config, durability="sync")
    assert result["messages"][-1].content == "已找到"
    assert any('"tag":"send"' in row["checkpoint"]["payload"] for row in host.rows.values())
    restored = HostCheckpointSaver(e, host.rpc).get_tuple(config)
    assert restored.checkpoint["channel_values"]["messages"][-1].content == "已找到"


def test_codec_preserves_messages_and_rejects_objects_hashes_versions_and_metadata():
    codec = SafeCheckpointCodec()
    value = {"messages": [AIMessage(content="", tool_calls=[{"id": "c1", "name": "search", "args": {}, "type": "tool_call"}])],
             "versions": {"messages": 1}, "tuple": (1, "x")}
    assert codec.loads(codec.dumps(value)) == value
    with pytest.raises(ValueError, match="unsupported"):
        codec.dumps(object())
    with pytest.raises(ValueError, match="metadata"):
        codec.dumps(AIMessage(content="public", additional_kwargs={"reasoning_content": "private"}))
    envelope = codec.dumps(value)
    envelope["payloadHash"] = "0" * 64
    with pytest.raises(ValueError, match="hash"):
        codec.loads(envelope)
    envelope = codec.dumps(value)
    envelope["frameworkVersion"] = "other"
    with pytest.raises(ValueError, match="incompatible"):
        codec.loads(envelope)
    malicious = {"tag": "constructor", "value": {"module": "os", "name": "system"}}
    envelope = codec.dumps(None)
    envelope["payload"] = json.dumps(malicious)
    import hashlib
    envelope["payloadHash"] = hashlib.sha256(envelope["payload"].encode()).hexdigest()
    with pytest.raises(ValueError, match="unknown"):
        codec.loads(envelope)


def test_saver_refuses_other_threads_and_regressing_host_version():
    e = execution()
    saver = HostCheckpointSaver(e, lambda op, request: {"protocolVersion": "ga-checkpoint.v1", "version": -1, "result": None})
    with pytest.raises(ValueError, match="scope"):
        saver.get_tuple({"configurable": {"thread_id": str(uuid4())}})
    with pytest.raises(ValueError, match="response"):
        saver.get_tuple({"configurable": {"thread_id": str(e.thread_id)}})
