"""Host-owned one-shot execution; framework owns the tool loop, never HTTP retries."""
import json
import logging
import time
from threading import Event, Lock, Thread
from queue import Queue, Empty, Full
from uuid import UUID, uuid4

import httpx
from fastapi import APIRouter, HTTPException, Request
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from starlette.responses import StreamingResponse

from .agent import build_agent
from .checkpoints import HostCheckpointSaver
from .contracts import ExecutionEvent, ExecutionRequest, RunScope
from .host_rpc import CapabilityRpc, CheckpointRpc
from .model_adapter import BrokerChatModel, NativeBroker, BrokerError
from ..wire import canonical_hash
from ..wire import WireModel
from pydantic import Field


class ClaimReply(WireModel):
    checkpoint_id: str | None = Field(alias="checkpointId", max_length=128)


class CancelRequest(WireModel):
    execution_epoch: int = Field(alias="executionEpoch", ge=1, le=9223372036854775807)
    lease_id: UUID = Field(alias="leaseId")


def execution_router(settings, authenticate):
    router = APIRouter(prefix="/internal/v1/global-assistant", dependencies=authenticate)
    active, lock = {}, Lock()
    # Origin comes from deployment config, never from an execution envelope.
    parsed = httpx.URL(settings.broker_url)
    base = str(parsed.copy_with(path="/internal/v1/global-assistant", query=None, fragment=None)).rstrip("/")

    @router.post("/executions")
    async def start(request: Request):
        if settings.model_mode != "broker" or not settings.internal_secret:
            raise HTTPException(503, "GA_EXECUTOR_NOT_CONFIGURED")
        body = bytearray()
        async for part in request.stream():
            body.extend(part)
            if len(body) > 262144:
                raise HTTPException(413, "GA_EXECUTION_TOO_LARGE")
        try:
            execution = ExecutionRequest.model_validate_json(bytes(body))
        except ValueError:
            raise HTTPException(422, "GA_EXECUTION_PROTOCOL_ERROR") from None
        # Durable CAS is authoritative across duplicate requests and Python restarts.
        import asyncio
        def claim():
            with httpx.Client(timeout=10, trust_env=False, follow_redirects=False,
                              transport=httpx.HTTPTransport(retries=0, trust_env=False)) as client:
                with client.stream("POST", base + "/execution-claim", headers={
                    "X-Spec-Agent-Internal-Token": settings.internal_secret}, json={
                    "runId": str(execution.run_id), "executionEpoch": execution.execution_epoch,
                    "leaseId": str(execution.lease_id),
                    "executionRequestHash": canonical_hash(execution.model_dump(by_alias=True, mode="json"))}) as response:
                    if response.status_code != 200:
                        raise HTTPException(409, "GA_EXECUTION_NOT_CLAIMED")
                    wire = bytearray()
                    for part in response.iter_bytes():
                        wire.extend(part)
                        if len(wire) > 4096:
                            raise HTTPException(502, "GA_EXECUTION_CLAIM_PROTOCOL_ERROR")
                    try:
                        return ClaimReply.model_validate_json(bytes(wire)).checkpoint_id
                    except ValueError:
                        raise HTTPException(502, "GA_EXECUTION_CLAIM_PROTOCOL_ERROR") from None
        try:
            checkpoint_id = await asyncio.to_thread(claim)
        except httpx.HTTPError:
            raise HTTPException(502, "GA_EXECUTION_CLAIM_UNAVAILABLE") from None
        cancelled = Event()
        identity = str(execution.run_id)
        with lock:
            if identity in active:
                raise HTTPException(409, "GA_EXECUTION_ALREADY_RUNNING")
            active[identity] = (execution.execution_epoch, str(execution.lease_id), cancelled)

        def events():
            sequence = 0
            started = time.monotonic()
            def guard():
                if cancelled.is_set():
                    raise RuntimeError("GA_EXECUTION_CANCELLED")
                if time.monotonic() - started >= execution.budget.max_duration_seconds:
                    raise RuntimeError("GA_EXECUTION_DEADLINE")
            def emit(kind, payload):
                nonlocal sequence
                if sequence >= 8191 and kind not in {"COMPLETED", "USER_INPUT_REQUIRED", "FAILED"}:
                    raise RuntimeError("GA_EXECUTION_EVENT_BUDGET")
                sequence += 1
                body = json.dumps({"protocolVersion": "ga-execution-event.v1", "runId": identity,
                    "executionEpoch": execution.execution_epoch, "eventId": str(uuid4()),
                    "sequence": sequence, "type": kind, "payload": payload},
                    ensure_ascii=False, allow_nan=False, separators=(",", ":"))
                ExecutionEvent.model_validate_json(body)
                return body + "\n"
            checkpoints = CheckpointRpc(base + "/checkpoints", settings.internal_secret)
            capabilities = CapabilityRpc(base + "/capabilities", settings.internal_secret)
            broker = NativeBroker(base + "/model-inference", settings.internal_secret)
            queue = Queue(maxsize=32)
            def enqueue(kind, payload):
                while True:
                    guard()
                    try:
                        queue.put((kind, payload), timeout=0.1)
                        return
                    except Full:
                        pass
            def work():
                try:
                    saver = HostCheckpointSaver(execution, checkpoints, initial_checkpoint=checkpoint_id)
                    model = BrokerChatModel(scope=RunScope(runId=execution.run_id,
                        executionEpoch=execution.execution_epoch, leaseId=execution.lease_id),
                        model_binding_id=execution.model_binding_id, broker=broker, guard=guard, stream_sink=enqueue)
                    graph = build_agent(execution, model, saver=saver, invoke=capabilities, guard=guard)
                    config = {"configurable": {"thread_id": str(execution.thread_id)}}
                    messages = [HumanMessage(content=m.content, id=str(m.message_id)) if m.role == "user"
                                else AIMessage(content=m.content, id=str(m.message_id)) for m in execution.history]
                    messages.append(HumanMessage(content=execution.content, id=str(execution.message_id)))
                    result = None
                    for result in graph.stream({"messages": messages}, config, stream_mode="values", durability="sync"):
                        guard()
                    guard()
                    final = result["messages"][-1]
                    if isinstance(final, AIMessage) and not final.tool_calls and final.content.strip():
                        enqueue("COMPLETED", {"text": final.content})
                    elif isinstance(final, ToolMessage):
                        observation = json.loads(final.content)
                        if observation.get("status") == "USER_INPUT_REQUIRED":
                            enqueue("USER_INPUT_REQUIRED", {"text": observation["content"]["question"]})
                        else:
                            enqueue("FAILED", {"errorCode": "GA_REPEATED_TOOL_CALL"})
                    else:
                        enqueue("FAILED", {"errorCode": "GA_EXECUTION_PROTOCOL_ERROR"})
                except Exception as ex:
                    # Exception text may contain provider payload; never emit/log it here.
                    logging.getLogger("spec_agent_brain").warning("GA execution failed run=%s category=%s", identity, type(ex).__name__)
                    if isinstance(ex, BrokerError):
                        logging.getLogger("spec_agent_brain").warning("GA sanitized broker failure: %s", ex)
                    try:
                        enqueue("FAILED", {"errorCode": "GA_EXECUTION_FAILED"})
                    except RuntimeError:
                        pass # A disconnected/cancelled consumer has no remaining delivery authority.
                finally:
                    broker.close(); capabilities.close(); checkpoints.close()
            worker = Thread(target=work, name="ga-framework-execution", daemon=True)
            try:
                yield emit("STATUS", {"stage": "EXECUTING"})
                worker.start()
                while True:
                    guard()
                    try:
                        kind, payload = queue.get(timeout=0.1)
                    except Empty:
                        if not worker.is_alive():
                            raise RuntimeError("GA_EXECUTOR_INTERRUPTED")
                        continue
                    yield emit(kind, payload)
                    if kind in {"COMPLETED", "USER_INPUT_REQUIRED", "FAILED"}:
                        break
            except Exception:
                yield emit("FAILED", {"errorCode": "GA_EXECUTION_FAILED"})
            finally:
                cancelled.set()
                if worker.is_alive():
                    worker.join(timeout=0.5)
                with lock:
                    active.pop(identity, None)
        return StreamingResponse(events(), media_type="application/x-ndjson")

    @router.post("/executions/{run_id}/cancel")
    async def cancel(run_id: str, request: Request):
        try:
            wire = bytearray()
            async for part in request.stream():
                wire.extend(part)
                if len(wire) > 4096:
                    raise ValueError()
            body = CancelRequest.model_validate_json(bytes(wire))
            run_id = str(UUID(run_id))
        except ValueError:
            raise HTTPException(422, "GA_CANCEL_PROTOCOL_ERROR") from None
        with lock:
            entry = active.get(run_id)
            if entry and entry[:2] == (body.execution_epoch, str(body.lease_id)):
                entry[2].set()
        return {"status": "SIGNALLED"}
    return router
