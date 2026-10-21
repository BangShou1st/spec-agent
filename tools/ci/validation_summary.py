"""Render truthful CI scope; skipped integrations are never counted as passes."""
import json
import os
from pathlib import Path

INDEPENDENT = ("frontend", "brain", "backend-independent", "contracts-static")
EXTERNAL = ("backend", "eval-baseline", "cross-language", "e2e")


def summarize(needs, full):
    rows = []
    failures = []
    if needs.get("validation-mode", {}).get("result") != "success":
        failures.append("validation-mode did not complete successfully")
    selected = needs.get("validation-mode", {}).get("outputs", {}).get("full")
    if selected not in ("true", "false") or (selected == "true") != full:
        failures.append("validation-mode output missing, invalid or inconsistent")
    for name in INDEPENDENT + EXTERNAL:
        result = needs.get(name, {}).get("result", "missing")
        if name in EXTERNAL and not full and result == "skipped":
            status = "NOT RUN"
            reason = "Explicit manual independent diagnostics; full integrations NOT RUN"
        elif result == "success":
            status, reason = "PASS", "Executed successfully (see job logs for coverage)"
            if name in EXTERNAL and not full:
                failures.append(name + ": external job ran in independent mode")
        else:
            status = {"failure": "FAIL", "cancelled": "CANCELLED",
                      "skipped": "UNEXPECTED_SKIP", "missing": "MISSING"}.get(result, "INVALID")
            reason = "Actual GitHub job result: " + result
            failures.append(name + ": " + result)
        rows.append((name, status, reason))
    scope = "full" if full else "independent"
    lines = [f"## Validation scope: {scope}", "",
             "Independent results do not qualify the full backend, Spring startup, database or live providers.",
             "", "| Job | Status | Evidence / limitation |", "| --- | --- | --- |"]
    lines += [f"| {name} | {status} | {reason} |" for name, status, reason in rows]
    lines += ["", "Live model quality: NOT_MEASURED. No live model requests are part of this workflow."]
    return "\n".join(lines) + "\n", failures


if __name__ == "__main__":
    raw_full = os.environ["CI_FULL"]
    report, failures = summarize(json.loads(os.environ["CI_NEEDS"]), raw_full == "true")
    if raw_full not in ("true", "false"):
        failures.append("CI_FULL must explicitly be true or false")
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as stream:
        stream.write(report)
    print(report)
    if failures:
        raise SystemExit("Validation failed: " + "; ".join(failures))
