#!/usr/bin/env python3
"""Structured semantic planning diagnostic, lineage R3 (DIAGNOSTIC ONLY).

Parent: R2 DIAGNOSTIC_REJECTED (prompt envelope / shape instruction).
R3 is a new legal lineage. Single allowed change vs R2:

  OUTPUT_SCHEMA_CLARIFICATION: add explicit nested JSON output example
  to the planning prompt. No semantic rule change.

Everything else frozen: planning-state.v1 schema, reason vocabulary,
evidence vocabulary, 90 unique cases, 3 repetitions, planning-mapping.v1,
expected labels, E22 treatment, behavioral gates, stability gate,
provider, model, endpoint, UA, DIRECT transport, max_tokens,
response_format, stream, pacing.

Harness correction (diagnostic-only, NOT semantic): R1/R2 parsed the
provider envelope raw directly with validate_planning_state, which can
never validate even when inner content is correct. R3 extracts
choices[0].message.content before strict validation. Reason codes,
evidence refs, mapping, gates unchanged. Never wired to production.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "tools"))

import semantic_planning_diagnostic as r1

PROMPT_FROZEN_SHA256_R1 = "f8a9cac7ec0230471dd5b42d259c9ca2d617d4ed82f651eac0f219da0e7340db"
LINEAGE = "SEMANTIC_PLANNING_DIAGNOSTIC_R3"
PARENT_LINEAGE = "R2 DIAGNOSTIC_REJECTED (prompt envelope / shape instruction)"
PROMPT_DIFF_CLASS = "OUTPUT_SCHEMA_CLARIFICATION"
PREFLIGHT_REQUESTS = 5
START_INTERVAL_SECONDS = 10
CONSECUTIVE_ABORT = 3

R3_ENVELOPE_CLARIFICATION = """OUTPUT ENVELOPE (STRUCTURE ONLY, NOT A SEMANTIC RULE):

Top-level JSON object MUST contain exactly these 5 keys and no others:
version, userInputRequired, externalStepRequired, directResponseSufficient,
newDurableKnowledgePresent.

Each of userInputRequired, externalStepRequired, directResponseSufficient,
newDurableKnowledgePresent MUST be an object with exactly these 3 keys
and no others: value, reasonCodes, evidenceRefs.

Forbidden: a flag as a bare boolean (for example "userInputRequired": true
is INVALID, it must be an object); reasonCodes or evidenceRefs at top
level; any free-form explanation field; any extra field at any level.

The example below shows STRUCTURE ONLY. Do NOT copy its booleans,
reason codes, or evidence refs. Choose value, reasonCodes, evidenceRefs
from the flag definitions above based on the input.

