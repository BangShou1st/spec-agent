"""Host-backed saver bridge. The host owns persistence, lease validation and CAS."""

import asyncio
import hashlib
import json
import math
from threading import RLock
from typing import Any, Callable

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage, RemoveMessage
from langgraph.checkpoint.base import BaseCheckpointSaver, CheckpointTuple, WRITES_IDX_MAP
from langgraph.types import Send

from .contracts import ExecutionRequest

CODEC = "ga-json.v1"
FRAMEWORK = "langgraph-1.2.12/checkpoint-4.2.0"
STATE = "langchain-ga.v1"
NAMESPACE = "ga:langchain-ga.v1"
MAX_BYTES = 1048576
MESSAGE_TYPES = {"ai": AIMessage, "human": HumanMessage, "system": SystemMessage, "tool": ToolMessage, "remove": RemoveMessage}


def _safe_message_metadata(data):
    metadata = data.get("response_metadata", {})
    return (type(data.get("content")) is str and not data.get("invalid_tool_calls")
            and not data.get("additional_kwargs") and type(metadata) is dict
            and set(metadata) <= {"finish_reason"}
            and (not metadata or metadata.get("finish_reason") in {"stop", "tool_calls"}))


class SafeCheckpointCodec:
    """Tagged JSON with a fixed message allowlist; no pickle/import/constructor lookup."""

    def _encode(self, value: Any, depth=0):
        if depth > 64:
            raise ValueError("checkpoint nesting limit")
        if value is None or type(value) in (str, bool, int, float):
            if type(value) is float and not math.isfinite(value):
                raise ValueError("invalid checkpoint number")
            return value
        if type(value) in (list, tuple, set):
            return {"tag": type(value).__name__, "value": [self._encode(v, depth + 1) for v in value]}
        if type(value) is dict:
            if any(type(k) is not str for k in value):
                raise ValueError("checkpoint object keys must be strings")
            return {"tag": "dict", "value": {k: self._encode(v, depth + 1) for k, v in value.items()}}
        if type(value) in MESSAGE_TYPES.values():
            # Provider payload/reasoning are never persisted. Their presence is
            # rejected rather than silently changing recoverable state.
            if not _safe_message_metadata(value.model_dump()):
                raise ValueError("provider metadata is forbidden in checkpoints")
            return {"tag": "message", "value": self._encode(value.model_dump(), depth + 1)}
        if type(value) is Send:
            if type(value.node) is not str or not 1 <= len(value.node) <= 128:
                raise ValueError("invalid checkpoint dispatch node")
            return {"tag": "send", "value": {"node": value.node, "arg": self._encode(value.arg, depth + 1)}}
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
            if set(data) - set(cls.model_fields) or not _safe_message_metadata(data):
                raise ValueError("invalid checkpoint message fields")
            return cls.model_validate(data)
        raise ValueError("unknown checkpoint tag")

    def dumps(self, value: Any) -> dict:
        payload = json.dumps(self._encode(value), ensure_ascii=False, sort_keys=True,
                             separators=(",", ":"), allow_nan=False)
        if len(payload.encode()) > MAX_BYTES:
            raise ValueError("checkpoint size limit")
        return {"codecVersion": CODEC, "frameworkVersion": FRAMEWORK, "stateVersion": STATE,
                "payload": payload, "payloadHash": hashlib.sha256(payload.encode()).hexdigest()}

    def loads(self, envelope: dict) -> Any:
        if set(envelope) != {"codecVersion", "frameworkVersion", "stateVersion", "payload", "payloadHash"}:
            raise ValueError("unknown checkpoint envelope fields")
        if (envelope["codecVersion"], envelope["frameworkVersion"], envelope["stateVersion"]) != (CODEC, FRAMEWORK, STATE):
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
        checkpoint = self.codec.loads(value["checkpoint"])
        metadata = self.codec.loads(value["metadata"])
        writes = self.codec.loads(value["pendingWrites"])
        if not isinstance(checkpoint, dict) or not isinstance(metadata, dict) or not isinstance(writes, list):
            raise ValueError("invalid checkpoint payload")
        return CheckpointTuple(config=self._config(checkpoint["id"]), checkpoint=checkpoint, metadata=metadata,
                               parent_config=self._config(value["parentCheckpointId"]) if value["parentCheckpointId"] else None,
                               pending_writes=writes)

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
