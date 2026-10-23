"""Host-backed saver bridge. The host owns persistence, lease validation and CAS."""

import asyncio
import hashlib
import json
import math
import re
from threading import RLock
from typing import Any, Callable

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage, RemoveMessage
from langgraph.checkpoint.base import BaseCheckpointSaver, CheckpointTuple, WRITES_IDX_MAP
from langgraph.types import Send

from .contracts import ExecutionRequest

CODEC = "ga-json.v2"
FRAMEWORK = "langgraph-1.2.12/checkpoint-4.2.0"
STATE = "langchain-ga.v1"
NAMESPACE = "ga:langchain-ga.v1"
MAX_BYTES = 1048576
REF_PATTERN = re.compile(r"gacr-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
MESSAGE_TYPES = {"ai": AIMessage, "human": HumanMessage, "system": SystemMessage, "tool": ToolMessage, "remove": RemoveMessage}


def _safe_message_metadata(data):
    metadata = data.get("response_metadata", {})
    extra_keys = set(data.get("additional_kwargs", {}))
    continuation_ref = data.get("additional_kwargs", {}).get("model_continuation_ref")
    ref_valid = continuation_ref is None or (
        type(continuation_ref) is str
        and bool(REF_PATTERN.fullmatch(continuation_ref)))
    return (type(data.get("content")) is str and not data.get("invalid_tool_calls")
            and extra_keys <= {"model_continuation_ref"} and ref_valid and type(metadata) is dict
            and set(metadata) <= {"finish_reason"}
            and (not metadata or metadata.get("finish_reason") in {"stop", "tool_calls"}))


def _legacy_message_metadata(data):
    metadata = data.get("response_metadata", {})
    extra_keys = set(data.get("additional_kwargs", {}))
    legacy = data.get("additional_kwargs", {}).get("tool_call_extra_content")
    legacy_valid = legacy is None or (type(legacy) is dict and 0 < len(legacy) <= 5
        and all(type(k) is str and type(v) is dict and set(v) == {"google"}
        and type(v.get("google")) is dict and set(v["google"]) == {"thought_signature"}
        and type(v["google"].get("thought_signature")) is str
        and 0 < len(v["google"]["thought_signature"]) <= 1024 for k, v in legacy.items()))
    return (type(data.get("content")) is str and not data.get("invalid_tool_calls")
            and extra_keys <= {"tool_call_extra_content"} and legacy_valid
            and type(metadata) is dict and set(metadata) <= {"finish_reason"}
            and (not metadata or metadata.get("finish_reason") in {"stop", "tool_calls"}))


class SafeCheckpointCodec:
    """Tagged JSON with a fixed message allowlist; no pickle/import/constructor lookup."""

    def _encode(self, value: Any, depth=0, *, legacy=False):
        if depth > 64:
            raise ValueError("checkpoint nesting limit")
        if value is None or type(value) in (str, bool, int, float):
            if type(value) is float and not math.isfinite(value):
                raise ValueError("invalid checkpoint number")
            return value
        if type(value) in (list, tuple, set):
            return {"tag": type(value).__name__, "value": [self._encode(v, depth + 1, legacy=legacy) for v in value]}
        if type(value) is dict:
            if any(type(k) is not str for k in value):
                raise ValueError("checkpoint object keys must be strings")
            return {"tag": "dict", "value": {k: self._encode(v, depth + 1, legacy=legacy) for k, v in value.items()}}
        if type(value) in MESSAGE_TYPES.values():
            data = value.model_dump()
            if not (legacy and _legacy_message_metadata(data)) and not _safe_message_metadata(data):
                raise ValueError("provider metadata is forbidden in checkpoints")
            return {"tag": "message", "value": self._encode(data, depth + 1, legacy=legacy)}
        if type(value) is Send:
            if type(value.node) is not str or not 1 <= len(value.node) <= 128:
                raise ValueError("invalid checkpoint dispatch node")
            return {"tag": "send", "value": {"node": value.node, "arg": self._encode(value.arg, depth + 1, legacy=legacy)}}
        if isinstance(value, BaseException):
            # Phase 1 never resumes a failed execution. Keep a safe error marker,
            # never the exception text (which can contain prompts/credentials).
            return {"tag": "error", "value": type(value).__name__}
        raise ValueError(f"unsupported checkpoint type: {type(value).__name__}")

    def _decode(self, value: Any, depth=0):
        if depth > 64:
            raise ValueError("checkpoint nesting limit")
        if value is None or type(value) in (str, bool, int, float):
            if type(value) is float and not math.isfinite(value):
                raise ValueError("invalid checkpoint number")
            return value
        if type(value) is not dict or set(value) != {"tag", "value"}:
            raise ValueError("invalid checkpoint tag")
        tag, body = value["tag"], value["value"]
        if tag == "dict" and type(body) is dict:
            return {k: self._decode(v, depth + 1) for k, v in body.items()}
        if tag in {"list", "tuple", "set"} and type(body) is list:
            values = [self._decode(v, depth + 1) for v in body]
            return {"list": list, "tuple": tuple, "set": set}[tag](values)
        if tag == "error" and type(body) is str:
            return RuntimeError("GA_EXECUTION_FAILED")
        if tag == "send" and type(body) is dict and set(body) == {"node", "arg"}:
            if type(body["node"]) is not str or not 1 <= len(body["node"]) <= 128:
                raise ValueError("invalid checkpoint dispatch node")
            return Send(body["node"], self._decode(body["arg"], depth + 1))
        if tag == "message":
            data = self._decode(body, depth + 1)
            if type(data) is not dict or data.get("type") not in MESSAGE_TYPES:
                raise ValueError("unsupported checkpoint message")
            cls = MESSAGE_TYPES[data["type"]]
            if set(data) - set(cls.model_fields) or not (_safe_message_metadata(data) or _legacy_message_metadata(data)):
                raise ValueError("invalid checkpoint message fields")
            return cls.model_validate(data)
        raise ValueError("unknown checkpoint tag")

    def dumps(self, value: Any, *, legacy=False) -> dict:
        payload = json.dumps(self._encode(value, legacy=legacy), ensure_ascii=False, sort_keys=True,
                             separators=(",", ":"), allow_nan=False)
        if len(payload.encode()) > MAX_BYTES:
            raise ValueError("checkpoint size limit")
        return {"codecVersion": CODEC, "frameworkVersion": FRAMEWORK, "stateVersion": STATE,
                "payload": payload, "payloadHash": hashlib.sha256(payload.encode()).hexdigest()}

    def dumps_legacy_payload(self, extras: dict) -> str:
        if not _legacy_message_metadata({"additional_kwargs": {"tool_call_extra_content": extras},
                                         "content": "", "invalid_tool_calls": []}):
            raise ValueError("invalid legacy continuation payload")
        return json.dumps(extras, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                          allow_nan=False)

    def loads(self, envelope: dict) -> Any:
        if set(envelope) != {"codecVersion", "frameworkVersion", "stateVersion", "payload", "payloadHash"}:
            raise ValueError("unknown checkpoint envelope fields")
        if (envelope["codecVersion"] not in {CODEC, "ga-json.v1"}
                or envelope["frameworkVersion"] != FRAMEWORK or envelope["stateVersion"] != STATE):
            raise ValueError("checkpoint version incompatible")
        payload = envelope["payload"]
        if type(payload) is not str or len(payload.encode()) > MAX_BYTES:
            raise ValueError("checkpoint size limit")
        if hashlib.sha256(payload.encode()).hexdigest() != envelope["payloadHash"]:
            raise ValueError("checkpoint hash mismatch")
        def reject_constant(value):
            raise ValueError("invalid JSON number")
        def unique_pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ValueError("duplicate checkpoint key")
                result[key] = value
            return result
        return self._decode(json.loads(payload, parse_constant=reject_constant, object_pairs_hook=unique_pairs))


class HostCheckpointSaver(BaseCheckpointSaver):
    """RPC is injected; no database or fallback saver exists in Python.

    rpc(operation, envelope) returns a strict host response. Host CAS version
    covers checkpoint and intermediate writes under the same execution lock.
    All operations are scoped; list(None) cannot enumerate other threads.
    """

    def __init__(self, execution: ExecutionRequest, rpc: Callable[[str, dict], dict], *, initial_checkpoint="latest"):
        super().__init__()
        self.execution = execution
        self.rpc = rpc
        self.codec = SafeCheckpointCodec()
        self.version = 0
        self.lock = RLock()
        self.initial_checkpoint = initial_checkpoint

    def _migrate_legacy_checkpoint(self, value):
        """Ask Java to convert persisted v1 provider payloads into a real sidecar ref."""
        if type(value) is list:
            return [self._migrate_legacy_checkpoint(v) for v in value]
        if type(value) is dict:
            return {k: self._migrate_legacy_checkpoint(v) for k, v in value.items()}
        if isinstance(value, AIMessage) and value.additional_kwargs.get("tool_call_extra_content"):
            extras = value.additional_kwargs["tool_call_extra_content"]
            tool_ids = [c["id"] for c in value.tool_calls]
            if sorted(extras) != sorted(tool_ids):
                raise ValueError("legacy continuation identity mismatch")
            reply = self._call("MIGRATE_CONTINUATION", toolCallIds=tool_ids,
                               continuationPayload=self.codec.dumps_legacy_payload(extras))
            ref = reply.get("continuationRef")
            if type(ref) is not str or not REF_PATTERN.fullmatch(ref):
                raise ValueError("invalid legacy continuation migration")
            return AIMessage(content=value.content, tool_calls=value.tool_calls,
                             additional_kwargs={"model_continuation_ref": ref})
        return value

    def _config(self, checkpoint_id=None):
        result = {"thread_id": str(self.execution.thread_id), "checkpoint_ns": ""}
        if checkpoint_id is not None:
            result["checkpoint_id"] = checkpoint_id
        return {"configurable": result}

    def _validate_config(self, config):
        configurable = config.get("configurable", {})
        if configurable.get("thread_id") != str(self.execution.thread_id) or configurable.get("checkpoint_ns", "") != "":
            raise ValueError("checkpoint scope mismatch")
        checkpoint_id = configurable.get("checkpoint_id")
        if checkpoint_id is not None and (type(checkpoint_id) is not str or len(checkpoint_id) > 128):
            raise ValueError("invalid checkpoint identity")
        return checkpoint_id

    def _call(self, operation, **payload):
        e = self.execution
        response = self.rpc(operation, {"protocolVersion": "ga-checkpoint.v1", "runId": str(e.run_id),
                                        "executionEpoch": e.execution_epoch, "leaseId": str(e.lease_id),
                                        "threadId": str(e.thread_id), "namespace": NAMESPACE,
                                        "expectedVersion": self.version, **payload})
        if (set(response) != {"protocolVersion", "version", "result"}
                or response["protocolVersion"] != "ga-checkpoint.v1"
                or type(response["version"]) is not int or response["version"] < self.version):
            raise ValueError("invalid checkpoint host response")
        self.version = response["version"]
        return response["result"]

    def _tuple(self, value):
        if set(value) != {"checkpoint", "metadata", "parentCheckpointId", "pendingWrites"}:
            raise ValueError("invalid checkpoint record")
        checkpoint = self._load_and_migrate(value["checkpoint"])
        metadata = self._load_and_migrate(value["metadata"])
        writes = self._load_and_migrate(value["pendingWrites"])
        if not isinstance(checkpoint, dict) or not isinstance(metadata, dict) or not isinstance(writes, list):
            raise ValueError("invalid checkpoint payload")
        return CheckpointTuple(config=self._config(checkpoint["id"]), checkpoint=checkpoint, metadata=metadata,
                               parent_config=self._config(value["parentCheckpointId"]) if value["parentCheckpointId"] else None,
                               pending_writes=writes)

    def _load_and_migrate(self, envelope):
        loaded = self.codec.loads(envelope)
        return self._migrate_legacy_checkpoint(loaded) if envelope["codecVersion"] == "ga-json.v1" else loaded

    def get_tuple(self, config):
        with self.lock:
            checkpoint_id = self._validate_config(config)
            if checkpoint_id is None and self.initial_checkpoint != "latest":
                checkpoint_id = self.initial_checkpoint
                if checkpoint_id is None:
                    # Read the head CAS version without reading an interrupted chain.
                    self._call("GET", checkpointId="ga:no-completed-checkpoint")
                    return None
            result = self._call("GET", checkpointId=checkpoint_id)
            if result is None and self.initial_checkpoint not in (None, "latest"):
                raise ValueError("completed checkpoint boundary missing")
            return self._tuple(result) if result is not None else None

    def list(self, config, *, filter=None, before=None, limit=None):
        with self.lock:
            if config is not None:
                self._validate_config(config)
            if limit is not None and (type(limit) is not int or not 1 <= limit <= 100):
                raise ValueError("checkpoint list limit must be 1..100")
            cursor = self._validate_config(before) if before else None
            rows = self._call("LIST", before=cursor, limit=limit or 100, filter=filter or {})
            if not isinstance(rows, list) or len(rows) > (limit or 100):
                raise ValueError("invalid checkpoint list")
            tuples = [self._tuple(row) for row in rows]
        yield from tuples

    def put(self, config, checkpoint, metadata, new_versions):
        with self.lock:
            parent = self._validate_config(config)
            self._call("PUT", checkpointId=checkpoint["id"], parentCheckpointId=parent,
                       checkpoint=self.codec.dumps(checkpoint), metadata=self.codec.dumps(metadata),
                        newVersions=self.codec.dumps(new_versions))
            self.initial_checkpoint = checkpoint["id"]
            return self._config(checkpoint["id"])

    def put_writes(self, config, writes, task_id, task_path=""):
        with self.lock:
            checkpoint_id = self._validate_config(config)
            if not checkpoint_id:
                raise ValueError("intermediate writes require checkpoint")
            values = [{"index": WRITES_IDX_MAP.get(channel, index), "channel": channel,
                       "value": self.codec.dumps(value)} for index, (channel, value) in enumerate(writes)]
            self._call("PUT_WRITES", checkpointId=checkpoint_id, taskId=task_id, taskPath=task_path, writes=values)

    def delete_thread(self, thread_id):
        if thread_id != str(self.execution.thread_id):
            raise ValueError("checkpoint scope mismatch")
        with self.lock:
            self._call("DELETE_THREAD")

    async def aget_tuple(self, config):
        return await asyncio.to_thread(self.get_tuple, config)

    async def alist(self, config, *, filter=None, before=None, limit=None):
        rows = await asyncio.to_thread(lambda: list(self.list(config, filter=filter, before=before, limit=limit)))
        for row in rows:
            yield row

    async def aput(self, config, checkpoint, metadata, new_versions):
        return await asyncio.to_thread(self.put, config, checkpoint, metadata, new_versions)

    async def aput_writes(self, config, writes, task_id, task_path=""):
        await asyncio.to_thread(self.put_writes, config, writes, task_id, task_path)

    async def adelete_thread(self, thread_id):
        await asyncio.to_thread(self.delete_thread, thread_id)
