"""文件名:planning_v2.py

planning-state.v2 schema 常量 + C1 原始契约校验器。

仅诊断用,仅依赖标准库。C1 覆盖机器可校验的形状/枚举/reason code/
evidence/引用/可空性违规。凡在此处失败的一律返回
CONTRACT_VIOLATION_C1,不再进入 C2 或映射。

reason code 的极性(真标志只允许"真码")在 C1 层强制:取自
错误极性子集的 code 对该取值而言就是非法 reason code。
"""
from __future__ import annotations

import hashlib
import json

SCHEMA_VERSION = "planning-state.v2"

GOAL_TYPES = (
    "UNDERSTAND_USER_INTENT",
    "RESOLVE_USER_CHOICE",
    "PRODUCE_DIRECT_RESPONSE",
    "GATHER_EXTERNAL_EVIDENCE",
    "WAIT_FOR_RUNTIME_DEPENDENCY",
)

GAP_TYPES = (
    "intent_gap",
    "choice_gap",
    "confirmation_gap",
    "authorization_gap",
    "argument_gap",
)

EVIDENCE_PREFIXES = (
    "node:",
    "answer:",
    "patch:",
    "context:",
    "route:",
    "claim:",
    "capability:",
    "event:",
)

RISK_LEVELS = (
    "READ_ONLY",
    "LOCAL_DURABLE",
    "EXTERNAL_IRREVERSIBLE",
)

ARG_COMPLETENESS = (
    "GROUNDED",
    "PARTIAL",
    "MISSING",
    "NOT_REQUIRED",
)

AUTH_STATUS = (
    "CONFIRMED",
    "PENDING",
    "MISSING",
    "NOT_REQUIRED",
)

NECESSITY = (
    "REQUIRED_NOW",
    "PREFERRED",
    "OPTIONAL",
)

# 每个标志的 reason code 按极性分组:(真码, 假码)。
REASON_CODES = {
    "userInputRequired": (
        ("INTENT_GAP", "CHOICE_GAP", "CONFIRMATION_GAP",
         "AUTHORIZATION_GAP", "ARGUMENT_GAP"),
        ("USER_INPUT_SUFFICIENT",),
    ),
    "externalStepRequired": (
        ("EXTERNAL_EVIDENCE_REQUIRED", "EXTERNAL_ACTION_REQUIRED",
         "ARGUMENTS_GROUNDED"),
        ("NO_EXTERNAL_NEED", "CAPABILITY_RELEVANT_NOT_REQUIRED",
         "INSUFFICIENT_ARGUMENTS", "INSUFFICIENT_AUTHORIZATION",
         "SAFER_PATH_AVAILABLE"),
    ),
    "directResponseSufficient": (
        ("GROUNDED_RESPONSE_AVAILABLE", "GOAL_ACHIEVED"),
        ("NO_GROUNDED_CONTENT", "AWAITING_USER_INPUT",
         "AWAITING_EXTERNAL_RESULT"),
    ),
    "newDurableKnowledgePresent": (
        ("NOVEL_SEMANTIC_UNIT",),
        ("REDUNDANT_WITH_EXISTING", "NOT_STANDALONE", "NOT_DURABLE",
         "REPHRASING"),
    ),
}

FLAG_NAMES = tuple(REASON_CODES)
TOP_KEYS = ("version", "goalType") + FLAG_NAMES


def schema_spec() -> dict:
    """规范的机器可读 schema 说明(供 manifest 哈希)。"""
    return {
        "version": SCHEMA_VERSION,
        "goalTypes": list(GOAL_TYPES),
        "gapTypes": list(GAP_TYPES),
        "riskLevels": list(RISK_LEVELS),
        "argumentCompleteness": list(ARG_COMPLETENESS),
        "authorizationStatus": list(AUTH_STATUS),
        "executionNecessity": list(NECESSITY),
        "reasonCodes": {k: [list(t), list(f)]
                        for k, (t, f) in REASON_CODES.items()},
        "evidencePrefixes": list(EVIDENCE_PREFIXES),
    }


def schema_hash() -> str:
    return hashlib.sha256(
        json.dumps(schema_spec(), sort_keys=True).encode("utf-8")).hexdigest()


def reason_vocab_hash() -> str:
    flat = sorted(code for t, f in REASON_CODES.values()
                  for code in list(t) + list(f))
    return hashlib.sha256(json.dumps(flat).encode("utf-8")).hexdigest()


def evidence_vocab_hash() -> str:
    return hashlib.sha256(
        json.dumps(sorted(EVIDENCE_PREFIXES)).encode("utf-8")).hexdigest()


def _get(mapping: dict, *names):
    for name in names:
        if isinstance(mapping, dict) and name in mapping:
            return mapping[name]
    return None


def _snapshot_of(model_input: dict) -> dict:
    snap = _get(model_input, "snapshot")
    return snap if isinstance(snap, dict) else {}


