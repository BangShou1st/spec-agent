"""C2 deterministic cross-checks (Architecture Section 11, checks 1-8).

Input must already be C1-clean. Returns a list of C2 violation codes;
empty means clean. The validator rejects machine-verifiable
contradictions only: it never judges novelty, and it cannot read claim
text, so a CONFIRMED authorization citation is accepted on target status
alone (documented limitation; text-content truth stays a model duty).
"""
from __future__ import annotations

from .planning_v2 import _get, resolvable_refs
from .reference_goal import CONF_THRESHOLD, derive_reference_goal_type


def _snapshot(model_input: dict) -> dict:
    snap = _get(model_input, "snapshot")
    return snap if isinstance(snap, dict) else {}


def _descriptors(model_input: dict) -> dict:
    out = {}
    for cap in _get(_snapshot(model_input), "availableCapabilities",
                     "available_capabilities") or []:
        if isinstance(cap, dict) and _get(cap, "id"):
            out[str(_get(cap, "id"))] = cap
    return out


def project_risk(descriptor: dict):
    """Mechanical descriptor -> riskLevel projection (Section 8 table).

    Returns None when the descriptor matches no known row (reserved or
    novel side-effect class): the validator cannot project, which is
    itself a C2 condition for e=true.
    """
    if not isinstance(descriptor, dict):
        return None
    read_only = _get(descriptor, "readOnly", "read_only")
    cls = _get(descriptor, "sideEffectClass", "side_effect_class")
    if read_only is True:
        return "READ_ONLY"
    if read_only is False:
        if cls == "LOCAL_DURABLE":
            return "LOCAL_DURABLE"
        if cls == "EXTERNAL_IRREVERSIBLE":
            return "EXTERNAL_IRREVERSIBLE"
    return None


def _claim_entry(model_input: dict, ref: str):
    """Resolve a claim: ref to its claim dict, or None."""
    snap = _snapshot(model_input)
    if ref.startswith("claim:effective/"):
        try:
            idx = int(ref.split("/")[-1])
        except ValueError:
            return None
        eff = _get(snap, "effectiveClaims", "effective_claims") or []
        if 0 <= idx < len(eff) and isinstance(eff[idx], dict):
            return eff[idx]
        return None
    if ref.startswith("claim:patch/"):
        parts = ref.split("/")
        if len(parts) != 3:
            return None
        _, _, tail = parts[0], parts[1], parts[2]
        pid = parts[1]
        try:
            idx = int(tail)
        except ValueError:
            return None
        for entry in _get(snap, "lineage", "lineageEntries") or []:
            if not isinstance(entry, dict):
                continue
            for patch in _get(entry, "patches") or []:
                if not isinstance(patch, dict):
                    continue
                if str(_get(patch, "id")) != pid:
                    continue
                claims = _get(patch, "claims") or []
                if 0 <= idx < len(claims) and isinstance(claims[idx],
                                                        dict):
                    return claims[idx]
                return None
    return None


def _is_confirmed(claim) -> bool:
    if not isinstance(claim, dict):
        return False
    if _get(claim, "status") != "confirmed":
        return False
    try:
        return float(_get(claim, "confidence")) >= CONF_THRESHOLD
    except (TypeError, ValueError):
        return False


def _results_for(model_input: dict, capability_id: str) -> list:
    return [r for r in
            (_get(_snapshot(model_input), "capabilityResults",
                  "capability_results") or [])
            if isinstance(r, dict) and
            str(_get(r, "capability_id", "capabilityId")) == capability_id]


def _record_approves(result: dict) -> bool:
    prov = _get(result, "provenance")
    content = _get(result, "content")
    return bool(isinstance(prov, dict) and prov) or \
        bool(isinstance(content, dict) and content)


def _autonomy_mode(model_input: dict) -> str:
    aut = _get(_snapshot(model_input), "autonomy")
    mode = _get(aut, "mode") if isinstance(aut, dict) else None
    return str(mode) if mode else ""


def _cited_claim_refs(block: dict) -> list:
    return [r for r in (block.get("evidenceRefs") or [])
            if isinstance(r, str) and r.startswith("claim:")]


