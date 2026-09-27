"""文件名:mapping_v2.py

planning-mapping.v2(确定性映射,仅诊断用)。

输入状态必须已通过 C1/C2 校验(harness 保证执行顺序:C1 -> C2 -> 映射)。
映射不负责修复语义状态:非法的标志组合直接抛错,而不是折叠成
PLANNING_AMBIGUOUS——该结果已不复存在。

残余优先序 u > e > d > n 只是对映射可到达的两组多真组合
(e,n) 与 (d,n) 的显式平局裁决;它不是权重表,也不会裁决
互相冲突的主标志。

资格过滤:预过滤的原始赢家通过 info["raw_winner"] / info["eligible_ok"]
上报,供 G8 统计落在合格集合之外的赢家。赢家不合格时,映射结果为
NO_WINNER。
"""
from __future__ import annotations

import hashlib

MAPPING_VERSION = "planning-mapping.v2"

MAPPING_RULES_TEXT = (
    "WAIT goal -> NO_WINNER_STRUCTURAL; "
    "u -> REQUEST_USER_INPUT; "
    "e (REQUIRED_NOW) -> INVOKE_CAPABILITY; "
    "d -> RESPOND_TO_USER; "
    "n alone -> CREATE_NODE; "
    "else NO_WINNER; residual order u>e>d>n"
)

REQUEST = "REQUEST_USER_INPUT"
INVOKE = "INVOKE_CAPABILITY"
RESPOND = "RESPOND_TO_USER"
CREATE = "CREATE_NODE"
NO_WINNER = "NO_WINNER"
NO_WINNER_STRUCTURAL = "NO_WINNER_STRUCTURAL"


def mapping_hash() -> str:
    return hashlib.sha256(
        (MAPPING_VERSION + "|" + MAPPING_RULES_TEXT).encode("utf-8")
    ).hexdigest()


def derive_outcome(state: dict, eligible_families) -> tuple:
    """映射一个已通过 C1/C2 的状态。返回 (结果, info 字典)。"""
    eligible = list(eligible_families or [])
    u = state["userInputRequired"]["value"]
    e = state["externalStepRequired"]["value"]
    d = state["directResponseSufficient"]["value"]
    n = state["newDurableKnowledgePresent"]["value"]
    if (u and d) or (u and e) or (u and n) or (d and e):
        raise ValueError("mapping received illegal flag combination; "
                         "C2 must reject before mapping")
    if state.get("goalType") == "WAIT_FOR_RUNTIME_DEPENDENCY":
        return NO_WINNER_STRUCTURAL, {"raw_winner": None,
                                      "eligible_ok": True,
                                      "structural": True}
    raw = None
    if u:
        raw = REQUEST
    elif e:
        assess = state["externalStepRequired"].get("capabilityAssessment")
        if not isinstance(assess, dict) or \
                assess.get("executionNecessity") != "REQUIRED_NOW":
            raise ValueError("mapping received e=true without REQUIRED_NOW;"
                             " C2 must reject before mapping")
        raw = INVOKE
    elif d:
        raw = RESPOND
    elif n:
        raw = CREATE
    if raw is None:
        return NO_WINNER, {"raw_winner": None, "eligible_ok": True,
                           "structural": False}
    if raw in eligible:
        return raw, {"raw_winner": raw, "eligible_ok": True,
                     "structural": False}
    return NO_WINNER, {"raw_winner": raw, "eligible_ok": False,
                       "structural": False}
