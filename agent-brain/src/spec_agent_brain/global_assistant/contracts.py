"""Strict GA wire DTOs, independent from the Project Agent protocol."""

from typing import Any, Literal, Self
from uuid import UUID

from pydantic import Field, field_validator, model_validator
from ..wire import WireModel, canonical_hash


class ToolCall(WireModel):
    id: str = Field(min_length=1, max_length=128)
    name: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    arguments: dict[str, Any]

    @field_validator("id")
    @classmethod
    def nonblank_id(cls, value):
        if not value.strip():
            raise ValueError("blank tool call ID")
        return value


class Message(WireModel):
    role: Literal["system", "user", "assistant", "tool"]
    content: str = Field(max_length=131072)
    tool_calls: list[ToolCall] = Field(default_factory=list, alias="toolCalls", max_length=5)
    tool_call_id: str | None = Field(default=None, alias="toolCallId", max_length=128)

    @model_validator(mode="after")
    def role_fields(self) -> Self:
        if self.tool_calls and self.role != "assistant":
            raise ValueError("only assistant messages may carry tool calls")
        if (self.role == "tool") != bool(self.tool_call_id):
            raise ValueError("tool messages require toolCallId; other roles forbid it")
        if self.role != "assistant" and not self.content.strip():
            raise ValueError("message content must not be blank")
        if self.role == "assistant" and not (self.content.strip() or self.tool_calls):
            raise ValueError("assistant message requires content or calls")
        return self


class ToolDefinition(WireModel):
    name: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    description: str = Field(min_length=1, max_length=8192)
    parameters: dict[str, Any]

    @model_validator(mode="after")
    def definition(self) -> Self:
        if not self.description.strip() or self.parameters.get("type") != "object":
            raise ValueError("description and object schema required")
        return self


class RunScope(WireModel):
    run_id: UUID = Field(alias="runId")
    execution_epoch: int = Field(alias="executionEpoch", ge=1, le=9223372036854775807)
    lease_id: UUID = Field(alias="leaseId")


class ModelRequest(RunScope):
    protocol_version: Literal["ga-model-inference.v1"] = Field(alias="protocolVersion")
    call_id: UUID = Field(alias="callId")
    call_type: Literal["AGENT", "SUMMARY"] = Field(alias="callType")
    model_binding_id: UUID = Field(alias="modelBindingId")
    messages: list[Message] = Field(min_length=1, max_length=128)
    tools: list[ToolDefinition] = Field(default_factory=list, max_length=12)
    tool_choice: Literal["auto", "none", "required"] = Field(alias="toolChoice")
    max_output_tokens: int = Field(alias="maxOutputTokens", ge=1, le=8192)
    stream: bool = False

    @model_validator(mode="after")
    def conversation(self) -> Self:
        names = {tool.name for tool in self.tools}
        if len(names) != len(self.tools):
            raise ValueError("duplicate tool name")
        if self.tool_choice == "required" and not names:
            raise ValueError("required tool choice needs tools")
        if self.call_type == "SUMMARY" and (self.tools or self.tool_choice != "none" or self.stream):
            raise ValueError("summary cannot call tools or stream")
        seen: set[str] = set()
        pending: set[str] = set()
        for message in self.messages:
            if message.role == "tool":
                if message.tool_call_id not in pending:
                    raise ValueError("orphan or duplicate tool result")
                pending.remove(message.tool_call_id)
            else:
                if pending:
                    raise ValueError("unanswered tool calls")
                for call in message.tool_calls:
                    if call.id in seen:
                        raise ValueError("duplicate tool call id")
                    seen.add(call.id)
                    pending.add(call.id)
        if pending:
            raise ValueError("unfinished tool calls")
        if len(self.model_dump_json(by_alias=True).encode()) > 262144:
            raise ValueError("model request exceeds wire limit")
        return self


class Usage(WireModel):
    prompt_tokens: int = Field(alias="promptTokens", ge=0, le=2147483647)
    completion_tokens: int = Field(alias="completionTokens", ge=0, le=2147483647)


