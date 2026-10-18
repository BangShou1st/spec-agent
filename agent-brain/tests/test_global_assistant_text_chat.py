"""TEXT_CHAT capability mode: the selected model chats normally without native tools."""

from uuid import uuid4

import pytest
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver

from spec_agent_brain.global_assistant.agent import TEXT_CHAT_PROMPT, build_agent
from spec_agent_brain.global_assistant.contracts import (
    Budget, CapabilityResponse, Descriptor, ExecutionRequest, ModelResponse, RunScope, ToolCall, Usage,
    canonical_hash,
)
from spec_agent_brain.global_assistant.model_adapter import BrokerChatModel


def text_execution(budget=None):
    """执行信封的空能力目录 = TEXT_CHAT:没有工具目录,也没有 HostTools 执行能力。"""
    return ExecutionRequest(protocolVersion="ga-execution.v1", engineVersion="langchain-ga.v1",
                            runId=uuid4(), executionEpoch=1, leaseId=uuid4(), threadId=uuid4(),
                            messageId=uuid4(), content="你好,请自我介绍", modelBindingId=uuid4(),
                            policyVersion="1", catalogHash=canonical_hash([]),
                            historyBoundary=None, history=[], uiContext={"currentPage": "PROJECTS"},
                            capabilities=[], budget=budget or Budget())


def response(content="", calls=()):
    return ModelResponse(protocolVersion="ga-model-inference.v1", content=content, toolCalls=list(calls),
                         finishReason="tool_calls" if calls else "stop",
                         usage=Usage(promptTokens=7, completionTokens=3))


class ScriptBroker:
    def __init__(self, replies):
        self.replies = iter(replies)
        self.requests = []

    def complete(self, request):
        self.requests.append(request)
        return next(self.replies)


def text_model(e, broker, guard=lambda: None):
    return BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=e.execution_epoch, leaseId=e.lease_id),
                           model_binding_id=e.model_binding_id, broker=broker, guard=guard)


def text_agent(e, broker, invoke, guard=lambda: None):
    model = text_model(e, broker)
    graph = build_agent(e, model, saver=InMemorySaver(), invoke=invoke, guard=guard)
    return graph, {"configurable": {"thread_id": str(e.thread_id)}, "recursion_limit": 50}


def test_text_chat_run_builds_no_tool_catalog_and_returns_plain_text():
    e = text_execution()
    broker = ScriptBroker([response(content="我是一个应用助手")])

    def invoke(request):
        pytest.fail("TEXT_CHAT must never reach the host capability broker")

    graph, config = text_agent(e, broker, invoke)
    result = graph.invoke({"messages": [HumanMessage(content=e.content, id=str(e.message_id))]}, config)

    assert result["messages"][-1].content == "我是一个应用助手"
    assert len(broker.requests) == 1
    request = broker.requests[0]
    # 不发送工具声明,也不发送 auto/required 工具选择。
    assert request.tools == []
    assert request.tool_choice == "none"


def test_text_chat_system_prompt_never_claims_workspace_tools():
    e = text_execution()
    broker = ScriptBroker([response(content="好的")])
    graph, config = text_agent(e, broker, lambda r: pytest.fail("no host tools"))
    graph.invoke({"messages": [HumanMessage(content=e.content)]}, config)

    system = broker.requests[0].messages[0].content
    assert TEXT_CHAT_PROMPT.strip() in system
    assert "no workspace tools" in system
    assert "Use only approved tools" not in system


def test_text_chat_never_sends_tool_choice_auto():
    # 即便上游绑定层传入 auto/required,纯文本模式也一律收敛为 none。
    e = text_execution()
    broker = ScriptBroker([response(content="hi")])
    model = text_model(e, broker)
    model.invoke([HumanMessage(content="hi")], tools=[], tool_choice="auto")
    assert broker.requests[0].tool_choice == "none"
    assert broker.requests[0].tools == []


