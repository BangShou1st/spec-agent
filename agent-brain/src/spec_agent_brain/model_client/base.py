"""Model client boundary of the brain.

The brain owns prompts and orchestration; the actual provider transport stays
in Java. Implementations either call the Java internal inference broker or
serve deterministic fake outputs for tests and offline development.
"""

from dataclasses import dataclass
from typing import List, Protocol, Sequence


@dataclass(frozen=True)
class ChatMessage:
    role: str  # "system" | "user"
    content: str


@dataclass(frozen=True)
class Completion:
    content: str
    finish_reason: str


class ModelClientError(RuntimeError):
    """Raised when model inference fails; never carries provider payloads."""


class ModelClient(Protocol):
    def complete(
        self,
        run_id: str,
        call_type: str,
        messages: Sequence[ChatMessage],
        max_output_tokens: int = 2048,
    ) -> Completion:
        """Runs one model completion for one durable run. No retry, no fallback."""
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
