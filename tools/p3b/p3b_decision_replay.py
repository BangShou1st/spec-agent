#!/usr/bin/env python3
"""文件名:p3b_decision_replay.py

用途:P3-B 的 **Payload / protocol 故障类** 确定性回放。

把一批冻结的模型输出回放给**真实的** Python DECISION 引擎
(``spec_agent_brain.decision.handle_decision``),再把被接受的提案按其动作族
对照 **Java fail-closed 契约的等价形状要求**判分,从而把 P3-B 任务书要求分开
统计的三类东西彻底分开:

  * **契约违约**(非 JSON / 未知字段 / 未 grounding 引用 / 未知动作族 /
    未暴露冲突) —— 模型输出质量,由 Brain 在自身契约边界 fail-closed;
  * **payload 形状违约** —— 动作族可以接受,但参数不符合结构化契约;
    由 Java 的 ``AgentBrainResponseValidator`` fail-closed 拦截
    (Brain 的 ``payload`` 是有界的通用 map,刻意不在这一层做按族形状校验);
  * **INFRA**(provider / 传输失败) —— 不计为任何决策错误。

诚实性:fixture 是 ``SYNTHETIC_FROZEN_FIXTURE``,由 prompt 契约文档与既有真实
缺陷记录逐字重构而来,**不是**本轮捕获的真实模型响应。因此这里度量的是
**契约边界行为**,不是真实模型决策质量。真实模型证据由独立 live suite 记录。

本工具只读、离线:不写隐藏状态,不访问网络,不改动任何生产路径。
"""
from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT / "agent-brain" / "src") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "agent-brain" / "src"))

from spec_agent_brain.contracts.inputs import parse_request_envelope  # noqa: E402
from spec_agent_brain.decision import handle_decision  # noqa: E402
from spec_agent_brain.decision.engine import (  # noqa: E402
    ActionIneligibleBrainError,
    AmbiguousSourceRefsError,
    BrainContractError,
    ConflictSurfacingError,
    UngroundedReferenceError,
)
from spec_agent_brain.model_client.base import (  # noqa: E402
    ChatMessage,
    Completion,
    ModelClientError,
    require_call_type,
)

BASE_REQUEST = REPO_ROOT / "contracts" / "fixtures" / "agent-input-valid.json"
FIXTURES = Path(__file__).resolve().parent / "p3b_replay_fixtures.json"
JAVA_BOUNDARY_FIXTURES = REPO_ROOT / "contracts" / "fixtures" / "p3b-java-payload-boundary.json"

ACCEPTED = "ACCEPTED_PROPOSAL"
CONTRACT_NON_JSON = "CONTRACT_VIOLATION_NON_JSON"
CONTRACT_SCHEMA = "CONTRACT_VIOLATION_MODEL_OUTPUT_SCHEMA"
CONTRACT_UNGROUNDED = "CONTRACT_VIOLATION_UNGROUNDED_REFERENCE"
CONTRACT_AMBIGUOUS = "CONTRACT_VIOLATION_AMBIGUOUS_SOURCE_REFS"
CONTRACT_CONFLICT = "CONTRACT_VIOLATION_CONFLICT_SURFACING"
CONTRACT_INELIGIBLE = "CONTRACT_VIOLATION_ACTION_INELIGIBLE"
INFRA = "INFRA_MODEL_CLIENT_FAILURE"


class ReplayModelClient:
    """把一条冻结的模型输出回放给真实的 DECISION 引擎(只记录调用次数)。

    绝不做任何语义改写:回放的就是 fixture 里的原始文本,因此分类结果
    完全由引擎的真实契约路径决定,而不是由本工具决定。
    """

    def __init__(self, content: str, fail_with: str | None = None) -> None:
        self._content = content
        self._fail_with = fail_with
        self.calls = 0

    def complete(self, run_id: str, call_type: str,
                 messages,  # noqa: ANN001 - Sequence[ChatMessage]
                 max_output_tokens: int = 2048) -> Completion:
        require_call_type(call_type)
        if call_type != "DECISION":
            raise ModelClientError(f"replay client only serves DECISION, got {call_type}")
        if not messages or not any(isinstance(m, ChatMessage) for m in messages):
            raise ModelClientError("replay client requires rendered chat messages")
        self.calls += 1
        if self._fail_with is not None:
            raise ModelClientError(self._fail_with)
        return Completion(content=self._content, finish_reason="stop")


def build_request(fixture: dict):
    """按 fixture 的 requestOverrides 构造真实请求信封。"""
    payload = json.loads(BASE_REQUEST.read_text(encoding="utf-8"))
    overrides = fixture.get("requestOverrides") or {}
    if "availableCapabilities" in overrides:
        payload["snapshot"]["availableCapabilities"] = overrides["availableCapabilities"]
    if "effectiveClaims" in overrides:
        payload["snapshot"]["effectiveClaims"] = overrides["effectiveClaims"]
    if "decisionBudget" in overrides:
        payload["decisionBudget"] = overrides["decisionBudget"]
    # 深拷贝一次,保证 fixture 之间绝不互相污染。
    return parse_request_envelope(copy.deepcopy(payload))