class ModelResponse(WireModel):
    protocol_version: Literal["ga-model-inference.v1"] = Field(alias="protocolVersion")
    content: str = Field(max_length=131072)
    tool_calls: list[ToolCall] = Field(default_factory=list, alias="toolCalls", max_length=5)
    finish_reason: Literal["stop", "tool_calls"] = Field(alias="finishReason")
    usage: Usage

    @model_validator(mode="after")
    def finish(self) -> Self:
        if bool(self.tool_calls) != (self.finish_reason == "tool_calls"):
            raise ValueError("finish reason disagrees with tool calls")
        if len({call.id for call in self.tool_calls}) != len(self.tool_calls):
            raise ValueError("duplicate tool call id")
        if not self.content.strip() and not self.tool_calls:
            raise ValueError("empty model response")
        return self


class Budget(WireModel):
    max_model_calls: int = Field(default=6, alias="maxModelCalls", ge=1, le=6)
    max_tool_calls: int = Field(default=5, alias="maxToolCalls", ge=1, le=5)
    max_duration_seconds: int = Field(default=180, alias="maxDurationSeconds", ge=1, le=180)


class Descriptor(WireModel):
    capability_id: str = Field(alias="capabilityId", min_length=1, max_length=128)
    version: str = Field(min_length=1, max_length=64)
    name: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    description: str = Field(min_length=1, max_length=8192)
    input_schema: dict[str, Any] = Field(alias="inputSchema")
    read_only: bool = Field(alias="readOnly")
    side_effect_class: Literal["NONE", "LOCAL_DURABLE"] = Field(alias="sideEffectClass")


class HistoryMessage(WireModel):
    message_id: UUID = Field(alias="messageId")
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=131072)


class UiContext(WireModel):
    current_page: str = Field(alias="currentPage", max_length=128)
    selected_entity: UUID | None = Field(default=None, alias="selectedEntity")


class ExecutionRequest(RunScope):
    protocol_version: Literal["ga-execution.v1"] = Field(alias="protocolVersion")
    engine_version: Literal["langchain-ga.v1"] = Field(alias="engineVersion")
    thread_id: UUID = Field(alias="threadId")
    message_id: UUID = Field(alias="messageId")
    content: str = Field(min_length=1, max_length=4000)
    model_binding_id: UUID = Field(alias="modelBindingId")
    policy_version: str = Field(alias="policyVersion", min_length=1, max_length=64)
    catalog_hash: str = Field(alias="catalogHash", pattern=r"^[a-f0-9]{64}$")
    history_boundary: UUID | None = Field(alias="historyBoundary")
    history: list[HistoryMessage] = Field(default_factory=list, max_length=128)
    ui_context: UiContext = Field(alias="uiContext")
    structured_refs: list[str] = Field(default_factory=list, alias="structuredRefs", max_length=64)
    capabilities: list[Descriptor] = Field(max_length=12)
    budget: Budget

    @model_validator(mode="after")
    def catalog(self) -> Self:
        if not self.content.strip():
            raise ValueError("blank request")
        for key in ("name", "capability_id"):
            if len({getattr(d, key) for d in self.capabilities}) != len(self.capabilities):
                raise ValueError("duplicate catalog identity")
        if canonical_hash([d.model_dump(by_alias=True) for d in self.capabilities]) != self.catalog_hash:
            raise ValueError("catalog hash mismatch")
        ids = [m.message_id for m in self.history]
        if len(set(ids)) != len(ids) or self.message_id in ids:
            raise ValueError("duplicate message identity")
        if self.history and self.history_boundary != self.history[-1].message_id:
            raise ValueError("history boundary mismatch")
        if not self.history and self.history_boundary is not None:
            raise ValueError("history boundary without history")
        if len(self.model_dump_json(by_alias=True).encode()) > 262144:
            raise ValueError("execution request exceeds wire limit")
        return self


