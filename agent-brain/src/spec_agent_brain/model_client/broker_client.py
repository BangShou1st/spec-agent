"""Model client that calls the Java internal inference broker.

Safety properties: the client sends only the shared internal token (never a
provider key, because it never has one), posts to one fixed configured URL,
performs no retry and no fallback, and raises a typed error on any failure.
"""

from typing import Optional, Sequence

import httpx

from .base import ChatMessage, Completion, ModelClientError, require_call_type, require_roles


class BrokerModelClient:
    def __init__(
        self,
        broker_url: str,
        internal_secret: str,
        timeout_seconds: float = 120.0,
        http_client: Optional[httpx.Client] = None,
    ):
        self._broker_url = broker_url
        self._internal_secret = internal_secret
        self._client = http_client or httpx.Client(timeout=timeout_seconds)

    def complete(
        self,
        run_id: str,
        call_type: str,
        messages: Sequence[ChatMessage],
        max_output_tokens: int = 2048,
    ) -> Completion:
        require_call_type(call_type)
        require_roles(messages)
        payload = {
            "protocolVersion": "model-inference.v1",
            "runId": run_id,
            "callType": call_type,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "maxOutputTokens": max_output_tokens,
        }
        headers = {"X-Spec-Agent-Internal-Token": self._internal_secret}
        try:
            response = self._client.post(self._broker_url, json=payload, headers=headers)
        except httpx.HTTPError as exc:
            raise ModelClientError(
                f"inference broker unreachable: {type(exc).__name__}") from exc
        if response.status_code != 200:
            raise ModelClientError(
                f"inference broker returned status {response.status_code}")
        body = response.json()
        return Completion(
            content=body.get("content", ""), finish_reason=body.get("finishReason", ""))
