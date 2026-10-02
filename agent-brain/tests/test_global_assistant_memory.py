import json
from uuid import uuid4

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from spec_agent_brain.global_assistant.agent import build_agent
from spec_agent_brain.global_assistant.checkpoints import HostCheckpointSaver
from spec_agent_brain.global_assistant.contracts import RunScope
from spec_agent_brain.global_assistant.model_adapter import BrokerChatModel, BrokerError
from test_global_assistant_checkpoints import TestHostStorage
from test_global_assistant_langchain import execution, ScriptBroker, response


def history(project):
    messages = [HumanMessage(content="找项目"), AIMessage(content="", tool_calls=[
        {"id": "history-c1", "name": "project_search", "args": {"query": "项目"}, "type": "tool_call"}]),
        ToolMessage(tool_call_id="history-c1", content=json.dumps({"protocolVersion": "ga-capability-result.v1",
            "toolCallId": "history-c1", "status": "SUCCEEDED", "content": {"candidates": [
                {"projectId": str(project), "title": "身份不能被摘要改写"}]}, "sourceRefs": ["project:" + str(project)]}))]
    messages.extend(HumanMessage(content="用户历史") if i % 2 == 0 else AIMessage(content="历史回答") for i in range(78))
    messages.append(HumanMessage(content="继续"))
    return messages


def test_real_framework_summary_preserves_identity_and_durable_codec():
    e = execution()
    project = uuid4()
    broker = ScriptBroker([response(content="仅连续性摘要，不含任何身份"), response(content="完成")])
    model = BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=1, leaseId=e.lease_id),
                           model_binding_id=e.model_binding_id, broker=broker, guard=lambda: None)
    host = TestHostStorage()
    saver = HostCheckpointSaver(e, host.rpc)
    graph = build_agent(e, model, saver=saver, invoke=lambda r: pytest.fail("Unexpected new tool"), guard=lambda: None)
    config = {"configurable": {"thread_id": str(e.thread_id)}}
    result = graph.invoke({"messages": history(project)}, config, durability="sync")
    assert [request.call_type for request in broker.requests] == ["SUMMARY", "AGENT"]
    assert broker.requests[0].tools == [] and broker.requests[0].tool_choice == "none"
    assert len(broker.requests[1].messages) < 30
    assert str(project) in broker.requests[1].messages[0].content
    restored = HostCheckpointSaver(e, host.rpc).get_tuple(config).checkpoint["channel_values"]
    assert restored["ga_facts"]["projects"][str(project)] == "身份不能被摘要改写"
    assert restored["ga_facts"]["sourceRefs"] == ["project:" + str(project)]
    assert result["messages"][-1].content == "完成"


def test_framework_summary_failure_does_not_retry_or_fabricate_summary():
    e = execution()
    class FailingBroker:
        def __init__(self): self.requests = []
        def complete(self, request):
            self.requests.append(request)
            raise BrokerError("GA_BUDGET_EXHAUSTED")
    broker = FailingBroker()
    model = BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=1, leaseId=e.lease_id),
                           model_binding_id=e.model_binding_id, broker=broker, guard=lambda: None)
    host = TestHostStorage()
    graph = build_agent(e, model, saver=HostCheckpointSaver(e, host.rpc),
                        invoke=lambda r: pytest.fail("Unexpected tool"), guard=lambda: None)
    with pytest.raises(BrokerError, match="GA_BUDGET_EXHAUSTED"):
        graph.invoke({"messages": history(uuid4())}, {"configurable": {"thread_id": str(e.thread_id)}})
    assert len(broker.requests) == 1 and broker.requests[0].call_type == "SUMMARY"