Example:
{
  "version": "planning-state.v1",
  "userInputRequired": {
    "value": true,
    "reasonCodes": ["NEED_MORE_INFO"],
    "evidenceRefs": ["node:00000000-0000-0000-0000-000000000000"]
  },
  "externalStepRequired": {
    "value": false,
    "reasonCodes": ["NO_EXTERNAL_NEED"],
    "evidenceRefs": ["context:00000000-0000-0000-0000-000000000000"]
  },
  "directResponseSufficient": {
    "value": false,
    "reasonCodes": ["DIRECT_RESPONSE_NOT_SUFFICIENT"],
    "evidenceRefs": ["answer:00000000-0000-0000-0000-000000000000"]
  },
  "newDurableKnowledgePresent": {
    "value": false,
    "reasonCodes": ["NOTHING_DURABLE"],
    "evidenceRefs": ["patch:00000000-0000-0000-0000-000000000000"]
  }
}"""

SYSTEM_PROMPT_R3 = r1.SYSTEM_PROMPT + "\n\n" + R3_ENVELOPE_CLARIFICATION


def assert_semantic_freeze() -> None:
    digest = hashlib.sha256(r1.SYSTEM_PROMPT.encode("utf-8")).hexdigest()
    if digest != PROMPT_FROZEN_SHA256_R1:
        raise SystemExit("semantic freeze violated: R1 prompt hash mismatch")
    if r1.REPETITIONS != 3 or r1.MAPPING_VERSION != "planning-mapping.v1":
        raise SystemExit("semantic freeze violated: reps or mapping changed")
    if r1.MODEL_EXPECTED != "mimo-v2.5-free":
        raise SystemExit("semantic freeze violated: model changed")
    if r1.ENDPOINT != "https://opencode.ai/zen/v1":
        raise SystemExit("semantic freeze violated: endpoint changed")
    if r1.USER_AGENT != "opencode/1.18.21":
        raise SystemExit("semantic freeze violated: UA changed")
    if r1.MAX_TOKENS != 800:
        raise SystemExit("semantic freeze violated: max_tokens changed")
    if not SYSTEM_PROMPT_R3.startswith(r1.SYSTEM_PROMPT):
        raise SystemExit("R3 prompt must extend R1 prompt verbatim")
    if "STRUCTURE ONLY" not in R3_ENVELOPE_CLARIFICATION:
        raise SystemExit("R3 example must declare STRUCTURE ONLY")
def load_credentials() -> tuple:
    import os
    key = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_KEY") or "").strip()
    model = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_MODEL") or "").strip()
    if not key or not model:
        secrets = REPO_ROOT / "backend" / ".local-secrets.env"
        if secrets.is_file():
            for line in secrets.read_text(encoding="utf-8").splitlines():
                name, sep, value = line.partition("=")
                if not sep:
                    continue
                if name.strip() == "SPEC_AGENT_EVAL_OPENCODE_KEY" and not key:
                    key = value.strip().strip("\"'")
                if name.strip() == "SPEC_AGENT_EVAL_OPENCODE_MODEL" and not model:
                    model = value.strip().strip("\"'")
    if not key:
        raise SystemExit("missing eval key (not logged)")
    if model != r1.MODEL_EXPECTED:
        raise SystemExit("model mismatch")
    return key, model


def direct_post(api_key: str, model: str, user_payload: dict) -> dict:
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT_R3},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
        "max_tokens": r1.MAX_TOKENS,
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    started = time.monotonic()
    request = urllib.request.Request(
        r1.ENDPOINT + "/chat/completions", data=body,
        headers={"User-Agent": r1.USER_AGENT,
                 "Authorization": "Bearer " + api_key,
                 "Content-Type": "application/json"},
        method="POST")
    try:
        with opener.open(request, timeout=r1.REQUEST_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8", "replace")
            return _evidence(response.status, dict(response.headers), raw, started, None)
    except urllib.error.HTTPError as exc:
        try:
            raw = exc.read(32768).decode("utf-8", "replace")
        except Exception:
            raw = ""
        return _evidence(exc.code, dict(exc.headers), raw, started, None)
    except Exception as exc:
        return _evidence(None, {}, "", started, type(exc).__name__)


def _evidence(status, headers, raw, started, transport_error):
    lowered = {key.lower(): value for key, value in headers.items()}
    keep = ["retry-after", "x-request-id", "ratelimit-limit", "ratelimit-remaining",
            "ratelimit-reset", "cf-ray", "date"]
    return {"ok": status is not None and 200 <= status < 300,
            "http_status": status,
            "transport_error": transport_error,
            "latency_ms": int((time.monotonic() - started) * 1000),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "retry_after": lowered.get("retry-after"),
            "request_id": lowered.get("x-request-id"),
            "rate_headers": {key: lowered[key] for key in keep if key in lowered},
            "raw": raw}


def paced_start(next_start: list) -> None:
    wait = next_start[0] - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    next_start[0] = time.monotonic() + START_INTERVAL_SECONDS


def synthetic_preflight_input(index: int) -> dict:
    sid = "00000000-0000-0000-0000-00000000000" + str(index)
    return {"eligibleFamilies": ["REQUEST_USER_INPUT", "RESPOND_TO_USER"],
            "event": {"kind": "R3_PREFLIGHT", "index": index},
            "observation": {"preflightIndex": index},
            "snapshot": {"snapshotId": sid,
                         "contextHash": "r3-preflight",
                         "lineage": [], "effectiveClaims": [],
                         "availableCapabilities": [], "capabilityResults": [],
                         "citableRefs": ["node:" + sid, "context:" + sid,
                                         "answer:" + sid, "patch:" + sid,
                                         "route:" + sid, "claim:" + sid,
                                         "capability:" + sid]}}


def extract_planning_content(envelope_raw: str) -> tuple:
    t = (envelope_raw or "").strip()
    if not t:
        return None, "SCHEMA_FAILURE:empty-response"
    try:
        env = json.loads(t)
    except Exception:
        return None, "SCHEMA_FAILURE:extra-free-form-text"
    if not isinstance(env, dict):
        return None, "SCHEMA_FAILURE:extra-free-form-text"
    try:
        choices = env.get("choices")
        if not isinstance(choices, list) or not choices:
            return None, "SCHEMA_FAILURE:invalid-schema"
        first = choices[0]
        if not isinstance(first, dict):
            return None, "SCHEMA_FAILURE:invalid-schema"
        msg = first.get("message")
        if not isinstance(msg, dict):
            return None, "SCHEMA_FAILURE:invalid-schema"
        content = msg.get("content")
        if not isinstance(content, str) or not content.strip():
            return None, "SCHEMA_FAILURE:invalid-schema"
        return content.strip(), None
    except Exception:
        return None, "SCHEMA_FAILURE:invalid-schema"


def validate_extracted(content: str) -> tuple:
    return r1.parse_strict(content)
def run_preflight(api_key: str, model: str, out_dir) -> dict:
    records = []
    clock = [time.monotonic()]
    for index in range(PREFLIGHT_REQUESTS):
        paced_start(clock)
        call = direct_post(api_key, model, synthetic_preflight_input(index))
        rec = {"index": index, "http_status": call["http_status"],
               "transport_error": call["transport_error"],
               "latency_ms": call["latency_ms"],
               "timestamp": call["timestamp"],
               "raw_chars": len(call["raw"]), "body": call["raw"]}
        if call["http_status"] != 200:
            rec["http_ok"] = False
            rec["json_parse_ok"] = False
            rec["schema_ok"] = False
            rec["outcome"] = "PREFLIGHT_HTTP_FAIL"
        else:
            rec["http_ok"] = True
            content, ext_fail = extract_planning_content(call["raw"])
            if ext_fail is not None:
                rec["json_parse_ok"] = False
                rec["schema_ok"] = False
                rec["outcome"] = ext_fail
                rec["extracted_chars"] = 0
            else:
                rec["extracted_chars"] = len(content)
                rec["extracted"] = content[:8000]
                try:
                    json.loads(content)
                    rec["json_parse_ok"] = True
                except Exception:
                    rec["json_parse_ok"] = False
                    rec["schema_ok"] = False
                    rec["outcome"] = "SCHEMA_FAILURE:extra-free-form-text"
                    records.append(rec)
                    continue
                state, failure = validate_extracted(content)
                if failure is not None:
                    rec["schema_ok"] = False
                    rec["outcome"] = failure
                else:
                    rec["schema_ok"] = True
                    rec["outcome"] = "SCHEMA_PASS"
                    rec["flags"] = {n: getattr(state, n).value for n in r1.FLAG_TO_FAMILY}
        records.append(rec)
    (out_dir / "preflight.jsonl").write_text(
        "\n".join(json.dumps(x, ensure_ascii=False, sort_keys=True) for x in records) + "\n",
        encoding="utf-8")
    http_ok = sum(1 for x in records if x.get("http_ok"))
    parse_ok = sum(1 for x in records if x.get("json_parse_ok"))
    schema_ok = sum(1 for x in records if x.get("schema_ok"))
    passed = (http_ok == 5 and parse_ok == 5 and schema_ok == 5)
    return {"transport_preflight": "PASS" if passed else "FAIL",
            "http_200": str(http_ok) + "/5",
            "json_parse_pass": str(parse_ok) + "/5",
            "schema_pass": str(schema_ok) + "/5",
            "statuses": [x.get("http_status") for x in records]}


def run_case(api_key: str, model: str, results_path, row: dict, model_input: dict, clock: list) -> dict:
    identity = {"arm": row["arm"], "scenario": row["scenario"],
                "variant": row["variant"], "repetition": row["repetition"]}
    rep_records = []
    for rep in range(r1.REPETITIONS):
        paced_start(clock)
        call = direct_post(api_key, model, model_input)
        if not call["ok"]:
            record = {"rep": rep, "outcome": "PROVIDER_FAILURE",
                      "http_status": call["http_status"],
                      "transport_error": call["transport_error"],
                      "retry_after": call["retry_after"],
                      "request_id": call["request_id"],
                      "rate_headers": call["rate_headers"],
                      "latency_ms": call["latency_ms"],
                      "timestamp": call["timestamp"],
                      "raw_chars": len(call["raw"]),
                      "body": call["raw"]}
        else:
            content, ext_fail = extract_planning_content(call["raw"])
            if ext_fail is not None:
                record = {"rep": rep, "outcome": ext_fail,
                          "latency_ms": call["latency_ms"],
                          "timestamp": call["timestamp"],
                          "raw_chars": len(call["raw"]),
                          "body": call["raw"]}
            else:
                state, failure = validate_extracted(content)
                if failure is not None:
                    record = {"rep": rep, "outcome": failure,
                              "latency_ms": call["latency_ms"],
                              "timestamp": call["timestamp"],
                              "raw_chars": len(call["raw"]),
                              "body": call["raw"],
                              "extracted": content[:8000]}
                else:
                    record = {"rep": rep,
                              "outcome": r1.derive_outcome(state, model_input["eligibleFamilies"]),
                              "flags": {n: getattr(state, n).value for n in r1.FLAG_TO_FAMILY},
                              "reason_codes": {n: getattr(state, n).reason_codes for n in r1.FLAG_TO_FAMILY},
                              "evidence_refs": {n: getattr(state, n).evidence_refs for n in r1.FLAG_TO_FAMILY},
                              "latency_ms": call["latency_ms"],
                              "timestamp": call["timestamp"],
                              "raw_chars": len(call["raw"]),
                              "body": call["raw"],
                              "extracted": content[:8000]}
        record["identity"] = identity
        rep_records.append(record)
        with results_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"identity": identity, "rep": rep,
                                     "expected": row["expected"],
                                     "historical_pass": row["historicalPass"],
                                     "record": record},
                                    ensure_ascii=False, sort_keys=True) + "\n")
    flag_values: dict = {}
    state_sigs = []
    for item in rep_records:
        flags = item.get("flags")
        if flags is None:
            state_sigs.append(None)
            continue
        for name, value in flags.items():
            flag_values.setdefault(name, []).append(value)
        state_sigs.append(json.dumps({"flags": flags,
                                      "reason_codes": item.get("reason_codes"),
                                      "evidence_refs": item.get("evidence_refs")},
                                     sort_keys=True))
    return {"identity": identity, "expected": row["expected"],
            "historical_pass": row["historicalPass"],
            "expected_correct_actual": row["actualCorrect"],
            "shadow_correct": row["shadowCorrect"],
            "outcomes": [item["outcome"] for item in rep_records],
            "flag_values": flag_values,
            "state_sigs": state_sigs}
def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--artifact-bplus", type=Path, required=True)
    parser.add_argument("--artifact-c", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()

    assert_semantic_freeze()
    api_key, model = load_credentials()
    check = r1.credential_check(r1.ENDPOINT, api_key)
    if not check["ok"]:
        raise SystemExit("credential check failed")

    replay_rows = r1.load_replay_rows(args.replay)
    seen = set()
    cases = []
    for row in replay_rows:
        identity = (row["arm"], row["scenario"], row["variant"], row["repetition"])
        if identity in seen:
            continue
        seen.add(identity)
        cases.append(row)
    sources = {"B+": r1.index_source_artifact(args.artifact_bplus),
               "C": r1.index_source_artifact(args.artifact_c)}
    joined = []
    join_miss = 0
    for row in cases:
        source = sources[row["arm"]].get((row["scenario"], row["variant"], row["repetition"]))
        if source is None:
            join_miss += 1
            continue
        joined.append((row, r1.build_model_input(source, row["eligibleFamilies"])))

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    r3_hash = hashlib.sha256(SYSTEM_PROMPT_R3.encode("utf-8")).hexdigest()
    manifest = {
        "experiment": LINEAGE,
        "parent_lineage": PARENT_LINEAGE,
        "parent_artifact": "backend/build/semantic-planning-diagnostic-r2/20260906-173000-71ec4ef",
        "r2_root_cause": "prompt output shape explanation not explicit (flat booleans)",
        "transport": "DIRECT",
        "proxy_bypass": "explicit empty ProxyHandler",
        "harness_correction": "extract choices[0].message.content before validate_planning_state; R1/R2 parsed envelope raw directly",
        "semantic_freeze": "unchanged except OUTPUT_SCHEMA_CLARIFICATION",
        "prompt_diff_class": PROMPT_DIFF_CLASS,
        "prompt_change": "add explicit nested JSON output example with STRUCTURE ONLY disclaimer; no semantic rule change",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "head_commit": r1._git_head(),
        "provider": "opencode-zen",
        "endpoint": r1.ENDPOINT,
        "model": model,
        "user_agent": r1.USER_AGENT,
        "prompt_sha256_r1_frozen": PROMPT_FROZEN_SHA256_R1,
        "prompt_sha256_r3": r3_hash,
        "schema_version": "planning-state.v1",
        "mapping_version": r1.MAPPING_VERSION,
        "repetitions": r1.REPETITIONS,
        "request_timeout_seconds": r1.REQUEST_TIMEOUT_SECONDS,
        "max_tokens": r1.MAX_TOKENS,
        "response_format": "json_object",
        "stream": False,
        "start_interval_seconds": START_INTERVAL_SECONDS,
        "concurrency": 1,
        "retry": 0,
        "preflight_requests": PREFLIGHT_REQUESTS,
        "circuit_breaker": "abort after 3 consecutive non-200 provider outcomes",
        "total_replay_rows": len(replay_rows),
        "unique_cases": len(cases),
        "joined_cases": len(joined),
        "join_miss": join_miss,
        "gates": r1.GATES,
        "majority_rule": "case outcome needs >=2/3 identical derived outcomes; else no-majority",
        "correction_rule": "B+ missed case corrected iff majority outcome is a family in expected",
        "regression_rule": "control case regressed iff a majority outcome exists and is not in expected",
        "credential_check": {"http_status": check["http_status"], "ok": check["ok"]},
        "prompt_full": SYSTEM_PROMPT_R3,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"manifest": str(out_dir / "manifest.json"), "joined": len(joined), "unique": len(cases), "r3_hash": r3_hash}, ensure_ascii=False), flush=True)

    preflight = run_preflight(api_key, model, out_dir)
    print(json.dumps({"preflight": preflight}, ensure_ascii=False), flush=True)
    if preflight["transport_preflight"] != "PASS":
        summary = {"transport_preflight": preflight,
                   "verdict": {"verdict": "DIAGNOSTIC_REJECTED", "subtype": "PROMPT_ENVELOPE_FAILURE",
                               "reason": "schema preflight failed; main phase never started"}}
        (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summary["verdict"], ensure_ascii=False), flush=True)
        return

    results_path = out_dir / "results.jsonl"
    case_rows = []
    clock = [time.monotonic()]
    aborted = False
    bad_streak = 0
    for index, (row, model_input) in enumerate(joined):
        case_rows.append(run_case(api_key, model, results_path, row, model_input, clock))
        for outcome in case_rows[-1]["outcomes"]:
            bad_streak = bad_streak + 1 if outcome == "PROVIDER_FAILURE" else 0
            if bad_streak >= CONSECUTIVE_ABORT:
                aborted = True
                break
        if aborted:
            break
        done = index + 1
        if done % 10 == 0 or done == len(joined):
            print(json.dumps({"progress": done, "total": len(joined)}, ensure_ascii=False), flush=True)
    summary = r1.summarize(case_rows)
    if aborted:
        summary["verdict"] = {"verdict": "INCONCLUSIVE",
                              "reason": "transport circuit breaker: 3 consecutive non-200",
                              "completed_cases": len(case_rows)}
    summary["transport_preflight"] = preflight
    summary["transport_abort"] = aborted
    summary["lineage"] = LINEAGE
    summary["prompt_sha256_r3"] = r3_hash
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary["verdict"], ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

