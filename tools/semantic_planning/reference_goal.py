"""文件名:reference_goal.py

G1-G5 参考目标推导(harness 计算,确定性)。

只读取冻结模型输入中"打分前"的可观察量:effectiveClaims
(含 patch claims)与 capabilityResults。绝不读取模型标志、
reason codes、映射结果或基准期望标签。

各规则按构造互斥;求值顺序不影响结果。带版本号,便于
manifest 溯源。
"""
from __future__ import annotations

import hashlib

REFERENCE_GOAL_VERSION = "r4-goalref.v1"

GOAL_RULES_TEXT = (
    "G1: no claims anywhere -> UNDERSTAND_USER_INTENT; "
    "G2: any unresolved claim -> RESOLVE_USER_CHOICE; "
    "G3: no unresolved + results non-empty -> GATHER_EXTERNAL_EVIDENCE; "
    "G4: no unresolved + results empty + confirmed conf>=0.5 -> "
    "PRODUCE_DIRECT_RESPONSE; "
    "G5: fallback -> UNDERSTAND_USER_INTENT"
)

CONF_THRESHOLD = 0.5


def reference_goal_hash() -> str:
    return hashlib.sha256(
        (REFERENCE_GOAL_VERSION + "|" + GOAL_RULES_TEXT).encode("utf-8")
    ).hexdigest()


def _get(mapping, *names):
    for name in names:
        if isinstance(mapping, dict) and name in mapping:
            return mapping[name]
    return None


def _iter_all_claims(model_input: dict):
    snap = _get(model_input, "snapshot")
    if not isinstance(snap, dict):
        return
    for claim in _get(snap, "effectiveClaims", "effective_claims") or []:
        if isinstance(claim, dict):
            yield claim
    for entry in _get(snap, "lineage", "lineageEntries") or []:
        if not isinstance(entry, dict):
            continue
        for patch in _get(entry, "patches") or []:
            if not isinstance(patch, dict):
                continue
            for claim in _get(patch, "claims") or []:
                if isinstance(claim, dict):
                    yield claim


def _conf(claim: dict) -> float:
    try:
        return float(_get(claim, "confidence"))
    except (TypeError, ValueError):
        return 0.0


def derive_reference_goal_type(model_input: dict) -> str:
    """机械执行 G1-G5 推导。完备:每个输入都映射到唯一目标。"""
    claims = list(_iter_all_claims(model_input or {}))
    if not claims:
        return "UNDERSTAND_USER_INTENT"  # G1
    if any(_get(c, "status") == "unresolved" for c in claims):
        return "RESOLVE_USER_CHOICE"  # G2
    snap = _get(model_input, "snapshot") or {}
    results = _get(snap, "capabilityResults", "capability_results") or []
    if len(results) > 0:
        return "GATHER_EXTERNAL_EVIDENCE"  # G3
    if any(_get(c, "status") == "confirmed" and _conf(c) >= CONF_THRESHOLD
           for c in claims):
        return "PRODUCE_DIRECT_RESPONSE"  # G4
    return "UNDERSTAND_USER_INTENT"  # G5 兜底回退
