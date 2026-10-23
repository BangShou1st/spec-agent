"""D4 evidence-consumption experiment, never a production or retrieval executor.

Explicit local-only opt-in; unchanged existing 4B model, no keys, database,
embedding, D3 queries, retries or judge calls. Real production prompts/parsers
and GA create_agent are used, but authorized evidence and host actions are
synthetic test boundaries. Results cannot qualify the Java host/RBAC/UI chain.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys
import time
from uuid import uuid4

import httpx
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
from rag_d4_cases import CASES, REF, ROUTE
from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.decision import handle_decision
from spec_agent_brain.global_assistant.agent import build_agent
from spec_agent_brain.global_assistant.contracts import (
    CapabilityResponse, Descriptor, ExecutionRequest, ModelResponse, RunScope, ToolCall, Usage, canonical_hash,
)
from spec_agent_brain.global_assistant.model_adapter import BrokerChatModel
from spec_agent_brain.model_client import Completion, ModelClientError

ROOT = Path(__file__).resolve().parents[2]
MODEL = "qwen3.5:4b"


def request(case):
    body = json.loads((ROOT / "contracts/fixtures/agent-input-valid.json").read_text(encoding="utf-8"))
    body["event"].update(kind="NODE_QUERY", freeText=case["query"])
    s = body["snapshot"]
    s["lineage"] = []
    s["effectiveClaims"] = []
    s["allowedSourceRefs"] = [i["sourceRef"] for i in case["items"]]
    s["retrievedContext"] = case["items"]
    s["metadata"] = {"projectTitle": "D4合成工作区"}
    if case.get("supplementalUnavailable"):
        s["metadata"]["retrieval"] = dict(retrievalEngineVersion="python-rag.v1", profileId="a"*64,
                                           supplementalRetrievalUnavailable=True)
    if case.get("conflict"):
        s["effectiveClaims"] = [dict(kind="conflict", text=case["items"][0]["content"], status="unresolved",
                                    confidence=1.0, sourceNodeId=REF[5:], sourceAnswerId=None)]
    body["decisionBudget"]["maxModelCalls"] = 1  # No repair/retry can hide first output.
    return parse_request_envelope(body)


class LocalBoundary:
    """Test-only local HTTP boundary. Production still uses Java ModelGateway."""
    def __init__(self):
        self.calls = 0
        self.first_ms = None
        self.started = time.perf_counter()
        self.last = ""
        self.tokens = []
        self.host_tool_calls = 0

    def chat(self, messages, tools=None, json_output=False):
        self.calls += 1
        body = dict(model=MODEL, messages=messages, stream=True, max_tokens=2048)
        if json_output:
            body["response_format"] = {"type": "json_object"}
        if tools:
            body.update(tools=tools, tool_choice="auto", parallel_tool_calls=False)
        text, calls = "", {}
        finish = None
        with httpx.Client(timeout=90, trust_env=False, follow_redirects=False) as client:
            with client.stream("POST", "http://127.0.0.1:11434/v1/chat/completions", json=body) as r:
                r.raise_for_status()
                for line in r.iter_lines():
                    if not line.startswith("data: ") or line == "data: [DONE]":
                        continue
                    event = json.loads(line[6:])
                    if event.get("usage"):
                        self.tokens.append(event["usage"])
                    if not event.get("choices"):
                        continue
                    choice = event["choices"][0]
                    delta = choice.get("delta", {})
                    part = delta.get("content") or ""
                    if part and self.first_ms is None:
                        self.first_ms = (time.perf_counter()-self.started)*1000
                    text += part
                    for call in delta.get("tool_calls", []):
                        target = calls.setdefault(call["index"], {"id": "", "name": "", "arguments": ""})
                        target["id"] += call.get("id", "")
                        target["name"] += call.get("function", {}).get("name", "")
                        target["arguments"] += call.get("function", {}).get("arguments", "")
                    finish = choice.get("finish_reason") or finish
        self.last = text
        if finish not in {"stop", "tool_calls"}:
            raise ModelClientError("LOCAL_MODEL_INCOMPLETE")
        return text, list(calls.values())

    def complete(self, *args, **kwargs):
        # Project ModelClient and GA NativeBroker have separate existing contracts.
        if args:
            req = args[0]
            messages = []
            for m in req.messages:
                wire = {"role": m.role, "content": m.content}
                if m.tool_call_id:
                    wire["tool_call_id"] = m.tool_call_id
                if m.tool_calls:
                    wire["tool_calls"] = [dict(id=c.id, type="function", function=dict(name=c.name, arguments=json.dumps(c.arguments))) for c in m.tool_calls]
                messages.append(wire)
            tools = [dict(type="function", function=dict(name=t.name, description=t.description, parameters=t.parameters)) for t in req.tools]
            text, calls = self.chat(messages, tools)
            typed = [ToolCall(id=c["id"] or str(uuid4()), name=c["name"], arguments=json.loads(c["arguments"])) for c in calls]
            return ModelResponse(protocolVersion="ga-model-inference.v1", content=text, toolCalls=typed,
                                 finishReason="tool_calls" if typed else "stop", usage=Usage(promptTokens=0, completionTokens=0))
        text, calls = self.chat([dict(role=m.role, content=m.content) for m in kwargs["messages"]], json_output=True)
        if calls:
            raise ModelClientError("UNEXPECTED_TOOL_CALL")
        return Completion(content=text, finish_reason="stop")


def global_run(case, client):
    capability = "help.search" if case["id"] in {"D401", "D402", "D403"} else "project.content.discover"
    name = capability.replace(".", "_")
    descriptors = [Descriptor(capabilityId=capability, version="1", name=name, description="Read authorized evidence",
                              inputSchema={"query": {"type": "string", "required": True}}, readOnly=True, sideEffectClass="NONE"),
                   Descriptor(capabilityId="user-input.request", version="1", name="user-input_request", description="Ask one necessary question",
                              inputSchema={"question": {"type": "string", "required": True}}, readOnly=True, sideEffectClass="NONE")]
    e = ExecutionRequest(protocolVersion="ga-execution.v1", engineVersion="langchain-ga.v1", runId=uuid4(), executionEpoch=1,
                         leaseId=uuid4(), threadId=uuid4(), messageId=uuid4(), content=case["query"], modelBindingId=uuid4(), policyVersion="1",
                         catalogHash=canonical_hash([d.model_dump(by_alias=True) for d in descriptors]), history=[], historyBoundary=None,
                         uiContext={"currentPage": "PROJECTS"}, capabilities=descriptors,
                         budget={"maxModelCalls": 2, "maxToolCalls": 2, "maxDurationSeconds": 180})
    content = dict(sources=case["items"], vectorUnavailable=case.get("vectorUnavailable", False), warnings=case.get("warnings", []))
    observation = CapabilityResponse(protocolVersion="ga-capability-result.v1", toolCallId="frozen-evidence",
                                     status="FAILED" if case.get("error") else "SUCCEEDED", content=content,
                                     sourceRefs=[i["sourceRef"] for i in case["items"]], errorCode=case.get("error"))
    invoked = []
    def invoke(req):
        client.host_tool_calls += 1
        invoked.append(req.capability_id)
        if req.capability_id == "user-input.request":
            return CapabilityResponse(protocolVersion="ga-capability-result.v1", toolCallId=req.tool_call_id,
                                      status="USER_INPUT_REQUIRED", content={"question": req.arguments["question"]})
        return observation.model_copy(update={"tool_call_id": req.tool_call_id})
    model = BrokerChatModel(scope=RunScope(runId=e.run_id, executionEpoch=1, leaseId=e.lease_id),
                            model_binding_id=e.model_binding_id, broker=client, guard=lambda: None)
    graph = build_agent(e, model, saver=InMemorySaver(), invoke=invoke, guard=lambda: None)
    result = graph.invoke({"messages": [HumanMessage(content=e.content, id=str(e.message_id)),
        AIMessage(content="", tool_calls=[dict(id="frozen-evidence", name=name, args={"query": e.content})]),
        ToolMessage(content=observation.model_dump_json(by_alias=True), tool_call_id="frozen-evidence",
                    status="error" if case.get("error") else "success")]},
        {"configurable": {"thread_id": str(e.thread_id)}, "recursion_limit": 12})
    last = result["messages"][-1]
    return last.content, invoked, "USER_INPUT_REQUIRED" if isinstance(last, ToolMessage) else "RESPOND_TO_USER"


def fault_run(case):
    class Adversary:
        def complete(self, **kwargs):
            if case["fault"] == "exception":
                raise ModelClientError("TEST_MODEL_UNAVAILABLE")
            if case["fault"] == "malformed":
                return Completion("{invalid", "stop")
            ref = REF if case["fault"] == "unsupported" else "node:ffffffff-ffff-4fff-8fff-ffffffffffff"
            return Completion(json.dumps(dict(observation=dict(known=[], unknowns=[], conflicts=[], risks=[]),
                action=dict(actionFamily="RESPOND_TO_USER", payload={"message": "已经确认预算900万元。"}, sourceRefs=[ref]))), "stop")
    req = request(dict(case, items=[dict(sourceRef=REF, sourceKind="NODE", scope="ROUTE", originRouteId=ROUTE,
        authority="USER_AUTHORED", content="仅确认保留期17天。", location={}, provenance={}, retrievalReason="test")]))
    try:
        result = handle_decision(req, Adversary())
    except Exception as ex:
        return {"passed": case["fault"] != "unsupported", "error": type(ex).__name__, "modelCalls": 1}
    return {"passed": False, "finding": "Allowed source identity does not validate semantic entailment",
            "output": result.model_dump(mode="json", by_alias=True), "modelCalls": 1}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live-local", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.live_local:
        parser.error("explicit --live-local required; never automatically contact a model")
    args.output.mkdir(parents=True, exist_ok=False)
    freeze = dict(labelReview="AI_REVIEWED_SYNTHETIC_NOT_HUMAN", cases=CASES, model=MODEL,
                  scope="Evidence consumption with synthetic host boundaries, not full host integration",
                  retryCount=0, promptFiles={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in
                    [ROOT/"agent-brain/src/spec_agent_brain/prompts/decision.py", ROOT/"agent-brain/src/spec_agent_brain/global_assistant/agent.py"]})
    (args.output/"freeze.json").write_text(json.dumps(freeze, ensure_ascii=False, indent=2), encoding="utf-8")
    results = []
    for case in CASES:
        row = dict(id=case["id"], agent=case["agent"], category=case["category"], toolCalls=0,
                   graphWrites=None, graphPersistenceMeasured=False)
        if case["agent"] == "fault":
            row.update(fault_run(case))
        else:
            client = LocalBoundary()
            try:
                if case["agent"] == "global":
                    text, tools, family = global_run(case, client)
                    row["toolCalls"] = len(tools)
                    refs = [i["sourceRef"] for i in case["items"] if i["sourceRef"] in text]
                else:
                    out = handle_decision(request(case), client)
                    family = out.action_proposal.action_family
                    text = str(out.action_proposal.payload)
                    refs = out.action_proposal.source_refs
                    row["response"] = out.model_dump(mode="json", by_alias=True)
                row.update(text=text, family=family, refs=refs,
                    conceptPass=all(any(term.lower() in text.lower() for term in group) for group in case["concepts"]),
                    citationPass=set(case["expectedRefs"]).issubset(refs) and set(refs).issubset({i["sourceRef"] for i in case["items"]}),
                    unknownPass=not case["unknown"] or family == "USER_INPUT_REQUIRED" or any(w in text for w in ["不足", "无法", "未确认", "尚未", "未提供", "未知", "不确定", "不完整", "不能", "没有", "未确定", "缺少", "未找到", "缺失", "未包含", "未见", "待"]),
                    forbiddenPass=not any(term in text for term in case.get("forbidden", [])))
                row["passed"] = all(row[k] for k in ["conceptPass", "citationPass", "unknownPass", "forbiddenPass"])
            except Exception as ex:
                row.update(passed=False, error=type(ex).__name__, rawOutput=client.last)
            row.update(modelCalls=client.calls, firstContentMs=client.first_ms,
                       toolCalls=client.host_tool_calls,
                       totalMs=(time.perf_counter()-client.started)*1000)
        results.append(row)
        with (args.output/"results.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False)+"\n")
        print(json.dumps({k: row[k] for k in ["id", "passed", "modelCalls", "toolCalls"]}), flush=True)
    latencies = sorted(r["totalMs"] for r in results if "totalMs" in r)
    summary = dict(scenarios=len(results), passed=sum(r["passed"] for r in results),
                   failed=[r["id"] for r in results if not r["passed"]], skipped=0,
                   localModelCalls=sum(r["modelCalls"] for r in results if r["agent"] != "fault"),
                   injectedModelCalls=sum(r["modelCalls"] for r in results if r["agent"] == "fault"),
                   p50Ms=latencies[math.ceil(len(latencies)*.5)-1], p95Ms=latencies[math.ceil(len(latencies)*.95)-1],
                   freezeSha256=hashlib.sha256((args.output/"freeze.json").read_bytes()).hexdigest(),
                   interpretation="Automated behavior checks; semantic AI review required, no human independence or production latency claim")
    (args.output/"summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