def classify(fixture: dict) -> dict:
    """回放一条 fixture,给出确定性的结果分类。"""
    request = build_request(fixture)
    client = ReplayModelClient(fixture["modelOutput"], fixture.get("modelClientFailure"))
    try:
        response = handle_decision(request, client)
    except UngroundedReferenceError as error:
        return outcome(CONTRACT_UNGROUNDED, str(error), client.calls)
    except AmbiguousSourceRefsError as error:
        return outcome(CONTRACT_AMBIGUOUS, str(error), client.calls)
    except ConflictSurfacingError as error:
        return outcome(CONTRACT_CONFLICT, str(error), client.calls)
    except ActionIneligibleBrainError as error:
        return outcome(CONTRACT_INELIGIBLE, str(error), client.calls)
    except BrainContractError as error:
        message = str(error)
        kind = CONTRACT_NON_JSON if "not valid JSON" in message else CONTRACT_SCHEMA
        return outcome(kind, message, client.calls)
    except ModelClientError as error:
        return outcome(INFRA, str(error), client.calls)
    return outcome(
        ACCEPTED, "",
        client.calls,
        action_family=response.action_proposal.action_family,
        payload=response.action_proposal.payload,
    )


def outcome(kind: str, detail: str, calls: int,
            action_family: str | None = None,
            payload: dict | None = None) -> dict:
    return {"outcome": kind, "detail": detail, "modelCalls": calls,
            "actionFamily": action_family, "payload": payload}


def payload_shape_valid(action_family: str | None, payload: dict | None) -> bool | None:
    """按动作族对照 Java fail-closed 契约的**等价**形状要求。

    有意只表达 Java 校验器已经强制的那几条最小形状规则;返回 ``None`` 表示
    该动作族在这里没有可判的形状(信息不足时如实标记,绝不伪造通过率)。
    """
    if action_family is None:
        return None
    payload = payload or {}
    if action_family == "REQUEST_USER_INPUT":
        return (is_non_blank_string(payload.get("questionText"))
                and isinstance(payload.get("options"), list)
                and isinstance(payload.get("allowFreeAnswer"), bool))
    if action_family == "CREATE_NODE":
        # Java AgentBrainResponseValidator and ProposalActionExecutor both use
        # INTERACTION when kind is absent/null.
        kind = payload.get("kind")
        if kind is None:
            kind = "INTERACTION"
        if kind == "INTERACTION":
            return payload_shape_valid("REQUEST_USER_INPUT", payload)
        return isinstance(payload.get("content"), dict) and is_non_blank_string(
            (payload.get("content") or {}).get("text"))
    if action_family == "INVOKE_CAPABILITY":
        arguments = payload.get("arguments")
        return (is_non_blank_string(payload.get("capabilityId"))
                and (arguments is None or isinstance(arguments, dict)))
    if action_family == "RESPOND_TO_USER":
        # The Java wire validator imposes no message shape here. The executor
        # returns a null message for a missing/non-string value; it does not
        # reject the action. Model the layer boundary rather than inventing a
        # stricter Python-only contract.
        return True
    return None


def executor_disposition(action_family: str | None, payload: dict | None) -> str:
    """Mirror the Java executor's observable disposition for relevant families."""
    if action_family is None:
        return "NOT_REACHED"
    payload = payload or {}
    if action_family == "CREATE_NODE":
        kind = payload.get("kind")
        return ("CREATE_INTERACTION_NODE"
                if kind is None or kind == "INTERACTION"
                else "CREATE_WORKSPACE_NODE")
    if action_family == "RESPOND_TO_USER":
        return ("EMITS_RESPONSE_MESSAGE" if isinstance(payload.get("message"), str)
                else "COMPLETES_WITHOUT_RESPONSE_MESSAGE")
    return "NOT_MODELED"


def declared_required_fields(input_schema: dict) -> list[str]:
    """Mirror ActionEligibilityValidator's two supported descriptor shapes."""
    required: list[str] = []
    properties = input_schema.get("properties")
    if isinstance(properties, dict):
        names = input_schema.get("required")
        if isinstance(names, list):
            required.extend(str(name) for name in names if name is not None)
        for name, definition in properties.items():
            if (isinstance(definition, dict) and is_required_flag(definition.get("required"))
                    and str(name) not in required):
                required.append(str(name))
        return required
    for name, definition in input_schema.items():
        if isinstance(definition, dict) and is_required_flag(definition.get("required")):
            required.append(str(name))
    return required


def is_required_flag(value) -> bool:  # noqa: ANN001 - schema value
    return value is True or (isinstance(value, str) and value.lower() == "true")


