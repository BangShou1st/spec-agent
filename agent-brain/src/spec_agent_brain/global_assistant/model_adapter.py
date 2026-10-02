"""LangChain chat model backed only by the separate, credential-free Java broker."""

import asyncio
from typing import Any, Callable, Sequence, Literal
from uuid import UUID, uuid4

import httpx
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.utils.function_calling import convert_to_openai_tool
from pydantic import Field

from .contracts import Message, ModelRequest, ModelResponse, ModelStreamEvent, RunScope, ToolCall, ToolDefinition
from .schema import normalize_schema


class BrokerError(RuntimeError):
    """Sanitized broker error; never contains response body or credentials."""


class NativeBroker:
    def __init__(self, url: str, secret: str, *, client: httpx.Client | None = None):
        parsed = httpx.URL(url)
        if parsed.scheme not in {"http", "https"} or parsed.userinfo or parsed.query or parsed.fragment:
            raise ValueError("invalid configured broker URL")
        if parsed.path != "/internal/v1/global-assistant/model-inference" or not secret:
            raise ValueError("GA broker endpoint and internal secret required")
        self.url = url
        self.secret = secret
        self.client = client or httpx.Client(timeout=180, trust_env=False, follow_redirects=False,
                                             transport=httpx.HTTPTransport(retries=0, trust_env=False))

    def complete(self, request: ModelRequest) -> ModelResponse:
        if request.stream:
            raise BrokerError("UNSUPPORTED_AGENT_MODEL: typed streaming not implemented")
        try:
            with self.client.stream("POST", self.url, content=request.model_dump_json(by_alias=True),
                                    headers={"X-Spec-Agent-Internal-Token": self.secret,
                                             "Content-Type": "application/json"},
                                    follow_redirects=False) as response:
                if response.status_code != 200:
                    raise BrokerError(f"GA broker status {response.status_code}")
                chunks = bytearray()
                for chunk in response.iter_bytes():
                    chunks.extend(chunk)
                    if len(chunks) > 262144:
                        raise BrokerError("GA broker response exceeds limit")
                return ModelResponse.model_validate_json(bytes(chunks))
        except httpx.HTTPError as exc:
            raise BrokerError(f"GA broker transport {type(exc).__name__}") from None
        except ValueError:
            raise BrokerError("GA broker response violates contract") from None

    def close(self) -> None:
        self.client.close()

    def stream(self, request: ModelRequest, sink: Callable) -> ModelResponse:
        if not request.stream or request.call_type != "AGENT":
            raise BrokerError("invalid streaming call")
        sequence, total, terminal, candidate = 0, 0, None, ""
        pending = bytearray()
        try:
            with self.client.stream("POST", self.url + "/stream", content=request.model_dump_json(by_alias=True),
                    headers={"X-Spec-Agent-Internal-Token": self.secret, "Content-Type": "application/json",
                             "Accept": "application/x-ndjson"}, follow_redirects=False) as response:
                if response.status_code != 200 or not response.headers.get("content-type", "").startswith("application/x-ndjson"):
                    raise BrokerError(f"GA model stream rejected status={response.status_code} typedContent={response.headers.get('content-type', '').startswith('application/x-ndjson')}")
                for part in response.iter_bytes():
                    total += len(part)
                    if total > 2097152:
                        raise BrokerError("GA model stream limit")
                    pending.extend(part)
                    while b"\n" in pending:
                        line, _, remainder = pending.partition(b"\n")
                        pending = bytearray(remainder)
                        if len(line) > 262144:
                            raise BrokerError("GA model frame limit")
                        event = ModelStreamEvent.model_validate_json(line)
                        sequence += 1
                        if terminal is not None or event.call_id != request.call_id or event.sequence != sequence:
                            raise BrokerError("GA model stream order/scope")
                        if event.type == "TEXT_DELTA":
                            candidate += event.payload.text
                            if len(candidate) > 131072:
                                raise BrokerError("GA candidate limit")
                            sink("TEXT_DELTA", {"callId": str(request.call_id), "text": event.payload.text})
                        elif event.type == "FAILED":
                            raise BrokerError("GA model stream failed")
                        else:
                            terminal = event.payload
                            if terminal.tool_calls:
                                if candidate:
                                    sink("TEXT_RESET", {"callId": str(request.call_id)})
                            elif candidate != terminal.content:
                                # A replay may have no deltas, but a partial/forged draft must not be accepted.
                                if candidate:
                                    raise BrokerError("GA model stream content mismatch")
                                sink("TEXT_DELTA", {"callId": str(request.call_id), "text": terminal.content})
                    if len(pending) > 262144:
                        raise BrokerError("GA model frame limit")
                if pending or terminal is None:
                    raise BrokerError("GA truncated model stream")
                return terminal
        except httpx.HTTPError as exc:
            raise BrokerError(f"GA broker transport {type(exc).__name__}") from None
        except ValueError:
            raise BrokerError("GA model stream violates contract") from None


