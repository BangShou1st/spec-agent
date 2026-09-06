"""planning-mapping.v2 (deterministic, diagnostic-only).

Input states MUST already be C1/C2-clean (harness enforces order:
C1 -> C2 -> mapping). Mapping never repairs semantic state: illegal
flag combinations raise instead of collapsing to PLANNING_AMBIGUOUS,
which no longer exists as an outcome.

Residual order u > e > d > n is an explicit tie-break for the only two
mapping-reachable multi-true pairs (e,n) and (d,n). It is not a weight
table and never adjudicates conflicting primaries.

Eligibility: the pre-filter winner is reported via info["raw_winner"] /
info["eligible_ok"] so G8 can count winners outside the eligible set.
An ineligible winner yields NO_WINNER as the mapped outcome.
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
    """Map a C1/C2-clean state. Returns (outcome, info dict)."""
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
