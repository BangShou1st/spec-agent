"""Bounded authenticated host RPC. No database, provider key or hidden retry."""

from typing import Any, Literal

import httpx
from pydantic import Field

from spec_agent_brain.wire import WireModel
from .contracts import CapabilityRequest, CapabilityResponse


class CheckpointReply(WireModel):
    protocol_version: Literal["ga-checkpoint.v1"] = Field(alias="protocolVersion")
    version: int = Field(ge=0)
    result: Any


class HostRpcError(RuntimeError):
    pass


class CheckpointRpc:
    def __init__(self, url: str, secret: str, *, client: httpx.Client | None = None):
        parsed = httpx.URL(url)
        if (parsed.scheme not in {"http", "https"} or parsed.userinfo or parsed.query or parsed.fragment
                or parsed.path != "/internal/v1/global-assistant/checkpoints" or not secret):
            raise ValueError("configured checkpoint endpoint and internal secret required")
        self.url = url
        self.secret = secret
        self.client = client or httpx.Client(timeout=30, trust_env=False, follow_redirects=False,
                                             transport=httpx.HTTPTransport(retries=0, trust_env=False))

    def __call__(self, operation: str, envelope: dict) -> dict:
        if operation not in {"GET", "LIST", "PUT", "PUT_WRITES", "DELETE_THREAD"}:
            raise ValueError("unknown checkpoint operation")
        import json
        body = json.dumps(envelope, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
        if len(body) > 4194304:
            raise HostRpcError("checkpoint request limit")
        try:
            with self.client.stream("POST", self.url + "/" + operation, content=body,
                                    headers={"X-Spec-Agent-Internal-Token": self.secret,
                                             "Content-Type": "application/json"}, follow_redirects=False) as response:
                if response.status_code != 200:
                    raise HostRpcError(f"checkpoint host status {response.status_code}")
                value = bytearray()
                for chunk in response.iter_bytes():
                    value.extend(chunk)
                    if len(value) > 4194304:
                        raise HostRpcError("checkpoint response limit")
                return CheckpointReply.model_validate_json(bytes(value)).model_dump(by_alias=True)
        except httpx.HTTPError as ex:
            raise HostRpcError(f"checkpoint transport {type(ex).__name__}") from None
        except ValueError:
            raise HostRpcError("checkpoint host contract violation") from None

    def close(self):
        self.client.close()


class CapabilityRpc:
    def __init__(self, url: str, secret: str, *, client: httpx.Client | None = None):
        parsed = httpx.URL(url)
        if (parsed.scheme not in {"http", "https"} or parsed.userinfo or parsed.query or parsed.fragment
                or parsed.path != "/internal/v1/global-assistant/capabilities" or not secret):
            raise ValueError("configured capability endpoint and internal secret required")
        self.url, self.secret = url, secret
        self.client = client or httpx.Client(timeout=180, trust_env=False, follow_redirects=False,
                                             transport=httpx.HTTPTransport(retries=0, trust_env=False))

    def __call__(self, request: CapabilityRequest) -> CapabilityResponse:
        body = request.model_dump_json(by_alias=True).encode()
        if len(body) > 262144:
            raise HostRpcError("capability request limit")
        try:
            with self.client.stream("POST", self.url, content=body,
                                    headers={"X-Spec-Agent-Internal-Token": self.secret,
                                             "Content-Type": "application/json"}, follow_redirects=False) as response:
                if response.status_code != 200:
                    raise HostRpcError(f"capability host status {response.status_code}")
                value = bytearray()
                for chunk in response.iter_bytes():
                    value.extend(chunk)
                    if len(value) > 262144:
                        raise HostRpcError("capability response limit")
                return CapabilityResponse.model_validate_json(bytes(value))
        except httpx.HTTPError as ex:
            raise HostRpcError(f"capability transport {type(ex).__name__}") from None
        except ValueError:
            raise HostRpcError("capability host contract violation") from None

    def close(self):
        self.client.close()
