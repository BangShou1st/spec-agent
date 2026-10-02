"""Real create_agent behavior against deterministic broker/host boundaries (not live qualification)."""

import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver
from pydantic import ValidationError

from spec_agent_brain.global_assistant.agent import build_agent
from spec_agent_brain.global_assistant.contracts import (
    Budget, CapabilityResponse, Descriptor, ExecutionRequest, ModelRequest,
    ModelResponse, RunScope, ToolCall, Usage, canonical_hash,
)
from spec_agent_brain.global_assistant.model_adapter import BrokerChatModel, BrokerError, NativeBroker
from spec_agent_brain.global_assistant.schema import normalize_schema, tool_name


def execution(descriptors=None, budget=None):
    descriptors = descriptors or [Descriptor(capabilityId="project.search", version="1", name="project_search",
                                             description="Find projects", inputSchema={"query": {"type": "string", "required": True}},
                                             readOnly=True, sideEffectClass="NONE")]
    return ExecutionRequest(protocolVersion="ga-execution.v1", engineVersion="langchain-ga.v1",
                            runId=uuid4(), executionEpoch=1, leaseId=uuid4(), threadId=uuid4(), messageId=uuid4(),
                            content="查找我的项目", modelBindingId=uuid4(), policyVersion="1",
                            catalogHash=canonical_hash([d.model_dump(by_alias=True) for d in descriptors]),
                            historyBoundary=None, history=[], uiContext={"currentPage": "PROJECTS"},
                            capabilities=descriptors, budget=budget or Budget())


def response(content="", calls=()):
    return ModelResponse(protocolVersion="ga-model-inference.v1", content=content, toolCalls=list(calls),
                         finishReason="tool_calls" if calls else "stop",
                         usage=Usage(promptTokens=12, completionTokens=4))


class ScriptBroker:
    def __init__(self, replies):
        self.replies = iter(replies)
        self.requests = []

    def complete(self, request):
        self.requests.append(request)
        return next(self.replies)


def setup_agent(e, broker, invoke, guard=lambda: None):
    model = BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=e.execution_epoch, leaseId=e.lease_id),
                            model_binding_id=e.model_binding_id, broker=broker, guard=guard)
    saver = InMemorySaver()  # Tests only. Production assembly must receive a host saver.
    graph = build_agent(e, model, saver=saver, invoke=invoke, guard=guard)
    config = {"configurable": {"thread_id": str(e.thread_id)}, "recursion_limit": 50}
    return graph, config, saver


def test_framework_native_tool_result_follow_up_and_checkpoint():
    e = execution()
    calls = [ToolCall(id="native-call-1", name="project_search", arguments={"query": "项目"})]
    broker = ScriptBroker([response(calls=calls), response(content="找到一个项目")])
    invoked = []

    def invoke(request):
        invoked.append(request)
        return CapabilityResponse(protocolVersion="ga-capability-result.v1", toolCallId=request.tool_call_id,
                                  status="SUCCEEDED", content={"projects": [{"projectId": str(uuid4())}]},
                                  sourceRefs=["project:verified"])

    graph, config, saver = setup_agent(e, broker, invoke)
    output = graph.invoke({"messages": [HumanMessage(content=e.content, id=str(e.message_id))]}, config)
    assert output["messages"][-1].content == "找到一个项目"
    assert len(invoked) == 1 and invoked[0].tool_call_id == "native-call-1"
    assert invoked[0].arguments_hash == canonical_hash({"query": "项目"})
    assert len(broker.requests) == 2
    assert [m.role for m in broker.requests[1].messages] == ["system", "user", "assistant", "tool"]
    assert broker.requests[1].messages[-1].tool_call_id == "native-call-1"
    assert all(r.model_binding_id == e.model_binding_id for r in broker.requests)
    assert saver.get_tuple(config).checkpoint["channel_values"]["messages"][-1].content == "找到一个项目"


@pytest.mark.parametrize("args", [{"query": 7}, {"query": "ok", "unknown": 1}, {}])
def test_invalid_arguments_are_bounded_observation_without_host_action(args):
    e = execution()
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="project_search", arguments=args)]),
                           response(content="需要有效查询")])
    attempts = []
    def invalid_attempt(request):
        attempts.append(request)
        return CapabilityResponse(protocolVersion="ga-capability-result.v1", toolCallId=request.tool_call_id,
                                  status="FAILED", content={}, errorCode="TOOL_ARGUMENT_INVALID")
    graph, config, _ = setup_agent(e, broker, invalid_attempt)
    result = graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)
    tool = next(m for m in result["messages"] if isinstance(m, ToolMessage))
    assert tool.status == "error" and "TOOL_ARGUMENT_INVALID" in tool.content
    assert len(broker.requests) == 2
    assert len(attempts) == 1


def test_framework_budget_stops_instead_of_retrying():
    e = execution(budget=Budget(maxModelCalls=1))
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="project_search", arguments={"query": "x"})])])
    graph, config, _ = setup_agent(e, broker, lambda r: CapabilityResponse(
        protocolVersion="ga-capability-result.v1", toolCallId=r.tool_call_id, status="SUCCEEDED", content={}))
    with pytest.raises(Exception, match="[Mm]odel call limit"):
        graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)
    assert len(broker.requests) == 1


