"""Typed streaming isolation and fail-closed framing; no live provider."""
import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest

from spec_agent_brain.global_assistant.contracts import ModelRequest, ToolCall
from spec_agent_brain.global_assistant.model_adapter import NativeBroker, BrokerError
from test_global_assistant_langchain import response


def request():
    fixture = Path(__file__).resolve().parents[2] / "contracts/global-assistant/fixtures/ga-model-request-valid.json"
    value = json.loads(fixture.read_text(encoding="utf-8"))
    value["stream"] = True
    return ModelRequest.model_validate_json(json.dumps(value))


def frame(req, sequence, kind, payload):
    return (json.dumps({"protocolVersion": "ga-model-stream.v1", "callId": str(req.call_id),
                       "sequence": sequence, "type": kind, "payload": payload}, ensure_ascii=False) + "\n").encode()


def broker(parts):
    class Stream(httpx.SyncByteStream):
        def __iter__(self):
            yield from parts
    def handle(req):
        assert req.url.path.endswith("/model-inference/stream")
        assert req.headers["X-Spec-Agent-Internal-Token"] == "internal-test"
        return httpx.Response(200, headers={"content-type": "application/x-ndjson"}, stream=Stream())
    return NativeBroker("http://host/internal/v1/global-assistant/model-inference", "internal-test",
                        client=httpx.Client(transport=httpx.MockTransport(handle)))


def test_deltas_arrive_before_final_frame_with_split_utf8_and_whitespace():
    req, seen = request(), []
    final = response(content="中文 回答").model_dump(mode="json", by_alias=True)
    def parts():
        for index, text in enumerate(["中文", " ", "回答"], 1):
            raw = frame(req, index, "TEXT_DELTA", {"text": text})
            for byte in raw:
                yield bytes([byte])
            assert len(seen) == index
        yield frame(req, 4, "COMPLETED", final)
    client = broker(parts())
    try:
        result = client.stream(req, lambda kind, payload: seen.append((kind, payload)))
        assert result.content == "中文 回答"
        assert "".join(payload["text"] for _, payload in seen) == result.content
    finally:
        client.close()


def test_tool_arguments_never_become_text_and_candidate_is_retracted():
    req, seen = request(), []
    final = response(content="先查一下", calls=[ToolCall(id="c1", name="project_search",
                     arguments={"query": "private-argument"})]).model_dump(mode="json", by_alias=True)
    client = broker([frame(req, 1, "TEXT_DELTA", {"text": "先查一下"}), frame(req, 2, "COMPLETED", final)])
    try:
        result = client.stream(req, lambda kind, payload: seen.append((kind, payload)))
        assert [kind for kind, _ in seen] == ["TEXT_DELTA", "TEXT_RESET"]
        assert "private-argument" not in json.dumps(seen)
        assert result.tool_calls[0].arguments["query"] == "private-argument"
    finally:
        client.close()


@pytest.mark.parametrize("case", ["truncated", "sequence", "scope", "after-terminal", "mismatch", "failed", "fields", "invalid-utf8"])
def test_invalid_or_failed_stream_never_yields_success(case):
    req = request()
    first = frame(req, 1, "TEXT_DELTA", {"text": "回答"})
    last = frame(req, 2, "COMPLETED", response(content="回答").model_dump(mode="json", by_alias=True))
    if case == "truncated": last = last[:-1]
    if case == "sequence": last = last.replace(b'"sequence": 2', b'"sequence": 3')
    if case == "scope": last = last.replace(str(req.call_id).encode(), str(uuid4()).encode())
    if case == "after-terminal": last += first
    if case == "mismatch": last = frame(req, 2, "COMPLETED", response(content="另一个回答").model_dump(mode="json", by_alias=True))
    if case == "failed": last = frame(req, 2, "FAILED", {"errorCode": "GA_MODEL_STREAM_FAILED"})
    if case == "fields": first = frame(req, 1, "TEXT_DELTA", {"text": "回答", "arguments": {}})
    if case == "invalid-utf8": first = b'\xff\n'
    client = broker([first, last])
    try:
        with pytest.raises(BrokerError): client.stream(req, lambda *_: None)
    finally:
        client.close()