class BrokerChatModel(BaseChatModel):
    scope: RunScope
    batch_read_only_names: set[str] = Field(default_factory=set)
    model_binding_id: UUID
    broker: Any = Field(exclude=True)
    guard: Callable[[], None] = Field(exclude=True)
    max_output_tokens: int = 8192
    call_type: Literal["AGENT", "SUMMARY"] = "AGENT"
    stream_sink: Callable | None = Field(default=None, exclude=True)

    @property
    def _llm_type(self) -> str:
        return "spec-agent-ga-native-broker"

    def bind_tools(self, tools: Sequence[Any], *, tool_choice: str | None = None, **kwargs: Any):
        if kwargs:
            raise ValueError("unsupported model binding options")
        definitions = []
        for tool in tools:
            converted = convert_to_openai_tool(tool)["function"]
            definitions.append(ToolDefinition(name=converted["name"], description=converted.get("description", ""),
                                               parameters=normalize_schema(converted["parameters"])))
        return self.bind(tools=definitions, tool_choice=tool_choice or "auto")

    def _generate(self, messages: list[BaseMessage], stop=None, run_manager=None, **kwargs) -> ChatResult:
        if stop or set(kwargs) - {"tools", "tool_choice"}:
            raise ValueError("unsupported model generation options")
        self.guard()
        wire = []
        for message in messages:
            if not isinstance(message.content, str):
                raise ValueError("only text GA messages are supported")
            if isinstance(message, AIMessage):
                if message.invalid_tool_calls:
                    raise ValueError("invalid historical tool calls")
                wire.append(Message(role="assistant", content=message.content,
                                    toolCalls=[ToolCall(id=c["id"], name=c["name"], arguments=c["args"])
                                               for c in message.tool_calls]))
            elif isinstance(message, ToolMessage):
                wire.append(Message(role="tool", content=message.content, toolCallId=message.tool_call_id))
            elif isinstance(message, (SystemMessage, HumanMessage)):
                wire.append(Message(role="system" if isinstance(message, SystemMessage) else "user",
                                    content=message.content))
            else:
                raise ValueError("unsupported GA message role")
        tools = kwargs.get("tools", [])
        request = ModelRequest(protocolVersion="ga-model-inference.v1", **self.scope.model_dump(by_alias=True),
                               callId=uuid4(), callType=self.call_type, modelBindingId=self.model_binding_id,
                               messages=wire, tools=tools, toolChoice=kwargs.get("tool_choice", "none"),
                               maxOutputTokens=self.max_output_tokens,
                               stream=self.stream_sink is not None and self.call_type == "AGENT")
        response = self.broker.stream(request, self.stream_sink) if request.stream else self.broker.complete(request)
        self.guard()
        # A provider may ignore parallel_tool_calls=false. Only frozen read-only
        # business tools may share a response; writes and interaction control
        # remain single-call, rejected before dispatch rather than racing effects.
        if len(response.tool_calls) > 1 and any(c.name not in self.batch_read_only_names for c in response.tool_calls):
            raise BrokerError("parallel writes or interaction tools are unsupported")
        allowed = {tool.name for tool in tools}
        if any(call.name not in allowed for call in response.tool_calls):
            raise BrokerError("model requested a tool outside the bound catalog")
        ai = AIMessage(content=response.content,
                       tool_calls=[{"id": c.id, "name": c.name, "args": c.arguments, "type": "tool_call"}
                                   for c in response.tool_calls],
                       usage_metadata={"input_tokens": response.usage.prompt_tokens,
                                       "output_tokens": response.usage.completion_tokens,
                                       "total_tokens": response.usage.prompt_tokens + response.usage.completion_tokens})
        return ChatResult(generations=[ChatGeneration(message=ai)],
                          llm_output={"finish_reason": response.finish_reason})

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        return await asyncio.to_thread(self._generate, messages, stop, None, **kwargs)
