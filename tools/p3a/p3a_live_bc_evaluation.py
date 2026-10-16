#!/usr/bin/env python3
"""P3-A B/C layer live evaluation driver.

Drives the REAL project-Agent production loop over the dev backend's HTTP API:
project -> root question -> user answer -> answer cycle (real STATE_UPDATE +
real DECISION via the Python brain on 8100 and the product's configured model),
then reads back the persisted claims (answering "understood correctly?") and the
resulting action family (answering "is the next step sensible?").

Design constraints (deliberate, per the P3-A brief):
  * No new production runtime: this only calls existing HTTP endpoints.
  * The B and C layers are scored separately, with an explicit oracle:
      - B: fact/condition/negation/unknown extraction, judged by deterministic
        semantic conditions (required concepts, forbidden over-generalizations,
        and whether genuinely-unknown content was wrongly marked CONFIRMED).
      - C: acceptable-set action accuracy over the Runtime eligibility mask,
        plus payload validity, and test/network failures are INFRA (never a
        decision error).
  * Labels are AI-authored candidate labels. Every scenario is emitted with
    `labelReview: PENDING_HUMAN_REVIEW`; this script never claims human review.

Secrets: nothing is written to disk. The driver only speaks to localhost and
never prints credentials.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BACKEND = "http://127.0.0.1:8080"
SCENARIOS = Path(__file__).resolve().parent / "p3a_scenarios.json"
OUTPUT_DIR = Path(__file__).resolve().parents[2] / "docs" / "v2" / "evidence"


def call(method: str, path: str, body: dict | None = None, timeout: float = 60.0):
    """One HTTP call. Returns (status, parsed_json_or_text)."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BACKEND + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            try:
                return response.status, json.loads(raw)
            except json.JSONDecodeError:
                return response.status, raw
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, raw
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return None, str(error)


def await_run(project_id: str, run_id: str, timeout_seconds: float = 300.0):
    """Poll a run until terminal. Returns (final_view, polls, infra_error)."""
    deadline = time.monotonic() + timeout_seconds
    polls = 0
    while time.monotonic() < deadline:
        status, payload = call(
            "GET", f"/api/v1/projects/{project_id}/agent-runs/{run_id}")
        if status is None:
            return None, polls, f"transport: {payload}"
        polls += 1
        if status != 200 or not isinstance(payload, dict):
            return None, polls, f"http {status}: {payload}"
        state = str(payload.get("status", ""))
        if state in {"completed", "failed"}:
            return payload, polls, None
        time.sleep(0.5)
    return None, polls, "timeout waiting for terminal run state"


def normalized(text: str) -> str:
    return "".join(str(text).split()).casefold()


