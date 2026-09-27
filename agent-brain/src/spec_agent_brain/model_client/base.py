"""文件名:base.py

用途:brain 的模型客户端边界。

Brain 负责编写 prompt 与流程编排;真正的厂商传输留在 Java 侧。实现要么
调用 Java 内部推理 broker,要么为测试与离线开发提供确定性的 fake 输出。
"""

from dataclasses import dataclass
from typing import List, Protocol, Sequence


@dataclass(frozen=True)
class ChatMessage:
    role: str  # "system" 或 "user"
    content: str


@dataclass(frozen=True)
class Completion:
    content: str
    finish_reason: str


class ModelClientError(RuntimeError):
    """模型推理失败时抛出;绝不携带厂商的原始 payload。"""


class BrokerTimeoutError(ModelClientError):
    """broker 调用超时(连接或读取)时抛出。

    契约与 ModelClientError 相同,但让 HTTP 边界能把超时与普通厂商失败
    区分开。
    """


class ModelClient(Protocol):
    def complete(
        self,
        run_id: str,
        call_type: str,
        messages: Sequence[ChatMessage],
        max_output_tokens: int = 2048,
    ) -> Completion:
        """为一次持久化 run 执行一次模型补全。不重试、不回退。"""
        ...


def require_call_type(call_type: str) -> None:
    from ..contracts import protocol

    if call_type not in protocol.CALL_TYPES:
        raise ModelClientError(f"unsupported call type: {call_type}")


def require_roles(messages: Sequence[ChatMessage]) -> None:
    for message in messages:
        if message.role not in ("system", "user"):
            raise ModelClientError(f"unsupported message role: {message.role}")


def render_messages_text(messages: List[ChatMessage]) -> str:  # pragma: no cover - helper
    return "\n".join(f"{m.role}\n{m.content}" for m in messages)
