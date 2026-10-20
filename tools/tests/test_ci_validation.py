"""Guard mode boundaries and truthful summaries without executing production code."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("validation_summary", ROOT / "tools/ci/validation_summary.py")
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def results(full=False):
    return {"validation-mode": {"result": "success", "outputs": {"full": str(full).lower()}},
            **{name: {"result": "success" if full or name in summary.INDEPENDENT else "skipped"}
               for name in summary.INDEPENDENT + summary.EXTERNAL}}


def test_independent_success_reports_external_skip_and_unmeasured_live():
    report, failures = summary.summarize(results(), False)
    assert failures == []
    assert report.count("| PASS |") == 4
    assert report.count("| SKIPPED_EXTERNAL_DEPENDENCY |") == 4
    assert "NOT_MEASURED" in report


def test_real_failure_cancellation_missing_or_unexpected_skip_never_passes():
    for full in (False, True):
        for name in summary.INDEPENDENT + summary.EXTERNAL:
            for result, status in (("failure", "FAIL"), ("cancelled", "CANCELLED"),
                                   ("skipped", "UNEXPECTED_SKIP"), ("missing", "MISSING")):
                if not full and name in summary.EXTERNAL and result == "skipped":
                    continue
                needs = results(full)
                needs[name] = {"result": result}
                report, failures = summary.summarize(needs, full)
                assert failures
                assert f"| {name} | {status} |" in report


def test_full_mode_requires_external_jobs_to_execute():
    assert summary.summarize(results(True), True)[1] == []
    needs = results()
    needs["validation-mode"]["outputs"]["full"] = "true"
    assert len(summary.summarize(needs, True)[1]) == 4


def test_missing_scope_job_cannot_produce_a_successful_summary():
    needs = results()
    del needs["validation-mode"]
    assert summary.summarize(needs, False)[1]


def test_independent_mode_rejects_accidentally_executed_external_jobs():
    needs = results(True)
    needs["validation-mode"]["outputs"]["full"] = "false"
    assert len(summary.summarize(needs, False)[1]) == 4


def test_scope_output_must_be_explicit_valid_and_consistent():
    for full in (False, True):
        for selected in (None, "", "invalid", str(not full).lower()):
            needs = results(full)
            needs["validation-mode"]["outputs"] = {"full": selected}
            assert summary.summarize(needs, full)[1]


def test_pr_trigger_and_always_running_summary_remain_enabled():
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    assert "\n  pull_request:\n" in workflow
    assert "  validation-summary:\n    if: always()" in workflow
    assert "needs: [validation-mode, frontend, brain, backend-independent, contracts-static, backend, eval-baseline, cross-language, e2e]" in workflow


def test_workflow_keeps_production_dependency_and_explicit_manual_full_mode():
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    for job in summary.EXTERNAL:
        assert f"  {job}:\n    needs: validation-mode\n    if: needs.validation-mode.outputs.full == 'true'" in workflow
    assert "continue-on-error" not in workflow
    assert "default: false" in workflow
    assert "github.event_name == 'workflow_dispatch' && inputs.full_validation == true" in workflow
    build = (ROOT / "backend/build.gradle.kts").read_text(encoding="utf-8")
    assert 'implementation("com.sharedlibs:opencode-zen-spring-boot-starter:1.2.0")' in build
    assert "skipZenStarter" not in build