def resolvable_refs(model_input: dict) -> set:
    """从实际模型输入构建允许的 evidence-ref 集合。

    只接受冻结的 camelCase 形状。仅有前缀不算可解析。
    """
    refs: set = set()
    event = _get(model_input, "event")
    if isinstance(event, dict):
        for key in event:
            refs.add("event:" + str(key))
    snap = _snapshot_of(model_input)
    sid = _get(snap, "snapshotId", "snapshot_id")
    if sid:
        refs.add("context:" + str(sid))
    for key in ("routeId", "route_id"):
        rid = _get(snap, key)
        if rid:
            refs.add("route:" + str(rid))
    rctx = _get(snap, "routeContext", "route_context")
    if isinstance(rctx, dict):
        for key in ("routeId", "route_id", "tipNodeId", "tip_node_id"):
            val = _get(rctx, key)
            if val:
                refs.add("route:" + str(val))
    for entry in _get(snap, "lineage", "lineageEntries") or []:
        if not isinstance(entry, dict):
            continue
        node = _get(entry, "node")
        if isinstance(node, dict) and _get(node, "id"):
            refs.add("node:" + str(_get(node, "id")))
        answer = _get(entry, "answer")
        if isinstance(answer, dict) and _get(answer, "id"):
            refs.add("answer:" + str(_get(answer, "id")))
        for patch in _get(entry, "patches") or []:
            if not isinstance(patch, dict):
                continue
            pid = _get(patch, "id")
            if pid:
                refs.add("patch:" + str(pid))
            for i, _claim in enumerate(_get(patch, "claims") or []):
                refs.add("claim:patch/" + str(pid) + "/" + str(i))
    for i, _claim in enumerate(_get(snap, "effectiveClaims",
                                    "effective_claims") or []):
        refs.add("claim:effective/" + str(i))
    for cap in _get(snap, "availableCapabilities",
                     "available_capabilities") or []:
        if isinstance(cap, dict) and _get(cap, "id"):
            refs.add("capability:" + str(_get(cap, "id")))
    return refs


def _check_flag_block(name: str, block, allowed: set):
    """校验单个标志块。返回 C1 错误串,合法则返回 None。"""
    if not isinstance(block, dict):
        return "C1:BAD_TYPE:" + name
    if name == "userInputRequired":
        want = {"value", "gapType", "reasonCodes", "evidenceRefs"}
    elif name == "externalStepRequired":
        want = {"value", "capabilityAssessment", "reasonCodes",
                "evidenceRefs"}
    else:
        want = {"value", "reasonCodes", "evidenceRefs"}
    if set(block) != want:
        return "C1:BAD_KEYS:" + name
    value = block.get("value")
    if type(value) is not bool:
        return "C1:BAD_TYPE:" + name + ".value"
    codes = block.get("reasonCodes")
    if not isinstance(codes, list) or not codes:
        return "C1:EMPTY_REASONS:" + name
    if any(not isinstance(c, str) for c in codes):
        return "C1:BAD_REASON_CODE:" + name
    if len(set(codes)) != len(codes):
        return "C1:DUPLICATE_REASON:" + name
    true_codes, false_codes = REASON_CODES[name]
    pool = true_codes if value else false_codes
    if any(c not in pool for c in codes):
        return "C1:BAD_REASON_CODE:" + name
    refs = block.get("evidenceRefs")
    if not isinstance(refs, list) or not refs:
        return "C1:EMPTY_EVIDENCE:" + name
    if any(not isinstance(r, str) or not r.strip() for r in refs):
        return "C1:BAD_EVIDENCE_REF:" + name
    if len(set(refs)) != len(refs):
        return "C1:DUPLICATE_EVIDENCE:" + name
    for ref in refs:
        if not ref.startswith(EVIDENCE_PREFIXES):
            return "C1:BAD_EVIDENCE_REF:" + name
        if ref not in allowed:
            return "C1:UNRESOLVED_REF:" + name + ":" + ref
    if name == "userInputRequired":
        gap = block.get("gapType")
        if value:
            if gap not in GAP_TYPES:
                return "C1:BAD_GAPTYPE:" + name
        elif gap is not None:
            return "C1:BAD_NULLABILITY:" + name + ".gapType"
    if name == "externalStepRequired":
        assess = block.get("capabilityAssessment")
        if value:
            if not isinstance(assess, dict):
                return "C1:MISSING_ASSESSMENT:" + name
            if set(assess) != {"capabilityId", "riskLevel",
                               "argumentCompleteness", "authorizationStatus",
                               "executionNecessity"}:
                return "C1:BAD_KEYS:" + name + ".capabilityAssessment"
            if not isinstance(assess.get("capabilityId"), str) or \
                    not assess["capabilityId"].strip():
                return "C1:BAD_TYPE:" + name + ".capabilityId"
            if assess.get("riskLevel") not in RISK_LEVELS:
                return "C1:BAD_ENUM:" + name + ".riskLevel"
            if assess.get("argumentCompleteness") not in ARG_COMPLETENESS:
                return "C1:BAD_ENUM:" + name + ".argumentCompleteness"
            if assess.get("authorizationStatus") not in AUTH_STATUS:
                return "C1:BAD_ENUM:" + name + ".authorizationStatus"
            if assess.get("executionNecessity") not in NECESSITY:
                return "C1:BAD_ENUM:" + name + ".executionNecessity"
        elif assess is not None:
            return "C1:BAD_NULLABILITY:" + name + ".capabilityAssessment"
    return None


def validate_c1(parsed, model_input: dict):
    """C1 校验。返回 (state 字典, None) 或 (None, C1 错误)。"""
    if not isinstance(parsed, dict):
        return None, "C1:BAD_SHAPE"
    if set(parsed) != set(TOP_KEYS):
        return None, "C1:BAD_TOP_KEYS"
    if parsed.get("version") != SCHEMA_VERSION:
        return None, "C1:BAD_VERSION"
    if parsed.get("goalType") not in GOAL_TYPES:
        return None, "C1:BAD_ENUM:goalType"
    if not isinstance(model_input, dict):
        return None, "C1:BAD_MODEL_INPUT"
    allowed = resolvable_refs(model_input)
    for name in FLAG_NAMES:
        err = _check_flag_block(name, parsed.get(name), allowed)
        if err is not None:
            return None, err
    return parsed, None