def test_text_chat_history_with_tool_roles_is_sanitized_without_fabrication():
    e = text_execution()
    broker = ScriptBroker([response(content="我现在无法查询工作区")])
    model = text_model(e, broker)
    history = [
        SystemMessage(content="sys"),
        HumanMessage(content="上一个问题"),
        AIMessage(content="", tool_calls=[{"id": "t1", "name": "project_search",
                                           "args": {"query": "x"}, "type": "tool_call"}]),
        ToolMessage(content='{"status":"SUCCEEDED"}', tool_call_id="t1"),
        HumanMessage(content="现在还能查项目吗"),
    ]
    model.invoke(history)

    sent = broker.requests[0].messages
    assert [m.role for m in sent] == ["system", "user", "user"]
    assert all(not m.tool_calls for m in sent)
    assert all(m.tool_call_id is None for m in sent)
    assert "SUCCEEDED" not in "".join(m.content for m in sent)


def test_text_chat_summary_path_stays_tool_free():
    e = text_execution()
    broker = ScriptBroker([response(content="摘要")])
    summary_model = text_model(e, broker).model_copy(update={"call_type": "SUMMARY"})
    summary_model.invoke([SystemMessage(content="summarize"), HumanMessage(content="对话")])

    request = broker.requests[0]
    assert request.call_type == "SUMMARY"
    assert request.tools == []
    assert request.tool_choice == "none"


def test_text_chat_supports_two_consecutive_turns():
    e = text_execution()
    broker = ScriptBroker([response(content="第一轮"), response(content="第二轮")])
    graph, config = text_agent(e, broker, lambda r: pytest.fail("no host tools"))
    first = graph.invoke({"messages": [HumanMessage(content="第一问")]}, config)
    assert first["messages"][-1].content == "第一轮"
    second = graph.invoke({"messages": [HumanMessage(content="第二问")]}, config)
    assert second["messages"][-1].content == "第二轮"
    assert len(broker.requests) == 2


def test_native_tools_run_still_binds_and_dispatches_the_catalog():
    # 对照:NATIVE_TOOLS 仍然提交工具目录并通过 HostTools 执行(原有功能不回归)。
    descriptor = Descriptor(capabilityId="project.search", version="1", name="project_search",
                            description="Find projects", inputSchema={"query": {"type": "string", "required": True}},
                            readOnly=True, sideEffectClass="NONE")
    e = ExecutionRequest(protocolVersion="ga-execution.v1", engineVersion="langchain-ga.v1",
                         runId=uuid4(), executionEpoch=1, leaseId=uuid4(), threadId=uuid4(), messageId=uuid4(),
                         content="找项目", modelBindingId=uuid4(), policyVersion="1",
                         catalogHash=canonical_hash([descriptor.model_dump(by_alias=True)]),
                         historyBoundary=None, history=[], uiContext={"currentPage": "PROJECTS"},
                         capabilities=[descriptor], budget=Budget())
    broker = ScriptBroker([response(calls=[ToolCall(id="c1", name="project_search", arguments={"query": "x"})]),
                           response(content="找到了")])
    seen = []

    def invoke(request):
        seen.append(request)
        return CapabilityResponse(protocolVersion="ga-capability-result.v1", toolCallId=request.tool_call_id,
                                  status="SUCCEEDED", content={"projects": []}, sourceRefs=[])

    model = text_model(e, broker)
    graph = build_agent(e, model, saver=InMemorySaver(), invoke=invoke, guard=lambda: None)
    config = {"configurable": {"thread_id": str(e.thread_id)}, "recursion_limit": 50}
    result = graph.invoke({"messages": [HumanMessage(content=e.content, id=str(e.message_id))]}, config)

    assert result["messages"][-1].content == "找到了"
    assert len(seen) == 1
    assert [tool.name for tool in broker.requests[0].tools] == ["project_search"]
    assert broker.requests[0].tool_choice == "auto"