class CapabilityRequest(RunScope):
    protocol_version: Literal["ga-capability-invocation.v1"] = Field(alias="protocolVersion")
    tool_call_id: str = Field(alias="toolCallId", min_length=1, max_length=128)
    capability_id: str = Field(alias="capabilityId", min_length=1, max_length=128)
    descriptor_version: str = Field(alias="descriptorVersion", min_length=1, max_length=64)
    catalog_hash: str = Field(alias="catalogHash", pattern=r"^[a-f0-9]{64}$")
    arguments: dict[str, Any]
    arguments_hash: str = Field(alias="argumentsHash", pattern=r"^[a-f0-9]{64}$")

    @model_validator(mode="after")
    def hash_arguments(self) -> Self:
        if canonical_hash(self.arguments) != self.arguments_hash:
            raise ValueError("arguments hash mismatch")
        return self


class CapabilityResponse(WireModel):
    protocol_version: Literal["ga-capability-result.v1"] = Field(alias="protocolVersion")
    tool_call_id: str = Field(alias="toolCallId", min_length=1, max_length=128)
    status: Literal["SUCCEEDED", "FAILED", "USER_INPUT_REQUIRED", "APPROVAL_REQUIRED"]
    content: dict[str, Any]
    source_refs: list[str] = Field(default_factory=list, alias="sourceRefs", max_length=64)
    error_code: str | None = Field(default=None, alias="errorCode", max_length=128)


class StatusPayload(WireModel):
    stage: Literal["EXECUTING"]


class TextPayload(WireModel):
    text: str = Field(min_length=1, max_length=131072)

    @field_validator("text")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("blank final text")
        return value


class FailurePayload(WireModel):
    error_code: Literal["GA_EXECUTION_FAILED", "GA_EXECUTION_PROTOCOL_ERROR", "GA_REPEATED_TOOL_CALL"] = Field(alias="errorCode")


class DraftPayload(WireModel):
    call_id: UUID = Field(alias="callId")
    text: str = Field(min_length=1, max_length=131072)


class ResetPayload(WireModel):
    call_id: UUID = Field(alias="callId")


class ModelStreamFailure(WireModel):
    error_code: Literal["GA_MODEL_STREAM_FAILED"] = Field(alias="errorCode")


class ModelDeltaPayload(WireModel):
    text: str = Field(min_length=1, max_length=131072)


class ModelStreamEvent(WireModel):
    protocol_version: Literal["ga-model-stream.v1"] = Field(alias="protocolVersion")
    call_id: UUID = Field(alias="callId")
    sequence: int = Field(ge=1, le=8192)
    type: Literal["TEXT_DELTA", "COMPLETED", "FAILED"]
    payload: ModelDeltaPayload | ModelResponse | ModelStreamFailure

    @model_validator(mode="after")
    def payload_type(self) -> Self:
        expected = {"TEXT_DELTA": ModelDeltaPayload, "COMPLETED": ModelResponse, "FAILED": ModelStreamFailure}[self.type]
        if not isinstance(self.payload, expected):
            raise ValueError("model stream payload mismatch")
        return self


class ExecutionEvent(WireModel):
    protocol_version: Literal["ga-execution-event.v1"] = Field(alias="protocolVersion")
    run_id: UUID = Field(alias="runId")
    execution_epoch: int = Field(alias="executionEpoch", ge=1, le=9223372036854775807)
    event_id: UUID = Field(alias="eventId")
    sequence: int = Field(ge=1, le=8192)
    type: Literal["STATUS", "TEXT_DELTA", "TEXT_RESET", "COMPLETED", "USER_INPUT_REQUIRED", "FAILED"]
    payload: StatusPayload | TextPayload | FailurePayload | DraftPayload | ResetPayload

    @model_validator(mode="after")
    def payload_type(self) -> Self:
        expected = {"STATUS": StatusPayload, "COMPLETED": TextPayload,
                    "USER_INPUT_REQUIRED": TextPayload, "FAILED": FailurePayload,
                    "TEXT_DELTA": DraftPayload, "TEXT_RESET": ResetPayload}[self.type]
        if not isinstance(self.payload, expected):
            raise ValueError("execution event payload mismatch")
        return self