def test_user_input_ends_framework_run_without_follow_up_model():
    e = execution()
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="project_search", arguments={"query": "x"})])])
    graph, config, _ = setup_agent(e, broker, lambda r: CapabilityResponse(
        protocolVersion="ga-capability-result.v1", toolCallId=r.tool_call_id,
        status="USER_INPUT_REQUIRED", content={"question": "请选择项目"}))
    result = graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)
    assert isinstance(result["messages"][-1], ToolMessage)
    assert len(broker.requests) == 1


def test_repeated_tool_without_new_observation_ends_without_extra_model_call():
    e = execution()
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="project_search", arguments={"query": "x"})])])
    graph, config, _ = setup_agent(e, broker, lambda r: CapabilityResponse(
        protocolVersion="ga-capability-result.v1", toolCallId=r.tool_call_id,
        status="FAILED", content={}, errorCode="REPEATED_TOOL_CALL"))
    result = graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)
    assert isinstance(result["messages"][-1], ToolMessage)
    assert len(broker.requests) == 1


def test_parallel_model_calls_are_rejected_before_any_host_action():
    descriptor=Descriptor(capabilityId="project.search",version="1",name="project_search",description="local mutation fixture",
                          inputSchema={"query":{"type":"string","required":True}},readOnly=False,sideEffectClass="LOCAL_DURABLE")
    e = execution([descriptor])
    broker = ScriptBroker([response(calls=[ToolCall(id=f"c{i}", name="project_search", arguments={"query": "x"})
                                           for i in range(2)])])
    graph, config, _ = setup_agent(e, broker, lambda r: pytest.fail("parallel tool reached host"))
    with pytest.raises(BrokerError, match="parallel"):
        graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)


def test_unknown_tool_is_rejected_without_host_action():
    e = execution()
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="forbidden", arguments={})])])
    graph, config, _ = setup_agent(e, broker, lambda r: pytest.fail("unknown tool reached host"))
    with pytest.raises(BrokerError, match="catalog"):
        graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)


def test_cancel_after_model_result_prevents_tool_action():
    e = execution()
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="project_search", arguments={"query": "x"})])])

    def guard():
        if broker.requests:
            raise RuntimeError("CANCELLED")

    graph, config, _ = setup_agent(e, broker, lambda r: pytest.fail("cancelled tool reached host"), guard)
    with pytest.raises(RuntimeError, match="CANCELLED"):
        graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)
    assert len(broker.requests) == 1


def test_broker_http_is_one_attempt_and_does_not_follow_redirect():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(307, headers={"Location": "https://untrusted.example/key"})

    e = execution()
    client = httpx.Client(transport=httpx.MockTransport(handler))
    broker = NativeBroker("http://127.0.0.1:8080/internal/v1/global-assistant/model-inference", "internal-test", client=client)
    model = BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=1, leaseId=e.lease_id),
                            model_binding_id=e.model_binding_id, broker=broker, guard=lambda: None)
    with pytest.raises(BrokerError, match="307"):
        model.invoke([HumanMessage(content="test")])
    assert len(seen) == 1
    assert seen[0].headers["X-Spec-Agent-Internal-Token"] == "internal-test"
    assert "Authorization" not in seen[0].headers


def test_nested_schema_and_tool_name_mapping_fail_closed():
    schema = normalize_schema({"payload": {"type": "object", "required": True,
                                           "properties": {"count": {"type": "integer", "minimum": 1}},
                                           "additionalProperties": False}})
    assert schema["required"] == ["payload"]
    assert schema["properties"]["payload"]["additionalProperties"] is False
    assert tool_name("skill.import.discover") == "skill_import_discover"
    for invalid in [{"type": "object", "$ref": "https://untrusted/schema"},
                    {"type": "object", "additionalProperties": True},
                    {"type": "object", "properties": {"x": {"type": "array"}}}]:
        with pytest.raises(ValueError):
            normalize_schema(invalid)


def test_shared_model_golden_fixtures():
    root = Path(__file__).resolve().parents[2] / "contracts" / "global-assistant" / "fixtures"
    fixtures = list(root.glob("ga-model-*.json"))
    assert len(fixtures) >= 17
    for path in fixtures:
        cls = ModelResponse if "response" in path.name else ModelRequest
        if "-invalid-" in path.name:
            with pytest.raises(ValidationError):
                cls.model_validate_json(path.read_text(encoding="utf-8"))
        else:
            cls.model_validate_json(path.read_text(encoding="utf-8"))


def test_multiple_read_only_calls_keep_separate_arguments_results_and_follow_up():
    e = execution()
    broker = ScriptBroker([response(calls=[ToolCall(id="read-one", name="project_search", arguments={"query":"first"}),
        ToolCall(id="read-two", name="project_search", arguments={"query":"second"})]), response(content="done")])
    seen = []
    def invoke(request):
        seen.append((request.tool_call_id, request.arguments["query"]))
        return CapabilityResponse(protocolVersion="ga-capability-result.v1",toolCallId=request.tool_call_id,status="SUCCEEDED",content={"query":request.arguments["query"]},sourceRefs=[])
    graph, config, _ = setup_agent(e, broker, invoke)
    result = graph.invoke({"messages":[HumanMessage(content=e.content,id=str(e.message_id))]},config)
    assert sorted(seen) == [("read-one","first"),("read-two","second")]
    tools = [m for m in broker.requests[-1].messages if m.role=="tool"]
    assert {m.tool_call_id for m in tools} == {"read-one","read-two"}
    assert result["messages"][-1].content == "done"