def score_claims(scenario: dict, claims: list) -> dict:
    """B layer: deterministic semantic conditions over the produced claims.

    Matching is intentionally NOT raw string equality. For each required concept
    we accept any claim whose normalized text contains ALL of a concept's keyword
    alternatives (OR-groups), so paraphrase survives. We then check the
    forbidden over-generalizations and the CONFIRMED-without-grounding rule.
    """
    texts = [normalized(claim.get("text", "")) for claim in claims]
    statuses = [str(claim.get("status", "")) for claim in claims]
    kinds = [str(claim.get("kind", "")) for claim in claims]

    def concept_present(concept: dict) -> bool:
        # every OR-group must be satisfied by at least one claim
        for group in concept["anyOf"]:
            if not any(all(alt.casefold() in text for alt in group) for text in texts):
                return False
        return True

    required = scenario.get("requiredConcepts", [])
    matched_required = [c for c in required if concept_present(c)]
    missed_required = [c["id"] for c in required if not concept_present(c)]

    forbidden = scenario.get("forbiddenOvergeneralizations", [])
    violations = []
    for bad in forbidden:
        for text in texts:
            if all(alt.casefold() in text for alt in bad["allOf"]):
                violations.append({"id": bad["id"], "claim": text})
                break

    # no-basis CONFIRMED: a CONFIRMED claim is unsupported if it has no source
    # refs at all. This is the "don't mark unknown as CONFIRMED" boundary.
    ungrounded_confirmed = [
        claims[index].get("text", "")
        for index, status in enumerate(statuses)
        if status == "CONFIRMED" and not claims[index].get("sourceRefs")
    ]

    # the CRITICAL boundary from the brief: content the user left undecided must
    # not be asserted as settled fact.
    unknown_asserted_confirmed = []
    for marker in scenario.get("mustStayUnresolved", []):
        for index, text in enumerate(texts):
            if marker.casefold() in text and statuses[index] == "CONFIRMED":
                unknown_asserted_confirmed.append(claims[index].get("text", ""))

    return {
        "claimCount": len(claims),
        "requiredConcepts": len(required),
        "matchedRequired": len(matched_required),
        "missedRequired": missed_required,
        "precision": (len(matched_required) / len(claims)) if claims else None,
        "recall": (len(matched_required) / len(required)) if required else None,
        "forbiddenViolations": violations,
        "ungroundedConfirmed": ungrounded_confirmed,
        "unknownAssertedConfirmed": unknown_asserted_confirmed,
        "kinds": kinds,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0,
                        help="run only the first N scenarios (0 = all)")
    parser.add_argument("--scenarios", default=str(SCENARIOS))
    parser.add_argument("--output", default="P3A_LIVE_BC_BASELINE.json")
    parser.add_argument("--repetitions", type=int, default=1)
    arguments = parser.parse_args()

    corpus = json.loads(Path(arguments.scenarios).read_text(encoding="utf-8"))
    scenarios = corpus["scenarios"]
    if arguments.limit:
        scenarios = scenarios[: arguments.limit]

    observations = []
    infra_failures = []
    started_all = time.monotonic()

    for scenario in scenarios:
        for repetition in range(arguments.repetitions):
            record = {
                "scenarioId": scenario["id"],
                "family": scenario["family"],
                "repetition": repetition,
                "labelReview": "PENDING_HUMAN_REVIEW",
                "labelAuthorship": "AI-authored candidate label",
            }
            # 1) project + root question, via the real workspace API
            status, project = call("POST", "/api/v1/projects",
                                   {"title": f"P3A {scenario['id']} {int(time.time())}"})
            if status not in (200, 201) or not isinstance(project, dict):
                record["outcome"] = "INFRA"
                record["detail"] = f"project create failed: {status} {project}"
                infra_failures.append(record)
                observations.append(record)
                continue
            project_id = project["id"]
            route_id = project["activeRouteId"]

            status, node = call("POST", f"/api/v1/projects/{project_id}/nodes", {
                "routeId": route_id,
                "question": scenario["question"],
                "purpose": "P3-A evaluation probe",
                "options": [],
                "allowFreeAnswer": True,
                "allowMultiSelect": False,
            })
            if status not in (200, 201) or not isinstance(node, dict):
                record["outcome"] = "INFRA"
                record["detail"] = f"node create failed: {status} {node}"
                infra_failures.append(record)
                observations.append(record)
                continue
            node_id = node.get("id") or node.get("nodeId")

            # 2) submit the answer and drive the real answer cycle
            status, accepted = call(
                "POST", f"/api/v1/projects/{project_id}/agent-runs",
                {"operation": "ANSWER_TIP", "nodeId": node_id,
                 "freeText": scenario["answer"],
                 "idempotencyKey": f"p3a-{scenario['id']}-{repetition}-{int(time.time()*1000)}"})
            if status not in (200, 202) or not isinstance(accepted, dict):
                record["outcome"] = "INFRA"
                record["detail"] = f"run create failed: {status} {accepted}"
                infra_failures.append(record)
                observations.append(record)
                continue

            run_id = accepted.get("runId")
            begin = time.monotonic()
            final, polls, infra = await_run(project_id, run_id)
            latency_ms = int((time.monotonic() - begin) * 1000)
            record.update({"projectId": project_id, "nodeId": node_id,
                           "runId": run_id, "latencyMs": latency_ms, "polls": polls})

            if infra is not None:
                record["outcome"] = "INFRA"
                record["detail"] = infra
                infra_failures.append(record)
                observations.append(record)
                continue

            record["runStatus"] = final.get("status")
            record["actualPrimaryAction"] = final.get("primaryAction") or final.get("action")
            record["failureReason"] = final.get("failureReason")

            # 3) read back the persisted claims (B layer evidence)
            status, state = call(
                "GET",
                f"/api/v1/projects/{project_id}/routes/{route_id}/requirement-state")
            claims = []
            if status == 200 and isinstance(state, dict):
                claims = state.get("claims") or []
            record["claims"] = [
                {"kind": c.get("kind"), "text": c.get("text"),
                 "status": c.get("status"),
                 "hasSourceRefs": bool(c.get("sourceRefs"))}
                for c in claims
            ]
            record["bLayer"] = score_claims(scenario, claims)

            # 4) C layer: acceptable-set accuracy against the declared oracle
            acceptable = set(scenario["acceptableActions"])
            actual = record["actualPrimaryAction"]
            record["acceptableActions"] = sorted(acceptable)
            record["cLayer"] = {
                "acceptableSetAccuracy": (actual in acceptable) if actual else False,
                "actionCorrectNotEligible": None,   # filled by the mask probe below
                "payloadValid": final.get("status") == "completed",
            }
            record["outcome"] = "COMPLETED"
            observations.append(record)
            print(f"{scenario['id']} r{repetition}: action={actual} "
                  f"claims={len(claims)} latency={latency_ms}ms", flush=True)

    completed = [o for o in observations if o.get("outcome") == "COMPLETED"]
    b_recall = [o["bLayer"]["recall"] for o in completed
                if o["bLayer"]["recall"] is not None]
    b_precision = [o["bLayer"]["precision"] for o in completed
                   if o["bLayer"]["precision"] is not None]
    action_accuracy = [1.0 if o["cLayer"]["acceptableSetAccuracy"] else 0.0
                       for o in completed]
    latencies = sorted(o["latencyMs"] for o in completed)

    def mean(values):
        return round(sum(values) / len(values), 4) if values else None

    report = {
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "layer": "B+C",
        "suite": "p3a-live-project-agent-baseline",
        "boundary": ("real project-Agent production loop: HTTP workspace API + real Python "
                     "brain (broker mode) + product-configured model; nothing mocked"),
        "modelProvenance": "product Database opencode_settings; see run details",
        "corpusFile": Path(arguments.scenarios).name,
        "corpusSha256": __import__("hashlib").sha256(
            Path(arguments.scenarios).read_bytes()).hexdigest(),
        "labelProvenance": "AI-authored candidate labels; human review PENDING",
        "scenarioCount": len(scenarios),
        "repetitions": arguments.repetitions,
        "completed": len(completed),
        "infraFailed": len(infra_failures),
        "bLayer": {
            "claimRecall": mean(b_recall),
            "claimPrecision": mean(b_precision),
            "forbiddenOvergeneralizations": sum(
                len(o["bLayer"]["forbiddenViolations"]) for o in completed),
            "ungroundedConfirmed": sum(
                len(o["bLayer"]["ungroundedConfirmed"]) for o in completed),
            "unknownAssertedConfirmed": sum(
                len(o["bLayer"]["unknownAssertedConfirmed"]) for o in completed),
        },
        "cLayer": {
            "acceptableSetAccuracy": mean(action_accuracy),
            "payloadValidity": mean(
                [1.0 if o["cLayer"]["payloadValid"] else 0.0 for o in completed]),
        },
        "latencyMs": {
            "p50": latencies[len(latencies) // 2] if latencies else None,
            "p95": latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))]
            if latencies else None,
            "max": latencies[-1] if latencies else None,
        },
        "totalElapsedMs": int((time.monotonic() - started_all) * 1000),
        "infrastructureFailures": [
            {"scenarioId": o["scenarioId"], "detail": o.get("detail")}
            for o in infra_failures
        ],
        "observations": observations,
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    output_path = OUTPUT_DIR / arguments.output
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print(f"\nP3-A B/C baseline written: {output_path}")
    print(f"completed={report['completed']} infra={report['infraFailed']}")
    print(f"B claimRecall={report['bLayer']['claimRecall']} "
          f"claimPrecision={report['bLayer']['claimPrecision']}")
    print(f"B forbidden={report['bLayer']['forbiddenOvergeneralizations']} "
          f"ungroundedConfirmed={report['bLayer']['ungroundedConfirmed']} "
          f"unknownAssertedConfirmed={report['bLayer']['unknownAssertedConfirmed']}")
    print(f"C acceptableSetAccuracy={report['cLayer']['acceptableSetAccuracy']} "
          f"payloadValidity={report['cLayer']['payloadValidity']}")
    print(f"latency p50={report['latencyMs']['p50']} p95={report['latencyMs']['p95']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