def check_c2(state: dict, model_input: dict) -> list:
    """Run C2 checks 1-8. Returns violation codes (empty = clean)."""
    viol: list = []
    u = state["userInputRequired"]["value"]
    e = state["externalStepRequired"]["value"]
    d = state["directResponseSufficient"]["value"]
    n = state["newDurableKnowledgePresent"]["value"]

    # Check 1: reference goal match.
    if state.get("goalType") != derive_reference_goal_type(model_input):
        viol.append("C2:GOAL_MISMATCH")

    # Checks 2-3: mutual exclusion.
    if u and d:
        viol.append("C2:MUTEX_UD")
    if u and e:
        viol.append("C2:MUTEX_UE")
    if u and n:
        viol.append("C2:MUTEX_UN")

    # Checks 4-6: capability binding, risk, threshold, auth.
    if e:
        assess = state["externalStepRequired"].get("capabilityAssessment")
        descs = _descriptors(model_input)
        cap_id = assess.get("capabilityId") if isinstance(assess,
                                                          dict) else None
        desc = descs.get(str(cap_id)) if cap_id else None
        if desc is None:
            viol.append("C2:UNKNOWN_CAPABILITY")
        else:
            projected = project_risk(desc)
            if projected is None:
                viol.append("C2:CANNOT_PROJECT_RISK")
            elif assess.get("riskLevel") != projected:
                viol.append("C2:RISK_MISMATCH")
            # No-splicing: every capability: ref in the e block names cap_id.
            for ref in state["externalStepRequired"].get("evidenceRefs",
                                                         []):
                if ref.startswith("capability:") and \
                        ref != "capability:" + str(cap_id):
                    viol.append("C2:SPLICED_EVIDENCE")
                    break
            # Necessity gate.
            if assess.get("executionNecessity") != "REQUIRED_NOW":
                viol.append("C2:NEEDS_REQUIRED_NOW")
            # Threshold row (check 4/5 machinery, incl. ADVISOR escape).
            risk = assess.get("riskLevel")
            args = assess.get("argumentCompleteness")
            auth = assess.get("authorizationStatus")
            if risk == "READ_ONLY":
                if args not in ("GROUNDED", "NOT_REQUIRED"):
                    viol.append("C2:THRESHOLD_ROW")
                if auth != "NOT_REQUIRED":
                    viol.append("C2:THRESHOLD_ROW")
            elif risk == "LOCAL_DURABLE":
                if args != "GROUNDED":
                    viol.append("C2:THRESHOLD_ROW")
                advisor_escape = (auth == "NOT_REQUIRED" and
                                  _autonomy_mode(model_input) != "ADVISOR")
                if auth != "CONFIRMED" and not advisor_escape:
                    viol.append("C2:THRESHOLD_ROW")
            elif risk == "EXTERNAL_IRREVERSIBLE":
                if args != "GROUNDED" or auth != "CONFIRMED":
                    viol.append("C2:IRREVERSIBLE_ROW")
            # Argument citation presence (deterministic half of hybrid).
            cited = [r for r in _cited_claim_refs(
                state["externalStepRequired"])
                if _claim_entry(model_input, r) is not None]
            if args == "GROUNDED" and not cited:
                viol.append("C2:ARGS_UNCITED")
            if args == "MISSING" and cited:
                viol.append("C2:ARGS_CONTRADICTION")
            # Authorization citation (check 6): path A or path B.
            if auth == "CONFIRMED":
                path_a = any(
                    _is_confirmed(_claim_entry(model_input, r))
                    for r in _cited_claim_refs(
                        state["externalStepRequired"]))
                path_b = (
                    any(_record_approves(r)
                        for r in _results_for(model_input, str(cap_id)))
                    and ("capability:" + str(cap_id)) in
                    state["externalStepRequired"].get("evidenceRefs", []))
                if not (path_a or path_b):
                    viol.append("C2:FAKE_AUTH")

    # Check 7: direct grounding + citation.
    if d:
        if derive_reference_goal_type(model_input) != \
                "PRODUCE_DIRECT_RESPONSE":
            viol.append("C2:DIRECT_UNGROUNDED")
        else:
            grounded = [r for r in _cited_claim_refs(
                state["directResponseSufficient"])
                if _is_confirmed(_claim_entry(model_input, r))]
            if not grounded:
                viol.append("C2:DIRECT_UNCITED")
            if not any(_is_confirmed(c) for c in
                       _iter_input_claims(model_input)):
                viol.append("C2:DIRECT_UNGROUNDED")

    # Check 8: WAIT implies quiet flags.
    if state.get("goalType") == "WAIT_FOR_RUNTIME_DEPENDENCY":
        if u or e or d or n:
            viol.append("C2:WAIT_NONQUIET")

    return sorted(set(viol))


def _iter_input_claims(model_input: dict):
    snap = _snapshot(model_input)
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