def eligibility_disposition(fixture: dict, action_family: str | None,
                            payload: dict | None) -> str:
    """Report the specific descriptor-based eligibility rule this replay can assess."""
    if action_family != "INVOKE_CAPABILITY":
        return "NOT_MEASURED"
    overrides = fixture.get("requestOverrides") or {}
    capabilities = overrides.get("availableCapabilities")
    if not isinstance(capabilities, list):
        return "NOT_MEASURED"
    payload = payload or {}
    descriptor = next((item for item in capabilities
                       if isinstance(item, dict)
                       and item.get("id") == payload.get("capabilityId")), None)
    if descriptor is None:
        return "VETO_CAPABILITY_NOT_VISIBLE"
    schema = descriptor.get("inputSchema")
    if not isinstance(schema, dict):
        return "NOT_MEASURED"
    arguments = payload.get("arguments")
    arguments = arguments if isinstance(arguments, dict) else {}
    for name in declared_required_fields(schema):
        value = arguments.get(name)
        if value is None or (isinstance(value, str) and not value.strip()):
            return "VETO_MISSING_REQUIRED_CAPABILITY_ARGUMENT"
    return "NOT_VETOED_BY_REQUIRED_ARGUMENT_RULE"


def java_boundary_evidence(fixture: dict, replayed: dict) -> dict:
    """Report the Brain and Java layers reached; policy is outside replay scope."""
    family = replayed["actionFamily"]
    payload = replayed["payload"]
    if replayed["outcome"] != ACCEPTED:
        return {
            "brain_decision_contract": "NOT_REACHED" if replayed["outcome"] == INFRA
            else "REJECTED",
            "wire_contract": "NOT_REACHED",
            "eligibility": "NOT_REACHED",
            "executor": "NOT_REACHED",
            "policy": "NOT_EVALUATED",
        }

    shape_valid = payload_shape_valid(family, payload)
    eligibility = eligibility_disposition(fixture, family, payload)
    if shape_valid is False:
        return {
            "brain_decision_contract": "ACCEPTED_PROPOSAL",
            "wire_contract": "REJECTED",
            "eligibility": "NOT_REACHED",
            "executor": "NOT_REACHED",
            "policy": "NOT_EVALUATED",
        }
    if eligibility.startswith("VETO_"):
        executor = "NOT_REACHED_BY_VETO"
    else:
        executor = executor_disposition(family, payload)
    return {
        "brain_decision_contract": "ACCEPTED_PROPOSAL",
        "wire_contract": "ACCEPTED" if shape_valid is True else "NOT_MEASURED",
        "eligibility": eligibility,
        "executor": executor,
        "policy": "NOT_EVALUATED",
    }


def is_non_blank_string(value) -> bool:  # noqa: ANN001 - wire value
    return isinstance(value, str) and value.strip() != ""


def score(fixture: dict, replayed: dict) -> dict:
    """把回放结果与 fixture 声明的期望对齐(只在真的走到该边界时判分)。"""
    expected_outcome = fixture["expectedOutcome"]
    expected_family = fixture.get("expectedActionFamily")
    observed_family = replayed["actionFamily"]
    payload_valid = payload_shape_valid(observed_family, replayed["payload"])
    return {
        "id": fixture["id"],
        "category": fixture["category"],
        "class": fixture["class"],
        "provenance": fixture["provenance"],
        "expectedOutcome": expected_outcome,
        "observedOutcome": replayed["outcome"],
        "outcomeMatched": replayed["outcome"] == expected_outcome,
        "expectedActionFamily": expected_family,
        "observedActionFamily": observed_family,
        "actionMatched": observed_family == expected_family,
        "expectedPayloadValid": fixture.get("expectedPayloadValid"),
        "observedPayloadValid": payload_valid,
        "payloadMatched": payload_valid == fixture.get("expectedPayloadValid"),
        "javaBoundary": java_boundary_evidence(fixture, replayed),
        "modelCalls": replayed["modelCalls"],
    }


def run_all() -> dict:
    corpus = json.loads(FIXTURES.read_text(encoding="utf-8"))
    records = []
    for fixture in corpus["fixtures"]:
        records.append(score(fixture, classify(fixture)))
    return {
        "schemaVersion": "p3b-decision-replay.v2",
        "suite": "p3b-decision-protocol-replay",
        "honesty": corpus["honesty"],
        "boundary": "real Python DECISION engine + Java fail-closed contract shapes; "
                    "no network, no provider call; wire/eligibility/executor/policy layers "
                    "are reported separately",
        "fixtureCount": len(records),
        "outcomeMatched": sum(1 for r in records if r["outcomeMatched"]),
        "actionMatched": sum(1 for r in records if r["actionMatched"]),
        "payloadMismatches": [
            {"id": r["id"], "class": r["class"],
             "expected": r["expectedPayloadValid"], "observed": r["observedPayloadValid"]}
            for r in records if not r["payloadMatched"]
        ],
        "records": records,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="")
    arguments = parser.parse_args()
    report = run_all()
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if arguments.output:
        Path(arguments.output).write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
