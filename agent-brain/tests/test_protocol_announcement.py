"""文件名:test_protocol_announcement.py

用途:锁定 /health 能力公告与实际支持的协议版本一致(单一事实来源)。

公告不是文档装饰:Java 的 GaExecutionCoordinator 在发起执行前读取
``gaExecutionProtocolVersions`` 并与必需版本求交。本文件用行为级断言
保证"公告的每个版本都被对应 DTO 实际接受、未公告的版本被拒绝",
而不是仅比对字符串列表。
"""

import json
import uuid as uuid_module

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from spec_agent_brain.app import create_app
from spec_agent_brain.contracts.protocol import INFERENCE_PROTOCOL_VERSION, WORKSPACE_RPC_GRANT_VERSION
from spec_agent_brain.global_assistant.contracts import (
    Budget,
    Descriptor,
    ExecutionEvent,
    ExecutionRequest,
    GA_EXECUTION_EVENT_PROTOCOL_VERSION,
    GA_EXECUTION_PROTOCOL_VERSION,
    GA_EXECUTION_PROTOCOL_VERSIONS,
)
from spec_agent_brain.wire import canonical_hash


def _execution_request(protocol_version: str) -> ExecutionRequest:
    descriptors = [Descriptor(capabilityId="project.search", version="1",
                              name="project_search", description="Find projects",
                              inputSchema={"query": {"type": "string", "required": True}},
                              readOnly=True, sideEffectClass="NONE")]
    return ExecutionRequest(
        protocolVersion=protocol_version, engineVersion="langchain-ga.v1",
        runId=uuid_module.uuid4(), executionEpoch=1, leaseId=uuid_module.uuid4(),
        threadId=uuid_module.uuid4(), messageId=uuid_module.uuid4(),
        content="查找我的项目", modelBindingId=uuid_module.uuid4(),
        policyVersion="1",
        catalogHash=canonical_hash([d.model_dump(by_alias=True) for d in descriptors]),
        historyBoundary=None, history=[], uiContext={"currentPage": "PROJECTS"},
        capabilities=descriptors, budget=Budget())


def _execution_event(protocol_version: str) -> ExecutionEvent:
    return ExecutionEvent(
        protocolVersion=protocol_version, runId=uuid_module.uuid4(),
        executionEpoch=1, eventId=uuid_module.uuid4(), sequence=1,
        type="STATUS", payload={"stage": "EXECUTING"})


def test_health_announces_exactly_the_declared_execution_protocol_constants(settings):
    body = TestClient(create_app(settings)).get("/health").json()

    assert body["gaExecutionProtocolVersions"] == [
        GA_EXECUTION_PROTOCOL_VERSION, GA_EXECUTION_EVENT_PROTOCOL_VERSION]
    assert tuple(body["gaExecutionProtocolVersions"]) == GA_EXECUTION_PROTOCOL_VERSIONS
    assert body["workspaceRpcGrantVersions"] == [WORKSPACE_RPC_GRANT_VERSION]
    assert body["protocolVersion"] == "agent-input.v2"


def test_every_announced_execution_protocol_is_actually_accepted_by_its_dto():
    # 公告的版本必须真的能过对应 DTO 的严格校验,否则公告在撒谎。
    assert _execution_request(GA_EXECUTION_PROTOCOL_VERSION).protocol_version \
        == GA_EXECUTION_PROTOCOL_VERSION
    assert _execution_event(GA_EXECUTION_EVENT_PROTOCOL_VERSION).protocol_version \
        == GA_EXECUTION_EVENT_PROTOCOL_VERSION


def test_unannounced_execution_protocol_versions_are_rejected_by_the_dtos():
    with pytest.raises(ValidationError):
        _execution_request("ga-execution.v999")
    with pytest.raises(ValidationError):
        _execution_event("ga-execution-event.v999")
    # 公告绝不能夹带 Java 不认识的代际(如 Java 内部的 ga-model-inference.v1)。
    assert "ga-model-inference.v1" not in GA_EXECUTION_PROTOCOL_VERSIONS


def test_broker_requests_send_the_declared_inference_protocol_version():
    import httpx

    from spec_agent_brain.model_client import BrokerModelClient, ChatMessage

    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(200, json={"protocolVersion": INFERENCE_PROTOCOL_VERSION,
                                         "content": "{}", "finishReason": "stop"})

    client = BrokerModelClient(
        "http://broker.invalid/internal/v1/model-inference", "shared-secret",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        execution_grant="x" * 43)
    client.complete(str(uuid_module.uuid4()), "DECISION",
                    [ChatMessage(role="system", content="s"),
                     ChatMessage(role="user", content="u")])

    assert captured["body"]["protocolVersion"] == INFERENCE_PROTOCOL_VERSION \
        == "model-inference.v1"
